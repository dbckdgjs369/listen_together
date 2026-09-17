// web/listener/index.ts — [S0 스텁] 시그니처 FROZEN / 본문 B 소유
import './listener.css';
import type { MountContext, MountHandle } from '../shared/mount';

/** panel·deeplink 두 모드 모두 이 함수 하나로 들어온다.
 *  deeplink 모드의 컨테이너는 shared 가 빈 div 만 준다 —
 *  배경색 포함 내부 DOM·스타일 전부 B가 렌더한다. */
export function mount(el: HTMLElement, ctx: MountContext): MountHandle {
  el.textContent = ctx.mode === 'deeplink' ? '같이 듣기 — 준비 중' : '참여 중이 아닙니다.'; // TODO(B)
  return {
    destroy() {
      el.replaceChildren();
    },
  };
}
