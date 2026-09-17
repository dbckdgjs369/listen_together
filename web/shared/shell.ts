/**
 * web/shared/shell.ts — FROZEN [S0]
 *
 * 셸 DOM 을 만들고 A·B 를 마운트한다. Step 0 이후 아무도 이 파일을 열지 않는다.
 *
 * 하는 일
 *   1. '🎧 같이 듣기' 제목 + status 2줄 + 배너 슬롯 2개 + #h-root/#l-root + 빈 #lt-deeplink
 *   2. location.hash 가 유효 방코드면 deeplink 모드(listener 만), 아니면 panel 모드(둘 다)
 *   3. status 1줄째의 **owner별 슬롯 합성** — 채워진 것만 ' · ' 로 이어 붙이고, 둘 다 비면 '대기 중'
 *   4. setRoomLine 이 쓴 코드를 selfRoom 으로 중계
 *   5. deviceName 보관(localStorage['lt.shared.deviceName']) + 없으면 추정명
 */
import { isWellFormedCode, normalizeCode } from './protocol';
import { ROOM_LINE_EMPTY, STATUS, TITLE, roomLineText } from './strings';
import type { MountContext, MountFn, MountHandle, StatusOwner } from './mount';
import { ltLog } from './log';

export interface ShellDeps {
  mountHost: MountFn;
  mountListener: MountFn;
}

const DEVICE_NAME_KEY = 'lt.shared.deviceName';

/* ──────────────────────────────────────────────────────────────────────
 * 기기 이름 추정
 *
 * 웹에는 Build.MODEL 등가물이 없다. iOS Safari·데스크탑은 모델명을 절대 주지 않는다.
 * 그대로 두면 호스트 목록이 '  님의 소리' 가 되고 호스트 두 명을 구분할 수 없다.
 * ──────────────────────────────────────────────────────────────────── */

interface UADataLike {
  platform?: string;
  mobile?: boolean;
  getHighEntropyValues?(hints: string[]): Promise<{ model?: string }>;
}

function uaData(): UADataLike | undefined {
  return (navigator as Navigator & { userAgentData?: UADataLike }).userAgentData;
}

/** 동기 폴백. UA-CH 가 없거나 늦게 오는 동안 쓸 이름. */
function guessDeviceName(): string {
  const d = uaData();
  const ua = navigator.userAgent;
  const platform = d?.platform ?? '';

  if (/iPhone/i.test(ua) || platform === 'iOS') return 'iPhone';
  if (/iPad/i.test(ua)) return 'iPad';
  if (/Android/i.test(ua) || platform === 'Android') return 'Android 폰';
  if (d?.mobile === true) return '폰';
  return '노트북';
}

/* ──────────────────────────────────────────────────────────────────────
 * 아주 작은 구독 셀. 외부 상태 라이브러리를 들이지 않으려고 직접 둔다.
 * ──────────────────────────────────────────────────────────────────── */
function cell<T>(initial: T) {
  let value = initial;
  const subs = new Set<(v: T) => void>();
  return {
    get: () => value,
    set(next: T) {
      if (Object.is(next, value)) return; // 같은 값으로 구독자를 깨우지 않는다
      value = next;
      for (const fn of subs) fn(value);
    },
    subscribe(fn: (v: T) => void) {
      subs.add(fn);
      return () => {
        subs.delete(fn);
      };
    },
  };
}

function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  props: { id?: string; className?: string; text?: string } = {},
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (props.id) node.id = props.id;
  if (props.className) node.className = props.className;
  if (props.text !== undefined) node.textContent = props.text;
  return node;
}

export function bootShell(deps: ShellDeps): void {
  document.title = TITLE.document;

  /* ── 셸 DOM ──────────────────────────────────────────────────────── */
  const root = el('div', { id: 'lt-shell', className: 'lt-shell' });

  root.append(el('h1', { className: 'lt-title', text: TITLE.app }));

  const statusLine = el('div', { id: 'lt-status', className: 'lt-status' });
  const roomLine = el('div', { id: 'lt-roomline', className: 'lt-roomline' });
  root.append(statusLine, roomLine);

  const bannerHost = el('div', { id: 'lt-banner-host', className: 'lt-banner' });
  const bannerListener = el('div', { id: 'lt-banner-listener', className: 'lt-banner' });
  root.append(bannerHost, bannerListener);

  const hRoot = el('section', { id: 'h-root', className: 'lt-section' });
  const lRoot = el('section', { id: 'l-root', className: 'lt-section' });
  root.append(hRoot, lRoot);

  // 딥링크 컨테이너는 **빈 div 와 position/display 만** shared 가 준다.
  // 배경색조차 B 소유다(§2). 여기에 스타일을 한 줄이라도 더 넣으면 경계가 무너진다.
  const deeplink = el('div', { id: 'lt-deeplink' });

  document.body.append(root, deeplink);

  /* ── status 1줄째: owner별 슬롯 ──────────────────────────────────── */
  // ★마지막 호출자 승리가 아니다. 두 슬롯을 항상 같이 그린다.
  const slots: Record<StatusOwner, string | null> = { host: null, listener: null };

  function renderStatus(): void {
    const parts = [slots.host, slots.listener].filter(
      (s): s is string => typeof s === 'string' && s.length > 0,
    );
    statusLine.textContent = parts.length ? parts.join(' · ') : STATUS.idle;
  }
  renderStatus();

  /* ── status 2줄째 + selfRoom 중계 ───────────────────────────────── */
  const selfRoomCell = cell<string | null>(null);

  function renderRoomLine(room: { code: string; joinUrl: string } | null): void {
    roomLine.textContent = room ? roomLineText(room.code, room.joinUrl) : ROOM_LINE_EMPTY;
    // 호스트는 어차피 setRoomLine 을 부른다. 그 값을 그대로 중계하면
    // B가 A의 localStorage 를 훔쳐보지 않고도 자기 방을 목록에서 거를 수 있다.
    selfRoomCell.set(room ? room.code : null);
  }
  renderRoomLine(null);

  /* ── 배너 ───────────────────────────────────────────────────────── */
  function renderBanner(owner: StatusOwner, text: string | null): void {
    const node = owner === 'host' ? bannerHost : bannerListener;
    node.textContent = text ?? '';
    node.toggleAttribute('data-active', !!text);
  }

  /* ── 기기 이름 ──────────────────────────────────────────────────── */
  const stored = localStorage.getItem(DEVICE_NAME_KEY);
  const deviceNameCell = cell<string>(stored && stored.trim() ? stored : guessDeviceName());

  // 사용자가 직접 지은 이름이 있으면 UA-CH 결과로 덮어쓰지 않는다.
  if (!stored) {
    const d = uaData();
    d?.getHighEntropyValues?.(['model'])
      .then((v) => {
        const model = v.model?.trim();
        if (model && !localStorage.getItem(DEVICE_NAME_KEY)) deviceNameCell.set(model);
      })
      .catch(() => {
        /* UA-CH 미지원. 추정명을 그대로 쓴다 — 실패가 아니라 정상 경로다. */
      });
  }

  /* ── 정리 훅 ────────────────────────────────────────────────────── */
  const teardowns: Array<() => void> = [];
  const handles: MountHandle[] = [];

  function runTeardown(): void {
    for (const fn of teardowns.splice(0)) {
      try {
        fn();
      } catch (e) {
        // 정리 중 예외가 뒤의 정리를 막지 않게 한다. 삼키되 찍는다.
        ltLog('shell/teardown', e);
      }
    }
    for (const h of handles.splice(0)) h.destroy();
  }
  window.addEventListener('pagehide', runTeardown);

  /* ── 모드 결정 ──────────────────────────────────────────────────── */
  const initialRoomCode = normalizeCode(location.hash);
  const isDeeplink = isWellFormedCode(initialRoomCode);

  function makeCtx(mode: 'panel' | 'deeplink'): MountContext {
    return {
      mode,
      initialRoomCode,
      origin: location.origin,
      setStatus(owner, text) {
        slots[owner] = text;
        renderStatus();
      },
      setRoomLine: renderRoomLine,
      selfRoom: { get: selfRoomCell.get, subscribe: selfRoomCell.subscribe },
      deviceName: {
        get: deviceNameCell.get,
        set(name: string) {
          const v = name.trim();
          if (!v) return;
          localStorage.setItem(DEVICE_NAME_KEY, v);
          deviceNameCell.set(v);
        },
        subscribe: deviceNameCell.subscribe,
      },
      setBanner: renderBanner,
      wsUrl(path: string) {
        const u = new URL(path, location.origin);
        u.protocol = u.protocol === 'https:' ? 'wss:' : 'ws:';
        return u.toString();
      },
      onTeardown(fn) {
        teardowns.push(fn);
      },
    };
  }

  ltLog('shell', { mode: isDeeplink ? 'deeplink' : 'panel', initialRoomCode });

  if (isDeeplink) {
    // 딥링크는 **참여 전용 전체화면**이다. 호스트 섹션은 마운트조차 하지 않는다.
    root.setAttribute('data-hidden', '');
    deeplink.setAttribute('data-active', '');
    handles.push(deps.mountListener(deeplink, makeCtx('deeplink')));
  } else {
    handles.push(deps.mountHost(hRoot, makeCtx('panel')));
    handles.push(deps.mountListener(lRoot, makeCtx('panel')));
  }
}
