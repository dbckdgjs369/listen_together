/* 자동 생성 — i18n/*.json 을 고치고 `npm run i18n` 을 돌린다. 직접 고치지 않는다. */
var I18N = {
 "en": {
  "app_name": "IEUM",
  "eyebrow": "CONNECTED BY SOUND",
  "h1_line1": "The same sound,",
  "h1_em": "a moment we share",
  "lead": "Enter the room code shown on the host's screen to listen together right here in your browser.",
  "code_placeholder": "Enter room code",
  "code_label": "Room code",
  "join": "Join",
  "help_title": "Can't get in?",
  "help_same_wifi_html": "You need to be on the <b>same Wi-Fi</b> as the host's phone (or on the host's hotspot).",
  "help_public_wifi_html": "<b>Public Wi-Fi in cafés, hotels, and airports</b> blocks devices from reaching each other, so it won't work even on the same network. Ask the host to turn on <b>‘Connect without Wi-Fi’</b> in the app, join that hotspot, and try again.",
  "help_mac_chrome_html": "If Chrome on a Mac says it can't connect, turn on Chrome in <b>System Settings → Privacy &amp; Security → Local Network</b> and restart it. Safari works without any setup.",
  "help_new_code": "The code changes when the host starts sharing again.",
  "bad_code": "That code doesn't look right — please check it again",
  "connecting": "Connecting… ({ip})",
  "came_back": "Didn't connect? Check the tips below"
 },
 "ko": {
  "app_name": "이음",
  "eyebrow": "소리를 잇다",
  "h1_line1": "같은 소리로,",
  "h1_em": "함께 이어지는 순간",
  "lead": "호스트 화면에 뜬 방 코드를 입력하면 이 브라우저에서 바로 같이 들을 수 있어요.",
  "code_placeholder": "방 코드 입력",
  "code_label": "방 코드",
  "join": "입장하기",
  "help_title": "안 들어가져요",
  "help_same_wifi_html": "호스트 폰과 <b>같은 Wi-Fi</b>(또는 호스트 핫스팟)에 붙어 있어야 해요.",
  "help_public_wifi_html": "<b>카페·호텔·공항 같은 공용 Wi-Fi</b>는 기기끼리 연결을 막아서 같은 Wi-Fi 여도 안 돼요. 호스트에게 앱의 <b>‘와이파이 없이 연결’</b>을 켜 달라고 하고, 그 핫스팟에 접속한 뒤 다시 입장하세요.",
  "help_mac_chrome_html": "맥 Chrome 에서 \"연결할 수 없음\"이 뜨면 <b>시스템 설정 → 개인정보 보호 및 보안 → 로컬 네트워크</b>에서 Chrome 을 켜고 다시 시작하세요. Safari 는 설정 없이 돼요.",
  "help_new_code": "호스트가 공유를 다시 시작하면 코드가 바뀌어요.",
  "bad_code": "코드가 맞지 않아요 — 다시 확인해 주세요",
  "connecting": "연결 중… ({ip})",
  "came_back": "연결이 안 됐나요? 아래 안내를 확인해 주세요"
 }
};
/** 브라우저가 원하는 언어 중 있는 첫 번째. 없으면 영어. */
var LANG = (function () {
  var want = navigator.languages || [navigator.language];
  for (var i = 0; i < want.length; i++) {
    var l = String(want[i] || '').toLowerCase().split('-')[0];
    if (I18N[l]) return l;
  }
  return 'en';
})();
/** t('connecting', {ip: '…'}). 개수형은 {n} 으로 고른다. */
function t(key, args) {
  var v = I18N[LANG][key];
  if (v == null) v = I18N.en[key];
  if (typeof v === 'object') {
    var n = args && args.n, q = 'other';
    try { q = new Intl.PluralRules(LANG).select(n); } catch (e) {}
    v = v[q] || v.other;
  }
  return String(v).replace(/\{(\w+)\}/g, function (m, k) { return args && k in args ? args[k] : m; });
}
