"""Generate original, fictional sample slides. Requires Pillow; fonts are not bundled."""
from pathlib import Path
from io import BytesIO
import argparse
import zipfile
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = '''time(07:00)

##1
本日は、発表の時間配分について説明します。
** 質問対策メモ：この資料の数値は操作練習用の架空の例です。
本文をスクロールして、実際に読んでいる場所を赤線に合わせます。
グレーになった文章は、予定では読み終えているはずの範囲です。

##2
こちらは架空の練習結果です。
[図を説明(00:30)]
練習の回数を増やすと、時間配分が安定する例を示しています。
[補足の時間(00:20)]
この数値は操作を試すための例で、実際の調査結果ではありません。

##3
最後にまとめます。
本文の位置は手動で調整し、予定のペースは固定で進みます。
ご清聴ありがとうございました。
'''

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--font', required=True, help='Path to a Japanese TrueType font (not copied)')
    args = parser.parse_args()
    font = lambda n: ImageFont.truetype(args.font, n)
    def image(number, title, subtitle):
        im = Image.new('RGB', (1280, 720), '#f5f7fb')
        d = ImageDraw.Draw(im)
        d.rectangle((0, 0, 1280, 14), fill='#2169cf')
        d.text((72, 54), 'YomiPace / SAMPLE', font=font(26), fill='#2169cf')
        d.text((72, 118), title, font=font(55), fill='#14243b')
        d.text((72, 208), subtitle, font=font(27), fill='#4c6075')
        if number == 1:
            for i, (a,b) in enumerate((('01', 'ZIPを開く'), ('02', '持ち時間を確認'), ('03', '開始して原稿を動かす'))):
                x = 72 + i*380
                d.rounded_rectangle((x, 330, x+350, 535), radius=18, fill='white', outline='#ccd6e5', width=2)
                d.text((x+25, 350), a, font=font(52), fill='#2169cf')
                d.text((x+25, 450), b, font=font(24), fill='#14243b')
        elif number == 2:
            for i, value in enumerate((55, 75, 90)):
                x = 150 + i*330
                y = 545 - value*2.4
                d.rounded_rectangle((x, y, x+170, 545), radius=12, fill=('#7db0f4','#4b8fe8','#2169cf')[i])
                d.text((x+48, y-52), str(value), font=font(34), fill='#14243b')
                d.text((x+24, 560), f'練習 {i+1}', font=font(25), fill='#4c6075')
        else:
            for i, text in enumerate(('赤線：実際に読んでいる位置', 'グレー：予定上の読了範囲', '振動：予定上の段落完了（設定時）')):
                d.text((96, 330+i*82), text, font=font(35), fill='#14243b')
        d.text((72, 660), '操作練習用の架空資料 / MIT-0', font=font(21), fill='#617386')
        d.text((1180, 660), f'{number:02}', font=font(23), fill='#617386')
        out = BytesIO(); im.save(out, format='PNG'); return out.getvalue()
    slides = [image(1,'発表のペースを、見える形に','スライドと原稿で試す、7分間の操作サンプル'),
              image(2,'練習結果の例','グラフは架空の数値です。30秒・20秒の固定区間を試します。'),
              image(3,'自分のペースで、読み進める','原稿の手動位置と予定タイムラインを見比べます。')]
    samples = ROOT / 'samples'; samples.mkdir(exist_ok=True)
    (samples/'script.txt').write_text(SCRIPT, encoding='utf-8', newline='\n')
    def archive(path, images, script):
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
            for i, data in enumerate(images,1):
                entry=zipfile.ZipInfo(f'{i:03}.png',(2026,11,2,0,0,0)); entry.compress_type=zipfile.ZIP_DEFLATED
                z.writestr(entry,data)
            entry=zipfile.ZipInfo('script.txt',(2026,11,2,0,0,0)); entry.compress_type=zipfile.ZIP_DEFLATED
            z.writestr(entry,script.encode('utf-8'))
    sample = samples/'YomiPace-sample.zip'
    archive(sample, slides, SCRIPT)
    for module in ('app','core'):
        target=ROOT/module/'src/test/resources/sample.zip'; target.parent.mkdir(parents=True,exist_ok=True)
        target.write_bytes(sample.read_bytes())
    regression='time(04:00)\n\n'+'\n'.join(f'##{i}\nこれは第{i}枚の架空の練習スライドです。\n' for i in range(1,10))
    archive(samples/'regression/nine-slides.zip', [slides[i%3] for i in range(9)], regression)
    print('Created public sample and test fixtures (3 and 9 slides).')

if __name__ == '__main__':
    main()
