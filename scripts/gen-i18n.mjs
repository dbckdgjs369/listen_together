/**
 * 화면 문구 생성기. 원본은 i18n/<언어>.json 하나뿐이고, 플랫폼별 파일은 전부 여기서 만든다.
 *
 *   node scripts/gen-i18n.mjs   (= npm run i18n)
 *
 * 만든 파일은 git 에 같이 올린다. 그래야 안드로이드·iOS 빌드에 Node 가 필요 없다.
 * 만든 파일을 손으로 고치면 다음 생성 때 사라진다 — 고칠 곳은 언제나 i18n/*.json 이다.
 *
 * ── 원본 규칙 ───────────────────────────────────────────
 * · en.json 이 기준이다. 키 목록·빈칸 이름은 en 을 따르고, 다른 언어에 없는 키는 영어로 나간다.
 *   기본 언어를 영어로 두는 이유: 번역이 없는 언어(일본어 등) 사용자에게 한글보다 영어가 낫다.
 * · 키 앞머리가 어디로 나갈지를 정한다.
 *     common.*  모든 곳          android.*  안드로이드 앱      ios.*  아이폰 앱·방송 확장
 *     iosplist.app.* / iosplist.ext.*  아이폰 앱 / 방송 확장의 Info.plist 값(홈 화면 이름, 권한 안내)
 *     page.*    호스트가 띄우는 청취 웹페이지(안드로이드·아이폰이 직접 서빙)
 *     site.*    방 코드 입력 페이지(site/, GitHub Pages)
 * · 빈칸은 {이름}. 안드로이드로 나갈 땐 en 에 처음 나온 순서대로 %1$s, %2$s … 가 된다 —
 *   코드에서 getString(id, …) 에 넘기는 순서도 그 순서다. 생성된 strings.xml 주석에 적어 둔다.
 *   iOS 는 같은 순서로 %1$@, %2$@ … 가 되고, 생성된 L.swift 의 함수 인자 이름이 빈칸 이름이다.
 * · 개수에 따라 말이 바뀌면 {"one": "...", "other": "..."} 로 적는다. 빈칸 {n} 이 개수다.
 *   (안드로이드 전용. iOS·웹은 "Listeners: {n}" 처럼 개수형이 필요 없는 문장으로 쓴다.)
 * · 키 끝이 _html 이면 웹에서 innerHTML 로 넣는 문구다(<b> 정도만 쓴다).
 */
import { readFileSync, writeFileSync, mkdirSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SRC = join(ROOT, 'i18n');
const BASE = 'en';
const HEADER = '자동 생성 — i18n/*.json 을 고치고 `npm run i18n` 을 돌린다. 직접 고치지 않는다.';

// ── 읽기·검사 ─────────────────────────────────────────────

const langs = readdirSync(SRC).filter(f => f.endsWith('.json')).map(f => f.slice(0, -5)).sort();
if (!langs.includes(BASE)) fail(`i18n/${BASE}.json 이 없다`);
// 기준 언어를 맨 앞에. 출력 순서가 매번 같아야 diff 가 조용하다.
langs.splice(langs.indexOf(BASE), 1);
langs.unshift(BASE);

const dict = Object.fromEntries(langs.map(l => [l, JSON.parse(readFileSync(join(SRC, `${l}.json`), 'utf8'))]));
const base = dict[BASE];

function fail(msg) {
  console.error(`i18n: ${msg}`);
  process.exit(1);
}

/** 문구(또는 복수형 묶음)에 나오는 빈칸 이름을 처음 나온 순서대로. */
function slots(v) {
  const out = [];
  for (const s of typeof v === 'string' ? [v] : Object.values(v)) {
    for (const m of s.matchAll(/\{(\w+)\}/g)) if (!out.includes(m[1])) out.push(m[1]);
  }
  return out;
}

for (const [key, v] of Object.entries(base)) {
  if (!/^(common|android|ios|page|site)\.[a-z0-9_.]+$/.test(key) && !/^iosplist\.(app|ext)\.[a-z_]+$/.test(key)) {
    fail(`키 이름이 규칙에 안 맞는다: ${key}`);
  }
  if (typeof v !== 'string' && typeof v?.other !== 'string') fail(`${key}: 복수형에는 other 가 있어야 한다`);
  if (typeof v !== 'string' && !key.startsWith('android.')) fail(`${key}: 복수형은 android.* 에만 쓴다`);
}
let missing = 0;
for (const l of langs.slice(1)) {
  for (const [key, v] of Object.entries(dict[l])) {
    if (!(key in base)) fail(`${l}.json 의 ${key} 가 ${BASE}.json 에 없다`);
    if (typeof v !== typeof base[key]) fail(`${l}.json 의 ${key}: ${BASE} 와 모양이 다르다(문구/복수형)`);
    if (typeof v !== 'string' && typeof v.other !== 'string') fail(`${l}.json 의 ${key}: other 가 없다`);
    const a = slots(base[key]).sort().join(), b = slots(v).sort().join();
    if (a !== b) fail(`${l}.json 의 ${key}: 빈칸이 다르다 {${b}} ≠ ${BASE} {${a}}`);
  }
  for (const key of Object.keys(base)) {
    if (!(key in dict[l])) { console.warn(`i18n: ${l} 번역 없음 → 영어로 나감: ${key}`); missing++; }
  }
}

/** 이 출력에 들어갈 키만, 앞머리(common./android. 등)를 떼고 점을 _ 로 바꾼 이름으로. */
function pick(lang, spaces) {
  const src = { ...base, ...dict[lang] };   // 번역이 없으면 영어
  const out = {};
  for (const [key, v] of Object.entries(src)) {
    const ns = key.slice(0, key.indexOf('.'));
    if (!spaces.includes(ns)) continue;
    const name = key.slice(ns.length + 1).replace(/\./g, '_');
    if (name in out) fail(`${spaces.join('+')} 에서 이름이 겹친다: ${name}`);
    out[name] = v;
  }
  return out;
}

function write(rel, text) {
  const path = join(ROOT, rel);
  mkdirSync(dirname(path), { recursive: true });
  const old = existsSync(path) ? readFileSync(path, 'utf8') : null;
  if (old !== text) { writeFileSync(path, text); console.log(`i18n: ${rel}`); }
}

// ── 안드로이드: res/values*/strings.xml ───────────────────

const RES = 'app/src/main/res';
const ANDROID = ['common', 'android'];

function androidText(s, order) {
  let t = s
    .replace(/\\/g, '\\\\')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/'/g, "\\'").replace(/"/g, '\\"')
    .replace(/\n/g, '\\n');
  if (/^[@?]/.test(t)) t = '\\' + t;
  if (order.length) {
    t = t.replace(/%/g, '%%');
    t = t.replace(/\{(\w+)\}/g, (_, n) => `%${order.indexOf(n) + 1}$s`);
  }
  return t;
}

const baseAndroid = pick(BASE, ANDROID);
for (const lang of langs) {
  const strings = pick(lang, ANDROID);
  const lines = ['<?xml version="1.0" encoding="utf-8"?>', `<!-- ${HEADER} -->`, '<resources>'];
  for (const [name, v] of Object.entries(strings)) {
    // 순서는 언제나 기준 언어 것. 언어마다 어순이 달라도 코드가 넘기는 인자 순서는 하나다.
    const order = slots(baseAndroid[name]);
    if (order.length && lang === BASE) lines.push(`    <!-- ${order.map((n, i) => `%${i + 1}$s={${n}}`).join(' ')} -->`);
    const plain = !order.length && /%/.test(typeof v === 'string' ? v : Object.values(v).join(''));
    const fmt = plain ? ' formatted="false"' : '';
    if (typeof v === 'string') {
      lines.push(`    <string name="${name}"${fmt}>${androidText(v, order)}</string>`);
    } else {
      lines.push(`    <plurals name="${name}"${fmt}>`);
      for (const [q, s] of Object.entries(v)) lines.push(`        <item quantity="${q}">${androidText(s, order)}</item>`);
      lines.push('    </plurals>');
    }
  }
  lines.push('</resources>', '');
  write(`${RES}/${lang === BASE ? 'values' : `values-${lang}`}/strings.xml`, lines.join('\n'));
}

// 안드로이드 13+ 설정 › 앱 › 언어 에서 이 앱만 따로 언어를 고를 수 있게 한다.
write(`${RES}/xml/locales_config.xml`, [
  '<?xml version="1.0" encoding="utf-8"?>',
  `<!-- ${HEADER} -->`,
  '<locale-config xmlns:android="http://schemas.android.com/apk/res/android">',
  ...langs.map(l => `    <locale android:name="${l}" />`),
  '</locale-config>',
  '',
].join('\n'));

// ── 청취 웹페이지: 앱이 요청의 Accept-Language 를 보고 골라 끼운다 ──

for (const lang of langs) {
  write(`app/src/main/assets/page/${lang}.json`, JSON.stringify(pick(lang, ['common', 'page']), null, 1) + '\n');
}

// ── 방 코드 페이지(site/): 브라우저 언어로 고른다 ───────────

const site = Object.fromEntries(langs.map(l => [l, pick(l, ['common', 'site'])]));
write('site/i18n.js', `/* ${HEADER} */
var I18N = ${JSON.stringify(site, null, 1)};
/** 브라우저가 원하는 언어 중 있는 첫 번째. 없으면 영어. */
var LANG = (function () {
  var want = navigator.languages || [navigator.language];
  for (var i = 0; i < want.length; i++) {
    var l = String(want[i] || '').toLowerCase().split('-')[0];
    if (I18N[l]) return l;
  }
  return '${BASE}';
})();
/** t('connecting', {ip: '…'}). 개수형은 {n} 으로 고른다. */
function t(key, args) {
  var v = I18N[LANG][key];
  if (v == null) v = I18N.${BASE}[key];
  if (typeof v === 'object') {
    var n = args && args.n, q = 'other';
    try { q = new Intl.PluralRules(LANG).select(n); } catch (e) {}
    v = v[q] || v.other;
  }
  return String(v).replace(/\\{(\\w+)\\}/g, function (m, k) { return args && k in args ? args[k] : m; });
}
`);

// ── iOS: Localizable.strings + L.swift(타입 있는 접근자) + InfoPlist.strings + 청취 페이지 문구 ──
//
// 문구 파일과 L.swift 는 LTShared 에 둔다. 앱과 방송 확장이 둘 다 이 폴더를 소스로 쓰므로
// 한 벌이 양쪽 번들에 들어간다(project.yml).

const IOS = ['common', 'ios'];
const camel = s => s.replace(/_([a-z0-9])/g, (_, c) => c.toUpperCase());

function stringsText(s, order) {
  let t = s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n');
  if (order.length) {
    t = t.replace(/%/g, '%%');
    t = t.replace(/\{(\w+)\}/g, (_, n) => `%${order.indexOf(n) + 1}$@`);
  }
  return t;
}

const baseIos = pick(BASE, IOS);
for (const lang of langs) {
  const lines = [`/* ${HEADER} */`];
  for (const [name, v] of Object.entries(pick(lang, IOS))) {
    lines.push(`"${name}" = "${stringsText(v, slots(baseIos[name]))}";`);
  }
  write(`ios/LTShared/${lang}.lproj/Localizable.strings`, lines.join('\n') + '\n');
}

const swift = [
  `// ${HEADER}`,
  'import Foundation',
  '',
  '/// 화면 문구. 키가 빠지거나 빈칸이 바뀌면 컴파일에서 걸린다 — 문자열 키를 손으로 치지 않는다.',
  '/// 주석은 영어 원문이다.',
  'enum L {',
];
for (const [name, v] of Object.entries(baseIos)) {
  const order = slots(v);
  swift.push(`    /// ${v.replace(/\n/g, ' ')}`);
  if (!order.length) {
    swift.push(`    static var ${camel(name)}: String { tr("${name}") }`);
  } else {
    const params = order.map(n => `${camel(n)}: Any`).join(', ');
    swift.push(`    static func ${camel(name)}(${params}) -> String { tr("${name}", ${order.map(camel).join(', ')}) }`);
  }
}
swift.push(
  '',
  '    private static func tr(_ key: String, _ args: Any...) -> String {',
  '        let format = NSLocalizedString(key, comment: "")',
  '        return args.isEmpty ? format : String(format: format, arguments: args.map { String(describing: $0) })',
  '    }',
  '}',
  '',
);
write('ios/LTShared/L.swift', swift.join('\n'));

// Info.plist 값은 키 이름이 정해져 있다. 여기 없는 이름은 오타로 본다.
const PLIST = { display_name: 'CFBundleDisplayName', local_network: 'NSLocalNetworkUsageDescription' };
const PLIST_DIR = { app: 'ios/Ieum', ext: 'ios/LTBroadcast' };
for (const lang of langs) {
  const src = { ...base, ...dict[lang] };
  for (const [target, dir] of Object.entries(PLIST_DIR)) {
    const lines = [`/* ${HEADER} */`];
    for (const [key, v] of Object.entries(src)) {
      if (!key.startsWith(`iosplist.${target}.`)) continue;
      const name = key.slice(`iosplist.${target}.`.length);
      if (!PLIST[name]) fail(`${key}: 모르는 Info.plist 항목 (${Object.keys(PLIST).join(', ')} 중 하나)`);
      lines.push(`"${PLIST[name]}" = "${stringsText(v, [])}";`);
    }
    write(`${dir}/${lang}.lproj/InfoPlist.strings`, lines.join('\n') + '\n');
  }
}

// 방송 확장이 서빙하는 청취 페이지 — 안드로이드 assets/page/*.json 과 같은 내용이다.
const pageSwift = [
  `// ${HEADER}`,
  '',
  '/// 청취 페이지 문구(언어별 JSON). Server.swift 가 요청의 Accept-Language 로 고른다.',
  'enum PageText {',
  '    static let json: [String: String] = [',
];
for (const lang of langs) {
  const j = JSON.stringify(pick(lang, ['common', 'page']));
  if (j.includes('"#')) fail(`page.* 문구에 "# 가 있으면 Swift 원시 문자열이 끊긴다 (${lang})`);
  pageSwift.push(`        "${lang}": #"${j}"#,`);
}
pageSwift.push('    ]', '}', '');
write('ios/LTBroadcast/PageText.swift', pageSwift.join('\n'));

console.log(`i18n: ${langs.join(', ')} · 키 ${Object.keys(base).length}개${missing ? ` · 번역 빠짐 ${missing}` : ''}`);
