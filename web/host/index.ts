// web/host/index.ts — [S0 스텁] 시그니처 FROZEN / 본문 A 소유
import './host.css';
import type { MountContext, MountHandle } from '../shared/mount';

/** panel 모드에서만 호출된다(deeplink 모드에서는 호출되지 않음). */
export function mount(el: HTMLElement, ctx: MountContext): MountHandle {
  el.textContent = '내가 틀기 — 준비 중'; // TODO(A): host-ui
  ctx.setRoomLine(null);
  return {
    destroy() {
      el.replaceChildren();
    },
  };
}
