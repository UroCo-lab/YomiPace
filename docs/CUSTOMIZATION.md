# 自分用に改編する

最初は「時計を大きく」「ボタンを左へ」「演出を控えめに」のように、変えたい見え方や操作をAIへ伝えてください。設定でできる変更は設定から行い、コードを変える場合は変更前後を残してビルドします。

## 公式版と並べて使う

リポジトリ直下の`customization.properties.example`を`customization.properties`へコピーします。UTF-8で編集してください。このファイルはGit管理から除外しています。

```properties
yomipace.applicationId=jp.cn22.yomipace.personal
yomipace.appName=自分用YomiPace
```

`applicationId`は端末がアプリを識別するIDです。各区切りを英字で始め、英数字と`_`を使うドット区切りにします。自分用IDを一度決めたら、更新のたびに変えず同じ署名鍵を使います。`namespace`とKotlinの`package`を書き換える必要はありません。ZIP共有の識別子もアプリIDに追従します。

優先順位はGradleの`-P`指定、`customization.properties`、公式既定値の順です。ファイルも指定もない通常ビルドはアプリID`jp.cn22.yomipace`、名前`YomiPace`です。一度だけ変更する場合：

```powershell
./gradlew.bat :app:assembleDebug '-Pyomipace.applicationId=jp.cn22.yomipace.personal' '-Pyomipace.appName=自分用YomiPace'
```

アプリ一覧の名前を変える設定であり、画面内の説明文やロゴは自動変更しません。公式版から資料を移すときはアーカイブの「ZIP共有」を利用します。

## どこを変えるか

以下のアプリ側ファイルは`app/src/main/java/jp/cn22/yomipace/`にあります。

| 変えたい内容 | まず確認する場所 |
|---|---|
| 文字・時計・ボタンの大きさ、色、振動、左右配置 | アプリの設定画面と`docs/操作説明.md` |
| 画面全体・メニュー・Foldの配置 | `MainActivity.kt` |
| 時計の表示と操作 | `ClockAppearance.kt`、`PlaybackControls.kt` |
| 原稿の文字・赤線・予定ペースの描画 | `ScriptReaderView.kt` |
| 段落完了の振動 | `ParagraphHaptics.kt` |
| 設定の保存・読み込み | `AppViewModel.kt` |
| 原稿作成・スライド編集 | `ScriptCreationModel.kt`、`ScriptCreationScreen.kt` |
| アーカイブ管理・編集ダイアログ | `ManagementDialogs.kt`、`DeckStore.kt` |
| 複製・ZIP書き出し・共有 | `DeckTransfer.kt`、`SharedZipProvider.kt` |
| DOPAGAKIの演出 | `DopamineEngine.kt`、`DopamineRenderer.kt`、`DopamineStage.kt` |
| 原稿記法・時間配分・ZIP取り込み | `core/src/main/kotlin/jp/cn22/yomipace/core/`と`docs/Script仕様.md` |
| 初回ガイド | `samples/welcome/README.md`、`WelcomeSample.kt` |

内部構造を大きく変える前に、この案内と関連テストを読みます。予定ペース、手動スクロール、旧原稿の互換性、保存済み資料と設定を確認してください。

## AIに渡す改編依頼の例

```text
このソースのYomiPaceを自分用に改編して。
時計を見やすく大きくし、よく使う操作ボタンを左側に寄せたい。
まず既存設定でできる範囲を確認し、必要ならコードを変更してください。
個人版のアプリIDと鍵を引き継ぎ、既存の資料・原稿・設定を保持してください。
関連する検査とAPKビルドを行い、LOCAL_CHANGES.mdに変更と確認結果を残してください。
```

変更記録には日付、基点の版、目的、変更ファイル、ビルド手順、アプリID、APK、検査結果、未確認事項を書きます。端末シリアルや秘密鍵のパスなどをGitHubへ載せないでください。

## 自分のGitHubに保存・元の更新を取り込む

cloneする前ならGitHubでforkを作り、自分のforkをcloneすると変更を保存しやすくなります。すでにclone済みの場合は`git remote -v`を確認し、`origin`を自分のfork、`upstream`を公式リポジトリに設定する方法があります。既存remoteを確認してから変更してください。

ソースZIPにはGitの履歴がありません。継続開発なら、対応タグからcloneし直した作業コピーへ自分の変更を移すと元の更新を取り込みやすくなります。ZIPのまま始める場合は新しくGit管理を始め、公開先が自分のリポジトリであることを確認します。

更新時は自分の変更をコミットしてから`upstream`をfetchし、差分を確認してmergeします。競合を解決して再検証し、同じID・同じ鍵のAPKで上書き更新します。`git reset --hard`で自分の改編を消す方法は使いません。実際の公開手順は[CONTRIBUTING.md](../CONTRIBUTING.md)を参照してください。

自作部分はMIT-0で改編・再配布できます。外部ライブラリのライセンスは[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)に従います。
