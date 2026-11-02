"""Collect notices from the exact release runtime artifacts resolved by Gradle.

Run :app:runtimeInventory first, then pass --inventory and --cache (Gradle
modules-2/files-2.1). Paths are used locally and are not written to the report.
"""
from pathlib import Path
from io import BytesIO
import argparse
import hashlib
import re
import shutil
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--inventory',type=Path,required=True)
    p.add_argument('--cache',type=Path,required=True)
    args=p.parse_args()
    notices=ROOT/'licenses/embedded'; notices.mkdir(parents=True,exist_ok=True)
    lines=[]
    def license_names(group, artifact, version, depth=0):
        if depth > 8: raise ValueError('POM parent nesting exceeds limit')
        result=[]
        for pom in (args.cache/group/artifact/version).rglob('*.pom'):
            doc=ET.parse(pom)
            names=[x.findtext('m:name',default='',namespaces=NS) for x in doc.findall('m:licenses/m:license',NS)]
            if not names:
                parent=doc.find('m:parent',NS)
                if parent is not None:
                    coords=[parent.findtext('m:'+n,namespaces=NS) for n in ('groupId','artifactId','version')]
                    if all(coords): names=license_names(*coords,depth+1)
            result.extend(names)
        return result
    for row in args.inventory.read_text(encoding='utf-8').splitlines():
        coord, filename=row.split('\t',1)
        group, artifact, version=coord.split(':')
        path=Path(filename)
        if group == 'yomipace' and artifact == 'core' and version == 'unspecified': continue
        names=license_names(group,artifact,version)
        # Every accepted group below is verified by its published POM.
        if not names or not all('apache' in n.lower() and '2' in n for n in names):
            raise SystemExit(f'License requires review: {coord}: {names}')
        key=f'{group}.{artifact}-{version}'
        found=[]
        def extract(z,prefix=''):
            for item in z.namelist():
                leaf=item.rsplit('/',1)[-1].lower()
                if not item.endswith('/') and re.search(r'^(license|notice|copying)([._-]|$)',leaf):
                    safe=re.sub(r'[^A-Za-z0-9._-]','_',prefix+item)
                    name=f'{key}--{safe}'
                    (notices/name).write_bytes(z.read(item))
                    found.append(name)
        with zipfile.ZipFile(path) as z:
            extract(z)
            if 'classes.jar' in z.namelist():
                with zipfile.ZipFile(BytesIO(z.read('classes.jar'))) as jar: extract(jar,'classes.jar_')
        links=', '.join(f'[原文](licenses/embedded/{n})' for n in found) or '[Apache-2.0](licenses/Apache-2.0.txt)'
        lines.append(f'| `{coord}` | Apache-2.0 | {links} |')
    header='''# 外部ソフトウェアのライセンス

自作部分のMIT-0は以下の外部ソフトウェアへ再適用しません。配布時も各ライセンス・著作権表示・NOTICEを保持してください。下表は `releaseRuntimeClasspath` の実際の解決結果です。各Maven POMのライセンスを確認し、JAR／AAR内のLICENSE・NOTICEを原文のまま `licenses/embedded/` に保存しています。

AndroidXの著作者はThe Android Open Source Project、Kotlin・kotlinx・JetBrains annotationsの著作者はJetBrainsおよび各貢献者、Guava ListenableFutureはThe Guava Authors、JSpecifyはThe JSpecify Authorsです。正確な表示は各原文を参照してください。APKには `assets/third_party_notices/` として本文・一覧を収録します。

| 配布APKの依存物（group:artifact:version） | ライセンス | 原文 |
|---|---|---|
'''
    footer='''

## ビルド・テスト用ツール

これらの実行バイナリは、Gradle Wrapper JARを除きリポジトリや配布APKには同梱しません。開発環境が公式リポジトリから取得します。テスト専用の推移依存物は各配布元の条件に従います。

| ソフトウェア | ライセンス・出典 |
|---|---|
| Gradle Wrapper 8.11.1 | Apache-2.0。Copyright 2015 the original author or authors。JAR同梱。 [Gradle](https://github.com/gradle/gradle/tree/v8.11.1/gradle/wrapper) |
| Android Gradle Plugin / Android SDK | Apache-2.0等およびAndroid SDKの利用条件。[Android](https://developer.android.com/studio/terms) |
| Kotlin Gradle plugin 2.1.20 | Apache-2.0。[Kotlin](https://github.com/JetBrains/kotlin) |
| JUnit 4.13.2 | EPL-1.0。[JUnit](https://github.com/junit-team/junit4) |
| Hamcrest Core 1.3（テスト推移依存） | BSD-3-Clause。[Hamcrest](https://github.com/hamcrest/JavaHamcrest) |
| Robolectric 4.14.1 | MIT。[Robolectric](https://github.com/robolectric/robolectric) |
| Roborazzi 1.44.0 | MIT。[Roborazzi](https://github.com/takahirom/roborazzi) |
| AndroidX Test / Compose UI Test / Window Testing | Apache-2.0。[AndroidX](https://android.googlesource.com/platform/frameworks/support/) |
| Pillow（サンプル再生成時のみ） | HPND等。[Pillow](https://github.com/python-pillow/Pillow/blob/main/LICENSE) |

READMEの画面例は、アプリが表示するOSのフォントを使った自動テストの画像です。フォントファイルそのものは配布しません。サンプルの再生成にもローカルのフォントを使用し、フォントファイルをZIPへ含めません。
'''
    text=header+'\n'.join(lines)+footer
    (ROOT/'THIRD_PARTY_NOTICES.md').write_text(text,encoding='utf-8',newline='\n')
    assets=ROOT/'app/src/main/assets/third_party_notices'
    assets.mkdir(parents=True,exist_ok=True)
    shutil.copytree(ROOT/'licenses',assets/'licenses',dirs_exist_ok=True)
    (assets/'THIRD_PARTY_NOTICES.md').write_text(text,encoding='utf-8',newline='\n')
    shutil.copy2(ROOT/'LICENSE',assets/'YomiPace-MIT-0.txt')
    print(f'Checked {len(lines)} runtime artifacts; preserved {len(list(notices.iterdir()))} embedded notices.')

if __name__=='__main__':
    main()
