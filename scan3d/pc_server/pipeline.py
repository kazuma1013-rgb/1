"""写真 → 3Dモデル（GLB）の再構成パイプライン。COLMAP で位置合わせ、OpenMVS で高密度化・メッシュ・テクスチャ。"""
import shutil
import struct
import subprocess
from pathlib import Path

# 品質ごとの設定
QUALITY = {
    "normal": {"undistort_size": 2000, "densify_level": 2, "refine": False, "decimate": 0.3},
    "high": {"undistort_size": 3200, "densify_level": 1, "refine": True, "decimate": 0.6},
}


class PipelineError(Exception):
    pass


def _registered_images(model_dir: Path) -> int:
    images_bin = model_dir / "images.bin"
    if not images_bin.exists():
        return 0
    with images_bin.open("rb") as f:
        return struct.unpack("<Q", f.read(8))[0]


def run(session_dir: Path, cfg: dict, quality: str, report):
    """report(step, progress, message) で進み具合を知らせる。成功すると session_dir/model.glb を作る。"""
    q = QUALITY.get(quality, QUALITY["normal"])
    colmap = cfg["colmap"]
    mvs = Path(cfg["openmvs_dir"])
    images = session_dir / "images"
    work = session_dir / "work"
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    log_path = session_dir / "pipeline.log"
    log_path.write_text("", encoding="utf-8")

    def sh(step, progress, args):
        report(step, progress, " ".join(str(a) for a in args[:2]))
        with log_path.open("a", encoding="utf-8") as log:
            log.write("\n$ " + " ".join(str(a) for a in args) + "\n")
            log.flush()
            proc = subprocess.run([str(a) for a in args], cwd=work, stdout=log, stderr=subprocess.STDOUT)
        if proc.returncode != 0:
            raise PipelineError(f"{step} で失敗しました（終了コード {proc.returncode}）。pipeline.log を確認してください")

    n_images = len(list(images.glob("*.jpg")))
    if n_images < 20:
        raise PipelineError(f"写真が {n_images} 枚しかありません（20枚以上必要）")

    db = work / "database.db"
    sparse = work / "sparse"
    dense = work / "dense"
    sparse.mkdir()

    # 1. 特徴点の抽出と照合（同じ端末・同じレンズなのでカメラは1台扱い）
    sh("特徴点の抽出", 0.05, [colmap, "feature_extractor", "--database_path", db, "--image_path", images,
                         "--ImageReader.single_camera", "1"])
    matcher = "exhaustive_matcher" if n_images <= 300 else "sequential_matcher"
    sh("特徴点の照合", 0.15, [colmap, matcher, "--database_path", db])

    # 2. 撮影位置の推定
    sh("撮影位置の推定", 0.30, [colmap, "mapper", "--database_path", db, "--image_path", images,
                         "--output_path", sparse])
    models = sorted((d for d in sparse.iterdir() if d.is_dir()), key=_registered_images, reverse=True)
    if not models:
        raise PipelineError("撮影位置を推定できませんでした。重なりが足りない可能性があります")
    best = models[0]
    used = _registered_images(best)
    report("撮影位置の推定", 0.40, f"{n_images}枚中 {used}枚を使用")
    if used < n_images * 0.5:
        report("撮影位置の推定", 0.40, f"注意: {n_images}枚中 {used}枚しかつながりませんでした。部分的なモデルになります")

    sh("歪み補正", 0.45, [colmap, "image_undistorter", "--image_path", images, "--input_path", best,
                      "--output_path", dense, "--output_type", "COLMAP",
                      "--max_image_size", q["undistort_size"]])

    # 3. OpenMVS：高密度点群 → メッシュ → テクスチャ
    exe = lambda name: mvs / name
    sh("OpenMVSへ変換", 0.50, [exe("InterfaceCOLMAP"), "-i", dense, "-o", work / "scene.mvs",
                           "--image-folder", dense / "images", "-w", work])
    sh("高密度点群", 0.55, [exe("DensifyPointCloud"), work / "scene.mvs",
                       "--resolution-level", q["densify_level"], "-w", work])
    sh("メッシュ作成", 0.75, [exe("ReconstructMesh"), work / "scene_dense.mvs",
                        "-p", work / "scene_dense.ply", "-w", work])
    mesh = work / "scene_dense_mesh.ply"
    if q["refine"]:
        sh("メッシュの精細化", 0.82, [exe("RefineMesh"), work / "scene_dense.mvs", "-m", mesh,
                              "-o", work / "scene_dense_mesh_refine.mvs", "--resolution-level", "1", "-w", work])
        mesh = work / "scene_dense_mesh_refine.ply"
    sh("テクスチャ貼り", 0.90, [exe("TextureMesh"), work / "scene_dense.mvs", "-m", mesh,
                          "-o", work / "model.mvs", "--export-type", "glb",
                          "--decimate", q["decimate"], "-w", work])

    glbs = sorted(work.glob("model*.glb"), key=lambda p: p.stat().st_size, reverse=True)
    if not glbs:
        raise PipelineError("GLB が出力されませんでした。OpenMVS のバージョン（2.2以上）を確認してください")
    shutil.copyfile(glbs[0], session_dir / "model.glb")
    report("完了", 1.0, f"model.glb {glbs[0].stat().st_size / 1e6:.1f}MB（使用写真 {used}/{n_images}枚）")
