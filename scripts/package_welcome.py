"""Package the rendered welcome slides. Python standard library only."""
from pathlib import Path
import hashlib
import json
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'samples/welcome'
content = json.loads((SOURCE / 'content.json').read_text(encoding='utf-8'))
assert len(content['slides']) == 10
script = 'time(' + content['duration'] + ')\n\n' + '\n'.join(
    f"#{i}\n{slide['script']}\n" for i, slide in enumerate(content['slides'], 1))
(SOURCE / 'script.txt').write_text(script, encoding='utf-8', newline='\n')
target = ROOT / 'samples/YomiPace-welcome.zip'
with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for i in range(1, 11):
        image = SOURCE / 'slides' / f'{i:03}.png'
        assert image.read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
        info = zipfile.ZipInfo(image.name, (2026, 11, 2, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, image.read_bytes())
    info = zipfile.ZipInfo('script.txt', (2026, 11, 2, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    archive.writestr(info, script.encode('utf-8'))
with zipfile.ZipFile(target) as archive:
    assert archive.testzip() is None
asset = ROOT / 'app/src/main/assets/welcome/YomiPace-welcome.zip'
asset.parent.mkdir(parents=True, exist_ok=True)
asset.write_bytes(target.read_bytes())
print(f'10 slides + script, SHA-256 {hashlib.sha256(target.read_bytes()).hexdigest()}')
