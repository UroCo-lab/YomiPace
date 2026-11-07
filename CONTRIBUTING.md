# 変更・検証・GitHubへの反映

導入は[AI_SETUP](docs/AI_SETUP.md)、個人版の識別とコードの案内は[CUSTOMIZATION](docs/CUSTOMIZATION.md)を参照してください。ソースZIPからのビルドにはGitは不要です。変更をGitHubへ保存する場合にGitと公開先を準備します。

## 変更を検証する

Windowsは`./gradlew.bat`、macOS／Linuxは`./gradlew`を使います。基本の検査：

```text
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

アプリの挙動を変更した場合は関連するUnit/UIテストも実行します。例えば原稿のコメント仕様なら：

```text
./gradlew :core:test :app:testDebugUnitTest --tests jp.cn22.yomipace.ScriptCommentsAppTest
```

全体を検査する場合：

```text
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Releaseを配布する変更では対応するReleaseテスト・Lint・署名・APK内容も確認します。結果は`app/build/reports/`、`core/build/reports/`に出ます。`-PbuildRoot`を指定した環境では出力先が変わります。Windowsで日本語や長いパスの影響を受ける場合は、`scripts/setup.ps1`または`-PbuildRoot=C:/Build/YomiPacePersonal`を利用します。`setup.ps1`の出力先は実行時に表示します。

UIテストには画像表示待機のタイムアウトが記録されています。失敗したテストは名前とエラーを確認し、関連するテストの成功と全体の結果を分けて報告してください。再実行の成功だけで原因が解消したとは判断しません。端末での導入・振動・Fold開閉は実機で確認した範囲を記録します。

## GitHubへ保存する

個人改編は自分のforkまたは自分が管理するリポジトリへ保存します。「GitHubへ反映して」という依頼では、既に指定されている反映先と権限を確認して進めます。公式リポジトリへ提案する場合は作業ブランチからPull Requestを作成します。所有者として公式mainへの反映を依頼された場合は、その指定に従います。

```text
git status --short
git remote -v
git diff --check
git diff
git add <変更した公開対象ファイル>
git commit -m "Describe the change"
git push origin <対象ブランチ>
```

ステージした差分も確認し、`git add .`で無関係なファイルをまとめて送らないでください。push後はリモートのコミットとActionsの結果を確認します。Actionsが進行中なら進行中、失敗なら失敗と報告します。

`customization.properties`、`local.properties`、`LOCAL_CHANGES.md`、秘密鍵、認証情報、個人の資料は公開対象から外します。`LOCAL_CHANGES.md`はローカル記録です。公開向けの変更説明は個人情報を除いてPR本文や`docs/`へ記載します。既存の配布APKを更新する場合は署名鍵の継続を確認します。個人の鍵で公式版へ上書き更新することはできません。

ドキュメントや導入補助だけの更新はmainへの反映で配布でき、APKのバージョンや既存のReleaseを更新する必要はありません。Releaseが依頼された場合は新しいタグと配布物を作り、既存のタグやAPKを差し替えません。
