// Usage: NODE_PATH=<bundled packages> node tools/build-flags.cjs <flag-icons source folder>
// Converts the upstream square SVG flags to small, lossless Android resources.
const fs = require('fs');
const path = require('path');
const sharp = require('sharp');
async function main() {
  const source = process.argv[2];
  if (!source) throw new Error('Pass the extracted flag-icons source directory');
  const root = path.resolve(__dirname, '..');
  const output = path.join(root, 'app/src/main/res/drawable-nodpi');
  const codes = fs.readdirSync(path.join(source, 'flags/1x1')).filter(n => /^[a-z]{2}\.svg$/.test(n) && n !== 'un.svg' && n !== 'xx.svg').sort();
  for (const filename of codes) {
    await sharp(path.join(source, 'flags/1x1', filename)).resize(128, 128).webp({ lossless: true }).toFile(path.join(output, `flag_${filename.slice(0, 2)}.webp`));
  }
  const code = `package com.gdhapp.client.ui.assets

import androidx.annotation.DrawableRes
import com.gdhapp.client.R
import java.util.Locale

/** Generated from flag-icons v7.3.2 (MIT). Unknown country codes use the globe. */
object CountryFlags {
    @DrawableRes fun drawable(code: String): Int? = when (code.trim().uppercase(Locale.ROOT)) {
${codes.map(n => `        "${n.slice(0, 2).toUpperCase()}" -> R.drawable.flag_${n.slice(0, 2)}`).join('\n')}
        else -> null
    }
}
`;
  const dest = path.join(root, 'app/src/main/java/com/gdhapp/client/ui/assets');
  fs.mkdirSync(dest, { recursive: true });
  fs.writeFileSync(path.join(dest, 'CountryFlags.kt'), code);
  console.log(`${codes.length} square flags generated at 128x128`);
}
main().catch(error => { console.error(error); process.exitCode = 1; });
