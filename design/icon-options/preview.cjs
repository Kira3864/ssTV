const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require('sharp');

async function main() {
  const options = [
    ['A-bushy-squirrel', 'A', 'SQUIRREL'],
    ['B-pocket-tv', 'B', 'POCKET TV'],
    ['C-acorn', 'C', 'ACORN'],
    ['D-cinema-ticket', 'D', 'CINEMA TICKET'],
  ];
  const layers = [];
  const labels = [];
  for (let i = 0; i < options.length; i++) {
    const [file, letter, name] = options[i];
    const source = await fs.readFile(path.join(__dirname, `${file}.svg`));
    const x = (i % 2) * 540;
    const y = Math.floor(i / 2) * 470 + 64;
    layers.push({ input: await sharp(source).resize(240).png().toBuffer(), left: x + 150, top: y + 20 });
    labels.push(`<text x="${x + 270}" y="${y + 306}" text-anchor="middle" fill="#43382F" font-family="Arial,sans-serif" font-size="23" font-weight="700">${letter} / ${name}</text>`);
    layers.push({ input: await sharp(source).resize(48).png().toBuffer(), left: x + 202, top: y + 337 });
    layers.push({ input: await sharp(source).resize(32).png().toBuffer(), left: x + 278, top: y + 345 });
    await sharp(source).png().toFile(path.join(__dirname, `${file}.png`));
  }
  const board = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="1000"><rect width="1080" height="1000" fill="#FAF7F1"/><text x="40" y="38" fill="#827566" font-family="Arial,sans-serif" font-size="16" letter-spacing="3">SSTV / ICON STUDIES</text><path d="M540 80V940M60 520H1020" stroke="#E7DFD3"/>${labels.join('')}</svg>`);
  await sharp(board).composite(layers).png().toFile(path.join(__dirname, 'comparison.png'));
}
main().catch(error => { console.error(error); process.exitCode = 1; });
