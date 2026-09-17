/**
 * web/shared/ws.ts — FROZEN [S0]
 *
 * 타입드 WebSocket 래퍼. 하는 일은 셋뿐이다.
 *   1. send 를 JSON 으로 감싼다
 *   2. 수신을 decode() 로 통과시켜 핸들러에 넘긴다
 *   3. WS_PING_MS 주기로 PING_FRAME 을 보낸다
 *
 * ★재시도는 하지 않는다. 재접속 정책은 호출자 소유다 —
 *   리스너는 선형 백오프로 무한 재시도하고(§7-14), 호스트는 새 코드를 뽑아 재시도한다.
 *   래퍼가 몰래 재시도하면 그 두 정책이 래퍼 정책과 겹쳐 이중으로 돈다.
 *
 * ★ping 은 반드시 PING_FRAME 상수를 **그대로** 보낸다. 바이트가 한 글자라도 다르면
 *   Cloudflare 의 setWebSocketAutoResponse 매칭이 빗나가서, keepalive 마다 DO 가 깨어난다.
 *   동작은 멀쩡해 보이고 요금과 지연만 조용히 늘어나는 유형이다.
 */
import { PING_FRAME, WS_PING_MS, decode } from './protocol';
import { ltWarn } from './log';

export interface WsHandlers<M> {
  onMessage(msg: M): void;
  onOpen?(): void;
  /** code 는 close code. 재시도 판단은 호출자 몫이다 — 래퍼는 재시도하지 않는다. */
  onClose?(code: number): void;
}

export interface WsHandle<S> {
  send(msg: S): void;
  close(code?: number): void;
}

export function openWs<S, M>(url: string, h: WsHandlers<M>): WsHandle<S> {
  const ws = new WebSocket(url);
  let pingTimer: ReturnType<typeof setInterval> | null = null;
  let closedCode: number | null = null;

  const stopPing = () => {
    if (pingTimer !== null) {
      clearInterval(pingTimer);
      pingTimer = null;
    }
  };

  ws.addEventListener('open', () => {
    pingTimer = setInterval(() => {
      if (ws.readyState === WebSocket.OPEN) ws.send(PING_FRAME);
    }, WS_PING_MS);
    h.onOpen?.();
  });

  ws.addEventListener('message', (ev: MessageEvent) => {
    const msg = decode(ev.data);
    // decode() 는 JSON 실패와 t 누락에 null 을 준다. 삼키지 말고 찍는다.
    if (msg === null) {
      ltWarn('ws', { url, badFrame: String(ev.data).slice(0, 200) });
      return;
    }
    h.onMessage(msg as M);
  });

  ws.addEventListener('close', (ev: CloseEvent) => {
    stopPing();
    closedCode = ev.code;
    h.onClose?.(ev.code);
  });

  // onerror 는 close 직전에만 오고 이유를 주지 않는다(보안상 브라우저가 감춘다).
  // close 핸들러가 어차피 뒤따르므로 여기선 기록만 하고 상태를 바꾸지 않는다.
  ws.addEventListener('error', () => ltWarn('ws', { url, event: 'error' }));

  return {
    send(msg: S) {
      if (ws.readyState !== WebSocket.OPEN) {
        ltWarn('ws', { url, dropped: msg, readyState: ws.readyState, closedCode });
        return;
      }
      ws.send(JSON.stringify(msg));
    },
    close(code?: number) {
      stopPing();
      if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
        ws.close(code);
      }
    },
  };
}
