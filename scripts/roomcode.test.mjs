// 방 코드 규칙이 앱(RoomCode.java)과 페이지(site/roomcode.js)에서 똑같은지 확인한다.
//
//   node scripts/roomcode.test.mjs
//
// JDK 가 필요하다(java 단일 파일 실행). 한쪽만 고치면 코드가 엉뚱한 주소로 풀리는데,
// 그 증상은 참여자 쪽에서 "연결할 수 없음" 으로만 보여서 원인을 짚기 어렵다.
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

// roomcode.js 는 브라우저용 IIFE 라 import 가 안 된다. module 을 쥐여주고 돌린다.
const mod = { exports: {} };
new Function('module', readFileSync(join(root, 'site/roomcode.js'), 'utf8'))(mod);
const RC = mod.exports;

const cases = [
  ['192.168.0.2', '0000'], ['192.168.219.110', '6162'], ['192.168.255.255', '9999'],
  ['172.16.0.0', '1234'], ['172.20.10.3', '0042'], ['172.31.255.255', '9999'],
  ['10.0.0.1', '5555'], ['10.13.34.219', '0001'], ['10.255.255.255', '9999'],
  ['100.64.1.2', '8317'], ['255.255.255.255', '9999'], ['192.168.1.1', ''],
];
for (let i = 0; i < 300; i++) {
  const ip = [0, 0, 0, 0].map(() => Math.floor(Math.random() * 256));
  const kind = i % 3;
  if (kind === 0) { ip[0] = 192; ip[1] = 168; }
  if (kind === 1) { ip[0] = 172; ip[1] = 16 + (ip[1] % 16); }
  if (kind === 2) { ip[0] = 10; }
  cases.push([ip.join('.'), String(Math.floor(Math.random() * 10000)).padStart(4, '0')]);
}

// RoomCode.java 를 그대로 가져와 하네스를 붙여 돌린다. 규칙을 옮겨 적지 않는다.
const src = readFileSync(join(root, 'app/src/main/java/com/example/lt/RoomCode.java'), 'utf8')
  .replace(/^package .*;$/m, '')
  .replace('public final class RoomCode', 'final class RoomCode');
const harness = `public class Harness {
  public static void main(String[] a) throws Exception {
    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
    String line;
    while ((line = r.readLine()) != null) {
      String[] f = line.split(" ", -1);
      if (f[0].equals("D")) {
        String[] d = RoomCode.decode(line.substring(2));
        System.out.println(d == null ? "null" : d[0] + " " + d[1]);
      } else {
        System.out.println(RoomCode.encode(f[0], f[1]));
      }
    }
  }
}
${src}`;
const dir = mkdtempSync(join(tmpdir(), 'roomcode-'));
const runJava = (lines) => execFileSync('java', [join(dir, 'Harness.java')], {
  input: lines.join('\n') + '\n',
  encoding: 'utf8',
}).trim().split('\n');
let javaOut, javaDec;
// 앱의 decode 도 페이지와 같게 풀어야 한다. 사람이 친 모양(소문자·하이픈·O/I/L)과 못 읽는 입력까지.
const decInputs = [];
try {
  writeFileSync(join(dir, 'Harness.java'), harness);
  javaOut = runJava(cases.map(([ip, pw]) => `${ip} ${pw}`));
  javaOut.forEach((c) => decInputs.push(c, c.toLowerCase().replace(/^(...)/, '$1-')));
  decInputs.push('ZZZZZZZZZZZZ', 'GQQ4?J', 'gqq-47j', 'GQQ4 7J', 'OIL');
  javaDec = runJava(decInputs.map((c) => `D ${c}`));
} finally {
  rmSync(dir, { recursive: true, force: true });
}
decInputs.forEach((c, i) => {
  const d = RC.decode(c);
  assert.equal(javaDec[i], d ? `${d.ip} ${d.pw}` : 'null', `앱 decode 불일치 ${c}`);
});

let maxLen192 = 0;
cases.forEach(([ip, pw], i) => {
  const code = javaOut[i];
  assert.equal(RC.encode(ip, pw), code, `인코딩 불일치 ${ip}/${pw}`);
  const d = RC.decode(code);
  assert.ok(d, `풀기 실패 ${code}`);
  assert.equal(d.ip, ip);
  assert.equal(d.pw, /^\d{1,4}$/.test(pw) ? pw.padStart(4, '0') : '0000');
  // 사람이 치는 모양 그대로도 풀려야 한다.
  assert.deepEqual(RC.decode(code.toLowerCase().replace(/^(...)/, '$1-')), d);
  if (ip.startsWith('192.168.')) maxLen192 = Math.max(maxLen192, code.length);
});

assert.equal(RC.decode('ZZZZZZZZZZZZ'), null);
assert.equal(RC.decode('GQQ4?J'), null);
assert.equal(RC.decode(''), null);

console.log(`OK  ${cases.length}건 일치 · 192.168 코드 최대 ${maxLen192}자`);
