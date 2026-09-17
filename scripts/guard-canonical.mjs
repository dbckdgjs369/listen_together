#!/usr/bin/env node
// canonical 슬롯(lt-web) 오배포 가드.
//
// 개발 중엔 A 가 deploy:a, B 가 deploy:b 만 쓴다. canonical 은 Sync 시점에 한 사람이
// main 에서만 올린다. 그 규칙을 사람 기억에 맡기면 반드시 한 번은 어긴다 —
// 게다가 어긴 걸 알아채는 시점이 "시연 5분 전 링크가 이상하다" 라서 최악이다.
//
// 세 가지를 검사하고 하나라도 어긋나면 exit 1.
//   1. 현재 브랜치가 main 인가
//   2. 워킹트리가 깨끗한가 (커밋 안 된 상대 작업물이 섞여 올라가는 걸 막는다)
//   3. 터미널에 CANONICAL 을 직접 타이핑했는가
import { execFileSync } from 'node:child_process';
import { createInterface } from 'node:readline/promises';
import { stdin, stdout } from 'node:process';

const git = (...args) => execFileSync('git', args, { encoding: 'utf8' }).trim();
const fail = (msg) => {
  console.error(`\n  ✗ canonical 배포 중단 — ${msg}\n`);
  console.error('    개발용 슬롯을 쓰세요:  npm run deploy:a   /   npm run deploy:b\n');
  process.exit(1);
};

const branch = git('rev-parse', '--abbrev-ref', 'HEAD');
if (branch !== 'main') fail(`현재 브랜치가 ${branch} 입니다. canonical 은 main 에서만 올립니다.`);

const dirty = git('status', '--porcelain');
if (dirty) {
  console.error('\n  커밋되지 않은 변경:');
  for (const line of dirty.split('\n')) console.error(`    ${line}`);
  fail('워킹트리가 깨끗하지 않습니다.');
}

// 비대화형(CI·파이프)에서는 통과시키지 않는다. 사람이 직접 쳐야 한다.
if (!stdin.isTTY) fail('대화형 터미널이 아닙니다. 확인 입력을 받을 수 없습니다.');

const rl = createInterface({ input: stdin, output: stdout });
const answer = await rl.question(
  `\n  canonical(lt-web) 에 배포합니다. 되돌리려면 재배포해야 합니다.\n  계속하려면 CANONICAL 을 입력하세요: `,
);
rl.close();
if (answer.trim() !== 'CANONICAL') fail('확인 입력이 일치하지 않습니다.');

console.log(`\n  ✓ 가드 통과 (branch=main, 워킹트리 깨끗)\n`);
