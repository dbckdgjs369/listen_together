/**
 * 방 코드 풀기 — app/src/main/java/com/example/lt/RoomCode.java 와 짝이다.
 *
 * ★두 파일의 규칙이 한 글자라도 다르면 코드가 엉뚱한 주소로 풀린다.
 *   고치면 `node scripts/roomcode.test.mjs` 로 양쪽이 같은지 확인한다.
 *
 * 값의 최댓값이 약 4.3e13 이라 Number(2^53) 안에 들어온다. BigInt 가 필요 없다.
 */
(function (root) {
  'use strict';

  var ALPHA = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
  var B172 = 65536;
  var B10 = B172 + 1048576;
  var BANY = B10 + 16777216;
  var END = BANY + 4294967296;
  var PORT = 7980;

  function check(digits) {
    var sum = 0;
    for (var i = 0; i < digits.length; i++) sum += (i + 1) * ALPHA.indexOf(digits[i]);
    return sum % 31;
  }

  /** 사람이 친 그대로를 받아준다. 소문자·공백·하이픈, O/I/L 혼동까지. */
  function normalize(raw) {
    return String(raw || '')
      .toUpperCase()
      .replace(/[\s\-_.]/g, '')
      .replace(/O/g, '0')
      .replace(/[IL]/g, '1');
  }

  function ipFromIndex(idx) {
    var n;
    if (idx < B172) return '192.168.' + Math.floor(idx / 256) + '.' + (idx % 256);
    if (idx < B10) {
      n = idx - B172;
      return '172.' + (16 + Math.floor(n / 65536)) + '.' + (Math.floor(n / 256) % 256) + '.' + (n % 256);
    }
    if (idx < BANY) {
      n = idx - B10;
      return '10.' + Math.floor(n / 65536) + '.' + (Math.floor(n / 256) % 256) + '.' + (n % 256);
    }
    n = idx - BANY;
    return [Math.floor(n / 16777216), Math.floor(n / 65536) % 256, Math.floor(n / 256) % 256, n % 256].join('.');
  }

  /**
   * 코드 → { ip, pw, url }. 틀린 코드면 null.
   * 검사 글자가 안 맞으면 풀지 않는다 — 오타 하나로 없는 주소에 보내면
   * 참여자는 "사이트에 연결할 수 없음" 만 보고 이유를 모른다.
   */
  function decode(raw) {
    var s = normalize(raw);
    if (s.length < 2 || s.length > 12) return null;
    for (var i = 0; i < s.length; i++) if (ALPHA.indexOf(s[i]) < 0) return null;
    var body = s.slice(0, -1);
    if (ALPHA[check(body)] !== s[s.length - 1]) return null;

    var value = 0;
    for (var j = 0; j < body.length; j++) value = value * 32 + ALPHA.indexOf(body[j]);
    var idx = Math.floor(value / 10000);
    if (idx >= END) return null;
    var pw = ('000' + (value % 10000)).slice(-4);
    var ip = ipFromIndex(idx);
    return { ip: ip, pw: pw, url: 'http://' + ip + ':' + PORT + '/?pw=' + pw };
  }

  /** 테스트용. 앱의 RoomCode.encode 와 같은 결과를 내야 한다. */
  function encode(ip, pw) {
    var o = String(ip).split('.').map(Number);
    if (o.length !== 4 || o.some(function (x) { return !(x >= 0 && x <= 255 && x % 1 === 0); })) return null;
    var idx;
    if (o[0] === 192 && o[1] === 168) idx = o[2] * 256 + o[3];
    else if (o[0] === 172 && o[1] >= 16 && o[1] <= 31) idx = B172 + (o[1] - 16) * 65536 + o[2] * 256 + o[3];
    else if (o[0] === 10) idx = B10 + o[1] * 65536 + o[2] * 256 + o[3];
    else idx = BANY + o[0] * 16777216 + o[1] * 65536 + o[2] * 256 + o[3];
    var p = /^\d{1,4}$/.test(pw || '') ? Number(pw) : 0;
    var value = idx * 10000 + p;
    var out = '';
    do {
      out = ALPHA[value % 32] + out;
      value = Math.floor(value / 32);
    } while (value > 0);
    return out + ALPHA[check(out)];
  }

  var api = { decode: decode, encode: encode, normalize: normalize };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.RoomCode = api;
})(this);
