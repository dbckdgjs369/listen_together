import { defineConfig } from 'vite';
import { resolve } from 'node:path';

// 루트 매니페스트 6/6 — 동결. Step 0 이후 아무도 이 파일을 열지 않는다.
//
// 포트 7990 을 쓴다. 7980·7981 은 원본 안드로이드 앱이 HTTP 스트림과 UDP 비콘에 쓰는 번호라
// 실기기를 같은 랜에 붙여놓고 앱과 웹을 동시에 돌리는 순간 충돌한다.
const WEB = resolve(import.meta.dirname, 'web');

export default defineConfig({
  root: WEB,

  // web/ 아래 public 디렉터리를 만들지 않는다. 만들면 "정적 파일을 어디 두나"가
  // A·B 사이의 새 공유 자원이 된다. 자산은 각자 트리 안에서 import 로 참조한다.
  publicDir: false,

  build: {
    outDir: resolve(import.meta.dirname, 'dist'),
    emptyOutDir: true,
    target: 'es2022',
    sourcemap: true,
    rollupOptions: {
      input: {
        app: resolve(WEB, 'index.html'),
        'mock-host': resolve(WEB, 'tools/mock-host/index.html'),
        'mock-listener': resolve(WEB, 'tools/mock-listener/index.html'),
      },
    },
  },

  server: {
    port: 7990,
    strictPort: true,
    host: true,

    // ★ 여기서 확정해 동결한다.
    // 안 적어두면 A 도 B 도 "프록시가 있던가?" 하고 이 파일을 동시에 열고,
    // 각자 다른 target 포트로 고쳐서 머지 충돌까지 확정된다.
    // 8787 은 `wrangler dev` 의 기본 포트다.
    //
    // ws:true 가 핵심이다. 빠뜨리면 /ws 업그레이드가 평범한 GET 으로 프록시돼
    // 시그널링이 조용히 죽는다 — 디버깅 최악의 유형.
    proxy: {
      '/ws': { target: 'http://127.0.0.1:8787', ws: true, changeOrigin: false },
      '/_lobby': { target: 'http://127.0.0.1:8787', changeOrigin: false },
    },
  },
});
