/** Render the app's 10-slide welcome deck with @oai/artifact-tool.
 * Usage: node scripts/create_welcome.mjs [preview-directory]
 * Requires @oai/artifact-tool and an installed Japanese font (Yu Gothic).
 * The app build uses committed PNG assets and does not require Node.js.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Presentation } from '@oai/artifact-tool';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const content = JSON.parse(await fs.readFile(path.join(root, 'samples/welcome/content.json'), 'utf8'));
const out = path.resolve(process.argv[2] || path.join(root, 'samples/welcome/slides'));
await fs.mkdir(out, { recursive: true });
const deck = Presentation.create({ slideSize: { width: 1280, height: 720 } });
function text(slide, value, x, y, width, height, size, color, bold = false) {
  const box = slide.shapes.add({ geometry: 'textbox', position: { left: x, top: y, width, height },
    fill: 'none', line: { fill: 'none', width: 0 } });
  box.text = value;
  box.text.style = { fontFamily: 'Yu Gothic', fontSize: size, color, bold };
}
for (const [i, item] of content.slides.entries()) {
  const slide = deck.slides.add();
  const dark = i === 0 || i === 9;
  slide.background.fill = dark ? '#142D4B' : '#F7F9FC';
  const ink = dark ? '#FFFFFF' : '#142D4B';
  const secondary = dark ? '#C1DAF0' : '#475D72';
  text(slide, `${String(i + 1).padStart(2, '0')} / 10`, 72, 42, 180, 44, 25, secondary);
  text(slide, item.title, 72, 125, 1140, 95, i === 9 ? 47 : 60, ink, true);
  for (const [j, line] of item.lines.entries()) {
    text(slide, line, 72, 290 + j * 92, 1140, 75, 38, j === 0 ? ink : secondary, j === 0);
  }
  if (item.note) text(slide, item.note, 72, 610, 1140, 58, 27, secondary);
  const png = await deck.export({ slide, format: 'png', scale: 1 });
  await fs.writeFile(path.join(out, `${String(i + 1).padStart(3, '0')}.png`), new Uint8Array(await png.arrayBuffer()));
}
const script = `time(${content.duration})\n\n` + content.slides.map((s, i) => `#${i + 1}\n${s.script}\n`).join('\n');
await fs.writeFile(path.join(root, 'samples/welcome/script.txt'), script, 'utf8');
console.log(`Rendered ${content.slides.length} slides to ${out}`);
