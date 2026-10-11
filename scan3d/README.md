# Scan3D：Galaxyで撮って、PCで3Dにする

人くらいの大きさのもの（人物、マネキン、大きなぬいぐるみ、家具など）を、Galaxy S25 Ultra で周囲から撮影し、PC（RTX 5070 Ti）で写真測量してテクスチャ付きの3Dモデル（GLB）にします。

```
Galaxy（Scan3Dアプリ）── Wi-Fi ──> PC（scan_server.py）── COLMAP → OpenMVS ──> model.glb
        ↑                                                                      │
        └───────────────── ブラウザで3D表示・GLB保存（Questでも見られる） ─────┘
```

## 1. PC側の準備（初回だけ）

1. **Python 3.10以上**を入れます。追加ライブラリは不要です。
2. **COLMAP**（Windows用のCUDA版）をダウンロードして展開します。
   - https://github.com/colmap/colmap/releases の `colmap-x.xx-windows-cuda.zip`
   - 例: `C:\AiTool\scan3d_tools\colmap\COLMAP.bat`
3. **OpenMVS**（Windows版、2.2以上）をダウンロードして展開します。
   - https://github.com/cdcseacave/openMVS/releases
   - 例: `C:\AiTool\scan3d_tools\openmvs\`（`InterfaceCOLMAP.exe` や `TextureMesh.exe` があるフォルダ）
4. `pc_server\start_server.bat` をダブルクリックします。
   - 初回は `config.json` が作られるので、COLMAPとOpenMVSの場所、保存先（`data_dir`）を書き換えてから、もう一度起動します。
5. 起動すると `アプリに http://192.168.x.x:8795 を入力してください` と表示されます。
   - Windowsファイアウォールの確認が出たら「プライベートネットワーク」を許可してください。

## 2. Galaxyへのインストール

Galaxyのブラウザで次を開いてAPKをダウンロードし、インストールします（提供元不明のアプリを許可）。

https://github.com/kazuma1013-rgb/1/releases/download/scan3d-latest/scan3d-debug.apk

PCからadbで入れる場合：`adb install -r scan3d-debug.apk`

## 3. スキャンのしかた（人サイズ）

1. アプリ上部に、PCで表示されたURLを入力します。
2. 「新しくスキャンする」を押します。
3. 対象から **1.5〜2m** 離れ、対象が画面いっぱいに収まるようにして、**ゆっくり一周** します。
   - 自動撮影がONなら、ブレていないときに、まだ撮っていない方向だけ自動で撮影します。
   - 右上の輪が撮影済みの方向です（内側=低、中央=中、外側=高）。黄色い針が今の向きです。
4. 高さを変えて3周します。
   - **中**：胸の高さで、少し見下ろして一周（必須）
   - **低**：しゃがんで、水平〜少し見上げて一周（足元・あごの下）
   - **高**：腕を上げて、頭上から見下ろして一周（頭頂・肩）
5. 「完了」→ 一覧で「PCで3D化」→ 品質を選びます。
   - 標準：数分〜十数分　／　高品質：数十分
6. 完成したら「見る」でブラウザに3Dが表示されます。GLBの保存もできます。
   - PCやQuest 3のブラウザから `http://PCのIP:8795/` を開いても一覧が見られます。

### きれいに撮るコツ

- **人物を撮るときは、モデルの人に完全に静止してもらいます**（まばたき程度はOK、呼吸で肩が動くのは少し影響します）。撮影は2〜3分で終わらせるのが理想です。
- 明るく、影の少ない場所（曇りの日の屋外や、明るい室内）が向いています。
- 写真同士が **7割以上重なる** ように少しずつ回ります（自動撮影なら10°ごと）。
- 真っ白な壁、ガラス、鏡、黒くて光沢のある服は苦手です。
- 背景も一緒に3Dになります。不要な部分は Blender で削ってください。

## 4. うまくいかないとき

| 症状 | 確認すること |
|---|---|
| 送信失敗 | PCでサーバーが動いているか。GalaxyとPCが同じWi-Fiか。URLのIPとポート |
| 「撮影位置を推定できませんでした」 | 重なり不足。もっと細かく、ゆっくり一周し直す |
| モデルが一部欠ける | その方向の写真が少ない。「追加撮影」で足して再度3D化 |
| その他のエラー | ブラウザで `http://PCのIP:8795/api/scans/<スキャン名>/log` を開いてログを見る |

## フォルダ構成

- `android/` … Galaxy用アプリ（Kotlin、CameraX）。GitHub Actions でビルドされ、上記リリースにAPKが置かれます。
- `pc_server/scan_server.py` … 受信・状態管理・ブラウザ表示（Python標準ライブラリのみ）
- `pc_server/pipeline.py` … COLMAP → OpenMVS の再構成手順
