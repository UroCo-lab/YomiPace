# AIに導入を頼む

PCのファイルやコマンドを操作できるAIへ、GitHub URLや手元のファイルを渡せます。通常のチャットで操作できない場合は、AIが必要なコマンドと端末側の手順を案内します。

## そのまま渡せる依頼文

```text
https://github.com/UroCo-lab/YomiPace を導入して。
README.md、AGENTS.md、docs/AI_SETUP.mdを読んで進めてください。
あとで好みに合わせて改編したいので、ソースも作業フォルダーに残してください。
環境と接続先を調べ、必要な準備・ビルド・導入・確認まで進めてください。
```

ソースZIPを指定する場合：

```text
このYomiPaceのソースZIPを新しいフォルダーに展開して導入して。
中のREADME.mdとAGENTS.mdを読んで、個人改編版としてビルドしてください。
元のZIPを残し、次回の改編に使うフォルダーと変更記録の場所を教えてください。
```

APKを指定する場合：

```text
このYomiPaceのAPKをAndroid端末へ導入して。
対象端末と既存版を確認し、既存の資料を残して進めてください。
あとで改編できるよう、対応するソースの取得先も残してください。
```

## 渡されたものの見分け方

| 入力 | AIが進めること |
|---|---|
| GitHubリポジトリURL | READMEを読み、利用だけならReleasesのAPK、改編するならcloneしたソースを用意 |
| Release URL | 指定版のAPKとSHA256SUMS、改編用には同じタグのソースを取得 |
| ソースZIP／Download ZIP | 新規フォルダーへ展開。`settings.gradle.kts`、`gradlew`、`app/`、`core/`を確認してビルド |
| `.apk` | APKの出所と対応Androidを確認して導入。ソースは別途取得 |
| 連番画像と`script.txt`を含むZIP | 発表資料。導入済みアプリの「ZIPを開く」で取り込み |

古いタグやZIPにこの案内がない場合は、[mainの作業指示](https://github.com/UroCo-lab/YomiPace/blob/main/AGENTS.md)も参照し、ソースの版を混同しないでください。APKから元のKotlinソースへ戻す手順は用いません。

## ソースから導入する

必要なものはJDK **21**、Android SDK Platform **36**、Build Tools **36.0.0**です。ADBで導入する場合はplatform-toolsも用意します。初回はGradleと依存ライブラリの取得にネット接続が必要です。Android端末はAndroid 8.0以降。ソースZIPからもGitなしでビルドできます。

Windows PowerShellで、展開／cloneしたリポジトリのルートから：

```powershell
./scripts/setup.ps1 -CheckOnly
./scripts/setup.ps1
```

JDK／SDKを明示する場合：

```powershell
./scripts/setup.ps1 -JavaHome 'C:\Tools\jdk-21' -SdkPath 'C:\Android\Sdk'
```

スクリプトは環境を確認し、ローカル設定と個人版設定を準備してCoreテスト・Debugビルド・Lintを実行します。既存の`customization.properties`は保持します。`-CheckOnly`では設定やビルド出力を作りません。JDK／SDKのダウンロードやライセンス同意は自動化していません。不足時は表示内容をもとにAIが環境を準備します。組織の実行ポリシーでスクリプトが禁止されている場合は、ルールに従って次のGradle手順を使います。

macOS／Linux、または手動でビルドする場合：

1. JDK 21とSDKを用意し、`JAVA_HOME`とSDKの場所を設定。
2. `customization.properties.example`を`customization.properties`へコピー。
3. `local.properties`へSDKのパスを記載（例：`sdk.dir=/Users/you/Library/Android/sdk`）。
4. Windowsは`./gradlew.bat`、macOS／Linuxは`chmod +x gradlew`のあと次を実行。

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

手動Gradle実行のAPKは`app/build/outputs/apk/debug/app-debug.apk`へ生成されます。Windowsの`setup.ps1`は日本語や長いソースパスでのテスト実行を避けるため、生成物を一時フォルダー配下の`yomipace-builds/<作業先ごとの識別子>/`へ出力し、APKの絶対パスとSHA-256を表示します。出力先を固定したい場合は`-BuildRoot 'C:\Build\YomiPacePersonal'`を指定します。Windowsのユーザー名が日本語の場合も、英数字の出力先を指定してください。個人版は既定でアプリID`jp.cn22.yomipace.personal`、端末上の名前`YomiPace Personal`です。[改編ガイド](CUSTOMIZATION.md)で変更できます。

## Android端末へ入れる

APKを端末へ転送して開くか、USBデバッグを許可してADBを使用します。接続許可・提供元の許可などAndroid側の確認は本人が操作します。

```powershell
adb devices -l
./scripts/setup.ps1 -Install -Serial '実際の端末シリアル'
```

`-Install`は検査・ビルド後に指定端末へ上書きインストールします。APKだけを導入する場合は：

```text
adb -s <対象シリアル> install -r <APKのパス>
```

署名が異なるAPKは上書きできません。既存版を消して解決せず、個人版を別IDでビルドします。公式版と個人版の資料・設定は別々です。移したい資料はアーカイブの「ZIP共有」で書き出し、新しいアプリの「ZIPを開く」で取り込みます。設定は新しいアプリ側で調整します。

アプリを起動し、初回ガイドの表示、開始・停止、手動スクロールを確認します。起動や振動を実機で確認していなければ、その旨を記録します。

## 次回の改編に残すもの

ソースのフォルダー、元のタグ／コミットまたはZIP名、`customization.properties`、自分の署名鍵、`LOCAL_CHANGES.md`を手元に残します。生成APKだけでは次のコード編集に使えません。デバッグ鍵も同じ環境で保持し、別PCへ移る場合は秘密情報として管理します。鍵が変わった場合、既存版の上書き更新はできません。

次は「このフォルダーのYomiPaceを、時計が大きく見えるように改編して。`LOCAL_CHANGES.md`も更新して」と頼めます。GitHubへ保存したい場合は自分のfork／リポジトリを指定してください。
