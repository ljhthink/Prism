// 把 QQ 的 package.json 的 main 指向 NapCat 的 loadNapCat.js
// 用法：node /data/local/tmp/patch-main.mjs <target.json> [entry]
import fs from 'fs';

const target = process.argv[2];
const entry = process.argv[3] || '/root/NapCat/loadNapCat.js';
if (!target) {
  console.error('usage: node patch-main.mjs <target.json> [entry]');
  process.exit(2);
}
const raw = fs.readFileSync(target, 'utf8');
const json = JSON.parse(raw);
if (!fs.existsSync(target + '.orig')) {
  fs.writeFileSync(target + '.orig', raw);
}
json.main = entry;
fs.writeFileSync(target, JSON.stringify(json, null, 2));
console.log('patched main ->', entry);
console.log('verify:', JSON.parse(fs.readFileSync(target, 'utf8')).main);
