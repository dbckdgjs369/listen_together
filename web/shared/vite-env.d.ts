// web/shared/vite-env.d.ts — FROZEN [S0]
//
// CONTRACT.md §2 트리에는 없던 파일이다. Step 0 에서 필요해져 추가했고, 추가 이유를 남긴다.
//
// `import './host.css'` 같은 사이드이펙트 import 는 TS 가 모듈로 인식하지 못해
// TS2882 로 떨어진다. Vite 가 제공하는 앰비언트 선언(*.css, *.svg, import.meta.env …)을
// 끌어와야 하는데, tsconfig.web.json 의 `types: []` 는 @types 자동 포함만 끊을 뿐
// 아래 삼중슬래시 참조는 막지 않는다 — workers-types 유입은 여전히 차단된 채로
// Vite 타입만 정확히 들어온다.
//
// 이 파일이 shared 에 있는 이유: A 도 B 도 각자 트리에서 css 를 import 하므로
// 한쪽 트리에 두면 나머지 한 사람이 자기 트리에 똑같은 걸 하나 더 만든다.
/// <reference types="vite/client" />
