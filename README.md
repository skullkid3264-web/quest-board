# 依頼掲示板（Quest Board）

やることをファンタジー世界の「依頼書」として掲示板に貼り、終わったら「クエストクリア」で達成する、自分専用の Android アプリ。

- 写真はモノクロの版画風に変わって依頼書に載る（撮影・ギャラリー両方OK）
- 依頼書をタップすると詳細が開き、絵は2本指で拡大・2回タップで寄れる
- 達成した依頼は「冒険の記録」に綴じられる
- ドット絵は `app/src/main/assets/www/pixel/` に置くと反映（詳しくはそのフォルダの README）

## しくみ

- 画面: `app/src/main/assets/www/index.html`
- Android の外枠（カメラ、戻るボタン、自動更新）: `app/src/main/java/app/questboard/MainActivity.java`
- `main` に変更が入ると GitHub Actions が APK を作り、Releases に「第N版」として公開する
- アプリは起動時に Releases を確認し、新しい版があれば「更新する」ボタンを出す

## 必要な Secrets（Settings → Secrets and variables → Actions）

| 名前 | 中身 |
|---|---|
| `KEYSTORE_BASE64` | 署名鍵（base64） |
| `KEYSTORE_PASSWORD` | 署名鍵のパスワード |

署名鍵が変わると上書き更新できなくなるので、`signing-secrets.txt` は大切に保管すること。

## データについて

依頼や写真はスマホの中だけに保存される（GitHub には上がらない）。アプリを更新してもデータは残るが、アンインストールすると消える。
