"""Galaxy の Scan3D アプリから写真を受け取り、PCで3D化して返すサーバー。

使い方:  python scan_server.py            （設定は同じフォルダの config.json）
"""
import html
import json
import re
import socket
import threading
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pipeline

HERE = Path(__file__).resolve().parent
CONFIG = json.loads((HERE / "config.json").read_text(encoding="utf-8"))
DATA = Path(CONFIG.get("data_dir", HERE / "scans"))
DATA.mkdir(parents=True, exist_ok=True)

NAME_RE = re.compile(r"^[A-Za-z0-9_\-]{1,64}$")
FILE_RE = re.compile(r"^[A-Za-z0-9_\-]{1,64}\.(jpg|jsonl)$")

# 同時に動かす再構成は1つだけ（GPU・メモリを取り合わないように）
job_lock = threading.Lock()
status_lock = threading.Lock()


def session_dir(name: str) -> Path:
    return DATA / name


def read_status(name: str) -> dict:
    p = session_dir(name) / "status.json"
    if p.exists():
        return json.loads(p.read_text(encoding="utf-8"))
    has_model = (session_dir(name) / "model.glb").exists()
    return {"state": "done" if has_model else "idle", "step": "", "progress": 1.0 if has_model else 0.0, "message": ""}


def write_status(name: str, **kw):
    with status_lock:
        st = read_status(name)
        st.update(kw)
        (session_dir(name) / "status.json").write_text(json.dumps(st, ensure_ascii=False), encoding="utf-8")


def process(name: str, quality: str):
    write_status(name, state="queued", step="順番待ち", progress=0.0, message="")
    with job_lock:
        try:
            def report(step, progress, message):
                print(f"[{name}] {step} {progress:.0%} {message}", flush=True)
                write_status(name, state="running", step=step, progress=progress, message=message)

            pipeline.run(session_dir(name), CONFIG, quality, report)
            write_status(name, state="done")
        except pipeline.PipelineError as e:
            write_status(name, state="error", message=str(e))
        except Exception as e:
            traceback.print_exc()
            write_status(name, state="error", message=f"{type(e).__name__}: {e}")


VIEWER_HTML = """<!doctype html>
<html lang="ja"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{name}</title>
<script type="module" src="https://unpkg.com/@google/model-viewer@4.0.0/dist/model-viewer.min.js"></script>
<style>
 body{{margin:0;background:#1b1b1f;color:#eee;font-family:sans-serif}}
 model-viewer{{width:100vw;height:calc(100vh - 48px);background:#2a2a30}}
 header{{height:48px;display:flex;align-items:center;gap:16px;padding:0 12px}}
 a{{color:#8cf}}
</style></head><body>
<header><a href="/">一覧</a><span>{name}</span><span id="st">{status}</span><a href="/api/scans/{name}/model.glb" download>GLBを保存</a></header>
{body}
</body></html>"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def send(self, code, body: bytes, ctype="application/json; charset=utf-8"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, obj, code=200):
        self.send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"))

    def route(self):
        """/api/scans/<name>/<rest...> を (name, rest) に分ける"""
        parts = urlparse(self.path).path.strip("/").split("/")
        return parts

    def do_GET(self):
        p = self.route()
        if p == [""]:
            return self.index()
        if len(p) == 2 and p[0] == "viewer" and NAME_RE.match(p[1]):
            return self.viewer(p[1])
        if len(p) >= 4 and p[:2] == ["api", "scans"] and NAME_RE.match(p[2]):
            name, what = p[2], p[3]
            d = session_dir(name)
            if what == "files":
                found = list((d / "images").glob("*.jpg")) + list(d.glob("*.jsonl"))
                files = {f.name: f.stat().st_size for f in found}
                return self.send_json({"files": files})
            if what == "status":
                return self.send_json(read_status(name))
            if what == "model.glb" and (d / "model.glb").exists():
                return self.send((d / "model.glb").read_bytes(), "model/gltf-binary")
            if what == "log" and (d / "pipeline.log").exists():
                return self.send((d / "pipeline.log").read_bytes(), "text/plain; charset=utf-8")
        self.send_json({"error": "not found"}, 404)

    def do_PUT(self):
        p = self.route()
        if len(p) == 5 and p[:2] == ["api", "scans"] and NAME_RE.match(p[2]) and p[3] == "files" and FILE_RE.match(p[4]):
            # 画像は images/、撮影向きの記録はセッション直下（COLMAPに画像以外を読ませない）
            dest_dir = session_dir(p[2]) / ("images" if p[4].endswith(".jpg") else "")
            dest_dir.mkdir(parents=True, exist_ok=True)
            length = int(self.headers.get("Content-Length", "0"))
            tmp = dest_dir / (p[4] + ".part")
            with tmp.open("wb") as f:
                remaining = length
                while remaining > 0:
                    chunk = self.rfile.read(min(1 << 20, remaining))
                    if not chunk:
                        break
                    f.write(chunk)
                    remaining -= len(chunk)
            if remaining:
                tmp.unlink(missing_ok=True)
                return self.send_json({"error": "incomplete"}, 400)
            tmp.replace(dest_dir / p[4])
            return self.send_json({"ok": True})
        self.send_json({"error": "not found"}, 404)

    def do_POST(self):
        p = self.route()
        if len(p) == 4 and p[:2] == ["api", "scans"] and NAME_RE.match(p[2]) and p[3] == "process":
            name = p[2]
            if read_status(name).get("state") in ("queued", "running"):
                return self.send_json({"ok": True, "already": True})
            quality = parse_qs(urlparse(self.path).query).get("quality", ["normal"])[0]
            threading.Thread(target=process, args=(name, quality), daemon=True).start()
            return self.send_json({"ok": True})
        self.send_json({"error": "not found"}, 404)

    def index(self):
        rows = []
        for d in sorted((x for x in DATA.iterdir() if x.is_dir()), reverse=True):
            st = read_status(d.name)
            n = len(list((d / "images").glob("*.jpg"))) if (d / "images").exists() else 0
            rows.append(f'<li><a href="/viewer/{d.name}">{d.name}</a> {n}枚 — {html.escape(st["state"])} '
                        f'{html.escape(st.get("step", ""))}</li>')
        body = "<h1>Scan3D</h1><ul>" + "".join(rows) + "</ul>"
        page = f'<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">' \
               f'<title>Scan3D</title><body style="font-family:sans-serif">{body}</body>'
        self.send(200, page.encode("utf-8"), "text/html; charset=utf-8")

    def viewer(self, name):
        st = read_status(name)
        if (session_dir(name) / "model.glb").exists():
            body = (f'<model-viewer src="/api/scans/{name}/model.glb" camera-controls touch-action="pan-y" '
                    f'shadow-intensity="1" exposure="1" ar ar-modes="scene-viewer webxr quick-look"></model-viewer>')
            status = ""
        else:
            body = '<p style="padding:12px">まだ完成していません。数十秒ごとに再読み込みしてください。' \
                   f'<a href="/api/scans/{name}/log">ログ</a></p>'
            status = f'{st["state"]} {st.get("step", "")} {int(st.get("progress", 0) * 100)}% {st.get("message", "")}'
        page = VIEWER_HTML.format(name=name, status=html.escape(status), body=body)
        self.send(200, page.encode("utf-8"), "text/html; charset=utf-8")


def lan_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


if __name__ == "__main__":
    port = int(CONFIG.get("port", 8795))
    # 前回中断した処理を「エラー」に戻しておく
    for d in DATA.iterdir():
        if d.is_dir() and read_status(d.name).get("state") in ("queued", "running"):
            write_status(d.name, state="error", message="サーバー再起動で中断されました。もう一度3D化してください")
    print(f"Scan3D サーバー起動: アプリに http://{lan_ip()}:{port} を入力してください")
    print(f"保存先: {DATA}")
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
