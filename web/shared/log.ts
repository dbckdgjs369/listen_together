/**
 * web/shared/log.ts — FROZEN [S0]
 *
 * 콘솔 접두사를 통일하는 것 하나만 한다. 이게 없으면 A와 B가 각자 다른 접두사로 찍고,
 * 시연 중 개발자도구를 열었을 때 어느 쪽 로그인지 눈으로 못 가른다.
 *
 * 원본 README 의 '조용한 실패 금지' 는 웹에서도 그대로다 — 삼키지 말고 찍는다.
 */

/** `[LT host-rtc] { … }` 꼴로 찍는다. */
export function ltLog(tag: string, obj?: unknown): void {
  if (obj === undefined) console.log(`[LT ${tag}]`);
  else console.log(`[LT ${tag}]`, obj);
}

/** 예외·실패 경로 전용. 콘솔 필터로 한 번에 걸러내려고 채널을 나눈다. */
export function ltWarn(tag: string, obj?: unknown): void {
  if (obj === undefined) console.warn(`[LT ${tag}]`);
  else console.warn(`[LT ${tag}]`, obj);
}
