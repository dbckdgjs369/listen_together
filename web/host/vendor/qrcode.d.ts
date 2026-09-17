// web/host/vendor/qrcode.d.ts — FROZEN-vendored [S0]
//
// qrcode.js(업스트림 qrcode-generator 2.0.4 의 dist/qrcode.mjs 원본)에 대한 **최소 타입 선언**.
//
// 업스트림이 함께 배포하는 qrcode.d.ts 를 그대로 쓰지 않는 이유:
// 그쪽은 `declare var qrcode` + `declare module 'qrcode-generator'` 형태라
// npm 패키지로 설치했을 때를 전제한다. 우리는 npm 의존성을 만들지 않고 파일 하나를
// 로컬 ESM 으로 두므로(= 런타임 npm 의존성 0), default export 모양으로 다시 적는다.
//
// 필요한 것만 적는다. 전부 옮기면 업스트림을 올릴 때 diff 가 커지기만 한다.

type TypeNumber = number;
type ErrorCorrectionLevel = 'L' | 'M' | 'Q' | 'H';
type Mode = 'Numeric' | 'Alphanumeric' | 'Byte' | 'Kanji';

export interface QRCode {
  addData(data: string, mode?: Mode): void;
  make(): void;
  getModuleCount(): number;
  isDark(row: number, col: number): boolean;
  /** scalable:true 를 줘야 컨테이너 크기에 맞춰 늘어난다. QR 은 벡터로 그리는 게 항상 낫다. */
  createSvgTag(opts?: { cellSize?: number; margin?: number; scalable?: boolean }): string;
  createDataURL(cellSize?: number, margin?: number): string;
}

export interface QRCodeFactory {
  /** typeNumber 0 = 자동. errorCorrectionLevel 은 'M' 이 기본 무난값. */
  (typeNumber: TypeNumber, errorCorrectionLevel: ErrorCorrectionLevel): QRCode;
  stringToBytes(s: string): number[];
}

declare const qrcode: QRCodeFactory;
export default qrcode;
export const stringToBytes: (s: string) => number[];
