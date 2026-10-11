# このリポジトリについて（Claude向けメモ）

- やりとりはすべて日本語。途中経過も日本語で書く。
- `status.sh` … ディスク容量を表示する小さなスクリプト。
- `scan3d/` … Galaxy S25 Ultra で人サイズの対象を撮影し、PCで3D化するプロジェクト。詳細は `scan3d/README.md`。

## scan3d の前提

- 端末: Galaxy S25 Ultra（SM-S938Q、Snapdragon 8 Elite、RAM 12GB、Android 16）。ToFセンサー前提にしない。
- PC: Windows、RTX 5070 Ti、CUDA 12.8。作業フォルダは `C:\AiTool\`、大きいデータは `E:\`。
- 方式: アプリは CameraX で高解像度写真＋ゲーム用回転ベクトルで撮影方向を記録（ARCoreは未使用）。PCで COLMAP → OpenMVS → GLB。
- APK はこのクラウド環境ではビルドできない（dl.google.com がブロックされ Android SDK が入らない）。
  `.github/workflows/scan3d-android.yml` が GitHub Actions でビルドし、リリース `scan3d-latest` に `scan3d-debug.apk` を置く。
- 署名は `scan3d/android/app/debug.keystore`（固定のデバッグ鍵）。上書きインストールできるよう変更しない。
- PCサーバーは Python 標準ライブラリのみで書く（追加インストールを増やさない）。

## 関連する過去の作業（別セッション）

- toyland_vr（`C:\AiTool\toyland_vr`）: three.js/WebXR の3Dおもちゃ屋。Quest 3 で表示。GLB＋KTX2。スキャン結果の取り込み先候補。
- 自作配信APK `com.kazum.toylandserver`、Tripo 3D（AI生成）、`E:\photogrammetry`（ドローン写真測量）。
- Colab Pro は「準備を全部済ませてから接続し、終わったら即切断」の条件で使用可。

## 今後の候補

- 背景の自動除去（人物マスク）でモデルを対象だけにする
- ARCore の位置情報を COLMAP の初期値に使って高速化・安定化
- Gaussian Splatting 版の出力
- toyland_vr への自動取り込み（KTX2 変換）
