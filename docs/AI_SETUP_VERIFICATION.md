# AI向け導入・改編支援の検証（2026-11-07）

基点は0.8.1のコミット`0bc4e0707e4cfe7517d3278c02d33743d7a8e3e6`です。READMEの導入口、AGENTS、導入／改編／GitHub反映のガイド、Windows用`setup.ps1`、ローカルの個人版設定を追加しました。

## 確認したこと

- Windows、PowerShell 7.6、JDK 21、Android SDK 36／Build Tools 36.0.0で検証。
- 通常ビルド：Core 28/28、Debug APK生成、Lintエラー0・警告33。
- Git履歴やローカル設定を含めないソースZIPを展開した作業先で、`setup.ps1`を実行。英数字の`-BuildRoot`を指定し、Core 28/28、Debug APK生成、Lintエラー0・警告33。
- APK内の通常版アプリID`jp.cn22.yomipace`・名前`YomiPace`と、個人版アプリID`jp.cn22.yomipace.personal`・名前`YomiPace Personal`を確認。起動Activityのnamespaceは同一。
- ローカル個人版設定がある状態で`-P`を指定し、アプリID`org.example.yomipacecheck`、日本語と`&`を含む名前`読み & Example`へ上書きできることをマージ済みManifestで確認。共有Providerの識別子もアプリIDに追従。
- この上書き設定で`SlideLibraryOperationsTest`が8/8成功。編集・複製・ZIP共有・共有URIの読み取り・失敗後の再試行を確認。
- `-CheckOnly`が設定ファイルを作成／変更せず、作業ディレクトリと環境変数を復元することを確認。PATHからのJava検出も確認。
- JDK不足、SDK不足、インストール先の未指定、`-CheckOnly`と`-Install`の同時指定が、ビルドや導入の前にエラーになることを確認。
- 追加ガイドとREADMEの相対リンク、PowerShellの構文、Gitの差分形式を確認。ローカル設定と変更記録がGit管理から除外されることを確認。

## パスに関する修正

日本語を含む長い展開先へ直接ビルド出力を置くと、Coreテストが`ClassNotFoundException`で起動できませんでした。`setup.ps1`は出力を作業先ごとに分けた一時フォルダーへ移し、`-BuildRoot`での明示指定にも対応します。英数字の出力先で、同じソースのCoreテストとビルドが成功しました。

## 確認の範囲

端末へのインストール、実機の起動・振動・Fold開閉、macOS／Linux、Windows PowerShell 5.1の実行は未確認です。アプリ全体のUIテストは再実行していません。公開済み0.8.1のAPKとタグは保持し、この更新でReleaseは作成していません。
