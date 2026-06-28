<div align="center">

# Overlay AI

**どのアプリの上にも浮かべて使える、Android 用フローティング AI チャット**

ChatGPT のサブスク枠（Codex OAuth）をそのまま使い、API キー不要。
画面共有・Web 検索・サンドボックス shell・スキル機構まで端末内で完結します。

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-26-orange)
![targetSdk](https://img.shields.io/badge/targetSdk-35-orange)

</div>

> [!IMPORTANT]
> 本アプリは OpenAI / Anthropic とは無関係の非公式プロジェクトです。ログインした ChatGPT アカウントの利用枠を消費します。利用にあたっては各サービスの利用規約をご自身でご確認ください。詳細は [免責事項](#免責事項) を参照してください。

---

## 特徴

- **フローティングバブル UI** — 黒い「＋」バブルを常に画面最前面に表示。タップで縦長のチャットパネルが開き、他アプリを操作しながら相談できます。ドラッグで移動、画面端のゴミ箱へ捨てると終了。
- **API キー不要** — お使いの ChatGPT アカウントで端末上から OAuth ログイン（PKCE・ループバック）。Codex の `responses` エンドポイント経由で、**サブスクの利用枠を消費**します。従量課金の API キーは使いません。
- **画面共有** — 「画面送信」をオンにすると `MediaProjection` で現在の画面をキャプチャして添付。今見ている画面についてそのまま質問できます（送信時はバブル／パネルを自動で隠します）。
- **Web 検索（常時）** — `web_search` ツールを常に有効化。最新情報を踏まえて回答します。
- **サンドボックス shell エージェント** — アプリ専有領域のワークスペース内でのみ動作する `shell` ツールを備え、ファイル生成・加工などを自律的に実行（エージェントループ）。
- **スキル機構** — Claude / Codex と同じ `SKILL.md` 形式に対応。zip でスキルを追加すると、モデルが必要に応じて読み込み・実行します。Progressive disclosure 方式でメタデータのみ常時注入。
- **完全オンデバイス** — 中継サーバやブリッジ用 PC は不要。ログイン情報は `EncryptedSharedPreferences` に暗号化保存。DNS は DoH（Cloudflare）フォールバック付き。

## スクリーンショット

| ダッシュボード | フローティングパネル |
| :---: | :---: |
| <img src="docs/screenshot_dashboard.png" width="240"> | <img src="docs/screenshot_overlay.png" width="240"> |

> `docs/` 配下に画像を配置してください（プレースホルダ）。

## 仕組み

```
┌────────────────────────── Android 端末 ──────────────────────────┐
│                                                                  │
│  MainActivity (Jetpack Compose ダッシュボード)                   │
│    ├─ オーバーレイ権限 / ChatGPT ログインのゲーティング          │
│    └─ スキル管理・各種設定                                       │
│                                                                  │
│  OverlayService (前面サービス / SYSTEM_ALERT_WINDOW)             │
│    ├─ バブル FAB（ドラッグ・ゴミ箱）                             │
│    ├─ OverlayWebHost ─ WebView パネル                           │
│    │     └─ webui (React + Tailwind + shadcn/ui)                │
│    ├─ ScreenCapture (MediaProjection)                           │
│    └─ SkillManager (filesDir/skills, filesDir/workspace)        │
│                                                                  │
│  DirectCodexClient                                              │
│    └─ POST chatgpt.com/backend-api/codex/responses (SSE)        │
│         tools: web_search, shell(function)  /  agent loop       │
│                                                                  │
│  CodexAuth ── PKCE ループバック OAuth → EncryptedSharedPrefs     │
└──────────────────────────────────────────────────────────────────┘
```

チャット UI は WebView 上の React アプリ、ネイティブ側との橋渡しは JavaScript Bridge で行います。モデル呼び出しは Codex の Responses スキーマ（ステートレス、`store:false`、`reasoning.encrypted_content` をインラインで返却）に準拠しています。設計の詳細は [docs/DESIGN.md](docs/DESIGN.md) を参照してください。

## 動作要件

- Android 8.0 (API 26) 以上
- ChatGPT アカウント（ログインに使用）
- 「他のアプリの上に重ねて表示」権限

## 技術スタック

| 領域 | 採用技術 |
| --- | --- |
| ネイティブ | Kotlin, Jetpack Compose（ダッシュボード）, WebView（チャット） |
| チャット UI | Vite + React + TypeScript + Tailwind CSS + shadcn/ui |
| 通信 | OkHttp 4.12 + okhttp-dnsoverhttps（DoH フォールバック）, SSE |
| 認証/保存 | AndroidX Browser, Security-Crypto（EncryptedSharedPreferences）|
| 画面取得 | MediaProjection API |
| ビルド | Android Gradle Plugin 8.5.2 / Gradle 8.7 / JDK 21 |

## ビルド方法

### 前提

- JDK 21
- Android SDK（`platforms;android-35`, `build-tools;35.0.0`）
- Node.js 18 以上（チャット UI のビルド用）

### 1. チャット UI（webui）をビルド

```bash
cd webui
npm install
npm run build
# 生成物 (dist/) を app/src/main/assets/webui/ へ配置
```

> `vite.config` の `outDir` をアプリの assets に向けておくと、コピー手順を省けます。

### 2. APK をビルド

```bash
./gradlew :app:assembleDebug
# 出力: app/build/outputs/apk/debug/app-debug.apk
```

端末にインストールし、初回起動時に「オーバーレイ権限」と「ChatGPT ログイン」を済ませれば利用できます。

## 使い方

1. **ダッシュボード**で ① オーバーレイ権限を許可 → ② ChatGPT にログイン。
2. **「オーバーレイを開始」** をタップ。黒いバブルが最前面に表示されます。
3. バブルをタップしてチャットパネルを開く。長押しでパネルごと移動、ピンチでサイズ変更。
4. 今見ている画面について聞きたいときは **「画面送信」** をオン。
5. ヘッダーの **−**（最小化／状態保持）、**×**（終了）。バブルを画面下のゴミ箱へドラッグしても終了します。

## スキルを追加する

Claude / Codex 互換の `SKILL.md` を含むフォルダを zip 化し、ダッシュボードの「スキル」→「スキルを追加」から取り込みます。

```
my-skill/
├─ SKILL.md            # name / description のフロントマター + 手順
├─ references/         # モデルが必要時に読む補足
├─ templates/          # 再利用するひな形
└─ scripts/            # 実行スクリプト（端末内 shell で動くもの）
```

```markdown
---
name: my-skill
description: 何をするスキルか / どんなときに使うかのトリガーを書く
---

# My skill
手順をここに記述。スクリプトは `sh ./skills/my-skill/scripts/xxx.sh` のように
ワークスペース直下からの相対パスで呼び出します。
```

> 端末側の shell は toybox ベースのため、`python` / `node` 等は使えません。スクリプトは POSIX `sh` + 標準コマンドで完結させてください。

## 設定

- **ワークスペースのリセット** — 起動ごとに作業領域を初期化するか選択できます。
- **モデル** — `gpt-5.5` / `gpt-5.4` / `gpt-5.3-codex` / `gpt-5-codex-mini` / `o3` などから選択可能（利用枠はアカウントの契約に依存）。

## プライバシー / セキュリティ

- ログイントークンは `EncryptedSharedPreferences` に暗号化して端末内に保存します。サーバへ送信しません。
- shell ツールはアプリ専有の作業ディレクトリ内でのみ動作します。
- 画面キャプチャはユーザーが「画面送信」を明示的にオンにしたときだけ行われます。

## 免責事項

本ソフトウェアは OpenAI および Anthropic とは一切関係のない、非公式・無保証のプロジェクトです。ChatGPT の認証情報・利用枠を用いて公式に文書化されていないエンドポイントへアクセスするため、利用は自己責任で行ってください。これに起因するアカウント上の問題や損害について、作者は責任を負いません。各サービスの利用規約を遵守できる範囲でご利用ください。

## コントリビュート

Issue / Pull Request を歓迎します。非公式エンドポイントの仕様変更に追従する性質上、`DirectCodexClient` まわりは壊れやすい点をご了承ください。

## ライセンス

[MIT License](LICENSE) © 2026 mokouliszt

## 作者

- GitHub: [@mokouliszt](https://github.com/mokouliszt)
- 開発を応援していただける方は [Ko-fi](https://ko-fi.com/mokouliszt) へ ☕
