// Run with Node.js and sharp available in NODE_PATH.
const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require('sharp');

async function main() {
  const res = path.resolve(__dirname, '../../app/src/main/res');
  const source = await fs.readFile(path.join(__dirname, 'A-bushy-squirrel.svg'), 'utf8');
  const foreground = source.replace(/<rect\b[^>]*\/>/, '');
  const shapes = [...foreground.matchAll(/<(path|circle)\b([^>]+)\/>/g)].map(([, tag, attributes]) => {
    const attrs = Object.fromEntries([...attributes.matchAll(/([\w-]+)="([^"]*)"/g)].map(m => [m[1], m[2]]));
    if (tag === 'circle') {
      const { cx, cy, r } = attrs;
      return `<path android:fillColor="${attrs.fill}" android:pathData="M${+cx - +r},${cy} a${r},${r} 0,1 0,${+r * 2},0 a${r},${r} 0,1 0,-${+r * 2},0" />`;
    }
    const values = Object.entries(attrs).map(([key, value]) => {
      const name = {d:'pathData',fill:'fillColor',stroke:'strokeColor','stroke-width':'strokeWidth','stroke-linecap':'strokeLineCap','stroke-linejoin':'strokeLineJoin'}[key];
      if (!name) throw new Error(`Unsupported SVG attribute ${key}`);
      return `android:${name}="${value === 'none' ? '#00000000' : value}"`;
    });
    return `<path ${values.join(' ')} />`;
  });
  const vector = content => `<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="512" android:viewportHeight="512">\n  <group android:pivotX="256" android:pivotY="256" android:scaleX="0.78" android:scaleY="0.78">\n    ${content.join('\n    ')}\n  </group>\n</vector>\n`;
  await fs.writeFile(path.join(res, 'drawable/ic_launcher_foreground.xml'), vector(shapes));
  await fs.writeFile(path.join(res, 'drawable/ic_launcher_monochrome.xml'), vector(shapes.slice(0,2).map(shape => shape.replace(/android:fillColor="[^"]*"/, 'android:fillColor="#FFFFFF"'))));
  for (const [density, size] of Object.entries({mdpi:48,hdpi:72,xhdpi:96,xxhdpi:144,xxxhdpi:192})) {
    await sharp(Buffer.from(source)).resize(size).png().toFile(path.join(res, `mipmap-${density}/ic_launcher.png`));
    const circular = source.replace('rx="112"', 'rx="256"');
    await sharp(Buffer.from(circular)).resize(size).png().toFile(path.join(res, `mipmap-${density}/ic_launcher_round.png`));
  }
  const adaptiveSource = foreground.replace('viewBox="0 0 512 512"', 'viewBox="85.333 85.333 341.334 341.334"')
    .replace(/(<svg[^>]*>)/, '$1<rect width="512" height="512" fill="#A45138"/><g transform="translate(56.32 56.32) scale(.78)">').replace('</svg>', '</g></svg>');
  const layers = [];
  for (const [i, mask] of ['<circle cx="128" cy="128" r="128" fill="white"/>', '<rect width="256" height="256" rx="62" fill="white"/>'].entries()) {
    const input = await sharp(Buffer.from(adaptiveSource)).resize(256).png().toBuffer();
    const clipped = await sharp(input).composite([{input:Buffer.from(`<svg width="256" height="256">${mask}</svg>`),blend:'dest-in'}]).png().toBuffer();
    layers.push({input:clipped,left:48+i*320,top:32});
  }
  await sharp({create:{width:672,height:320,channels:4,background:'#FAF7F1'}}).composite(layers).png().toFile(path.join(__dirname,'launcher-masks.png'));
}
main().catch(error => { console.error(error); process.exitCode=1; });
