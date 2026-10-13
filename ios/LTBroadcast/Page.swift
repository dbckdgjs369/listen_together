// 자동 생성 — 고치지 말 것. 원본: app/.../CaptureService.java 의 PAGE. scripts/gen_page.sh
enum Page {
    static let html = #"""
<!doctype html><html lang=ko><head>
<meta charset=utf-8>
<meta name=viewport content='width=device-width,initial-scale=1,viewport-fit=cover'>
<meta name=theme-color content='#07080C'>
<title>이음</title><style>
:root{--bg:#07080C;--surface:#171A22;--stroke:rgba(255,255,255,.08);--text:#F2F4F8;--dim:#9AA3B6;--faint:#5B6376;
--accent:#6C5CE7;--lav:#B7ACFF;--live:#3DDC97;--danger:#FF7A7A}
*{box-sizing:border-box;margin:0;padding:0}
html,body{min-height:100%}
body{background:var(--bg);color:var(--text);font-family:-apple-system,system-ui,'Apple SD Gothic Neo',sans-serif;
-webkit-font-smoothing:antialiased;display:flex;flex-direction:column;min-height:100vh;min-height:100dvh;overflow-x:hidden;
padding:env(safe-area-inset-top) 22px calc(24px + env(safe-area-inset-bottom))}
/* 천천히 흐르는 오로라. 흐림 필터 대신 방사형 그라데이션이라 폰에서도 가볍다 */
.aura{position:fixed;inset:0;z-index:-2;overflow:hidden;background:radial-gradient(120% 70% at 50% -10%,#16163A 0%,var(--bg) 62%)}
.aura i{position:absolute;border-radius:50%;will-change:transform}
.aura .a1{width:90vmax;height:90vmax;left:-40vmax;top:-35vmax;background:radial-gradient(circle,rgba(108,92,231,.55),transparent 62%);animation:d1 19s ease-in-out infinite alternate}
.aura .a2{width:80vmax;height:80vmax;right:-45vmax;top:20vh;background:radial-gradient(circle,rgba(45,108,223,.38),transparent 62%);animation:d2 23s ease-in-out infinite alternate}
.aura .a3{width:70vmax;height:70vmax;left:-10vmax;bottom:-45vmax;background:radial-gradient(circle,rgba(61,220,151,.22),transparent 62%);animation:d3 27s ease-in-out infinite alternate}
@keyframes d1{to{transform:translate(18vw,12vh) scale(1.15)}}
@keyframes d2{to{transform:translate(-16vw,-8vh) scale(1.1)}}
@keyframes d3{to{transform:translate(14vw,-10vh) scale(1.2)}}
/* 필름 입자. 매끈한 그라데이션에 종이 같은 질감을 준다 */
.grain{position:fixed;inset:-50%;z-index:-1;pointer-events:none;opacity:.08;
background-image:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='180' height='180'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='.85' numOctaves='2' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E")}
.top{height:60px;display:flex;align-items:center;flex:none}
.brand{font-size:21px;font-weight:700;letter-spacing:-.02em;display:flex;align-items:center}
/* 音 은 도장처럼 — 이름의 두 번째 뜻을 낙관 찍듯 곁들인다 */
.seal{display:inline-grid;place-items:center;width:22px;height:22px;margin-left:8px;border-radius:6px;
border:1.5px solid rgba(183,172,255,.75);color:var(--lav);font-size:12px;font-weight:700;transform:rotate(-6deg)}
main{flex:1;width:100%;max-width:380px;margin:0 auto;display:flex;flex-direction:column;justify-content:center}
main>*{animation:rise .8s cubic-bezier(.2,.8,.2,1) both}
main>*:nth-child(2){animation-delay:.08s}main>*:nth-child(3){animation-delay:.16s}
main>*:nth-child(4){animation-delay:.24s}main>*:nth-child(5){animation-delay:.32s}main>*:nth-child(6){animation-delay:.4s}
@keyframes rise{from{opacity:0;transform:translateY(14px)}}
.eyebrow{font-size:12.5px;font-weight:600;letter-spacing:.24em;color:var(--lav);opacity:.85}
.glass{background:rgba(23,26,40,.52);-webkit-backdrop-filter:blur(22px) saturate(140%);backdrop-filter:blur(22px) saturate(140%);
border:1px solid var(--stroke);border-radius:26px;box-shadow:0 20px 50px -20px rgba(0,0,0,.6),inset 0 1px 0 rgba(255,255,255,.05)}
.btn{position:relative;overflow:hidden;width:100%;height:56px;border-radius:18px;border:0;font:inherit;font-size:16px;font-weight:700;
letter-spacing:.02em;color:#fff;cursor:pointer;-webkit-tap-highlight-color:transparent;
background:linear-gradient(135deg,#8C7EF7 0%,#6C5CE7 45%,#4636B8 100%);
box-shadow:0 12px 30px -10px rgba(108,92,231,.85),inset 0 1px 0 rgba(255,255,255,.28);transition:transform .15s,filter .15s}
.btn:active{transform:scale(.98);filter:brightness(.92)}
/* 가끔 한 번씩 빛이 스치고 지나간다 */
.btn:after{content:'';position:absolute;top:0;bottom:0;width:40%;left:-60%;
background:linear-gradient(100deg,transparent,rgba(255,255,255,.28),transparent);animation:sheen 4.5s ease-in-out 1.2s infinite}
@keyframes sheen{0%,60%{left:-60%}100%{left:130%}}
.strands{display:block;width:100%;height:64px;overflow:visible}
.strands path{fill:none;stroke-width:2;stroke-linecap:round}
.strands .s1{animation:flow 6s linear infinite}.strands .s2{animation:flow 4.2s linear infinite reverse}
@keyframes flow{to{transform:translateX(120px)}}
@media (prefers-reduced-motion:reduce){*,*:before,*:after{animation:none!important}}
main{text-align:center;align-items:center}
/* 가운데 구슬. 쉴 때는 천천히 숨쉬고, 재생되면 물결이 퍼지고 띠가 돈다 */
.orb{position:relative;width:230px;height:230px;display:grid;place-items:center;flex:none}
.orb .ring{position:absolute;border-radius:50%;border:1px solid rgba(183,172,255,.16)}
.orb .r1{inset:0}.orb .r2{inset:26px;border-color:rgba(183,172,255,.24)}
.orb .wave{position:absolute;inset:50px;border-radius:50%;border:1.5px solid rgba(157,144,255,.6);opacity:0}
.orb .halo{position:absolute;inset:40px;border-radius:50%;opacity:0;transition:opacity .8s;
background:conic-gradient(from 0deg,transparent 0 55%,rgba(61,220,151,.9) 80%,transparent 100%);
-webkit-mask:radial-gradient(circle,transparent 66%,#000 67%);mask:radial-gradient(circle,transparent 66%,#000 67%)}
.core{position:relative;width:130px;height:130px;border-radius:50%;display:grid;place-items:center;
background:radial-gradient(circle at 34% 28%,#A99DFF 0%,#6C5CE7 42%,#35288F 78%,#1C1552 100%);
box-shadow:0 0 70px rgba(108,92,231,.55),0 0 0 1px rgba(255,255,255,.08) inset,inset -12px -16px 30px rgba(0,0,0,.35);
animation:breathe 5s ease-in-out infinite}
@keyframes breathe{50%{transform:scale(1.04);box-shadow:0 0 95px rgba(108,92,231,.7),0 0 0 1px rgba(255,255,255,.08) inset,inset -12px -16px 30px rgba(0,0,0,.35)}}
.eq{display:flex;gap:5px;align-items:center;height:46px}
.eq i{width:5px;height:10px;border-radius:3px;background:rgba(255,255,255,.92);transition:height .4s}
.eq i:nth-child(2){height:18px}.eq i:nth-child(3){height:26px}.eq i:nth-child(4){height:16px}.eq i:nth-child(5){height:9px}
[data-k=on] .eq i{animation:eq 1.1s ease-in-out infinite}
.eq i:nth-child(1){animation-delay:-.2s}.eq i:nth-child(2){animation-delay:-.6s}
.eq i:nth-child(3){animation-delay:-.1s}.eq i:nth-child(4){animation-delay:-.8s}.eq i:nth-child(5){animation-delay:-.4s}
@keyframes eq{0%,100%{height:10px}50%{height:42px}}
[data-k=on] .wave{animation:wave 3s ease-out infinite}
[data-k=on] .w2{animation-delay:1s}[data-k=on] .w3{animation-delay:2s}
@keyframes wave{from{transform:scale(1);opacity:.9}to{transform:scale(1.9);opacity:0}}
[data-k=on] .halo{opacity:1;animation:spin 3.2s linear infinite}
[data-k=wait] .halo{opacity:.6;animation:spin 1.4s linear infinite;filter:hue-rotate(95deg)}
@keyframes spin{to{transform:rotate(1turn)}}
/* 들어온 뒤에는 가운데 구슬이 음소거 버튼이다 */
.want .orb{cursor:pointer;-webkit-tap-highlight-color:transparent}
.want .orb:active .core{transform:scale(.96)}
[data-k=mute] .core{animation:none;filter:saturate(.4) brightness(.75);box-shadow:0 0 30px rgba(108,92,231,.25),inset -12px -16px 30px rgba(0,0,0,.35)}
[data-k=mute] .eq i{height:4px!important;opacity:.7}
[data-k=mute] .dot{display:block;background:var(--faint)}
.eyebrow{margin-top:26px}
h1{font-size:28px;font-weight:800;letter-spacing:-.03em;margin-top:8px;word-break:keep-all;line-height:1.25}
.st{display:inline-flex;gap:8px;align-items:center;justify-content:center;margin-top:10px;font-size:14px;color:var(--dim);min-height:21px;line-height:1.45}
.dot{width:7px;height:7px;border-radius:50%;flex:none;display:none}
[data-k=on] .st{color:var(--live)}[data-k=on] .dot{display:block;background:var(--live);box-shadow:0 0 10px var(--live)}
[data-k=wait] .dot{display:block;background:var(--lav);animation:blink 1.1s infinite}
[data-k=bad] .st{color:var(--danger)}
@keyframes blink{50%{opacity:.25}}
#form{margin-top:26px;width:100%;padding:18px 18px 20px}
.lab{font-size:12.5px;font-weight:600;color:var(--dim);letter-spacing:.04em}
.pin{position:relative;display:flex;gap:10px;justify-content:center;margin-top:12px}
.pin div{width:56px;height:64px;border-radius:16px;background:rgba(7,8,12,.55);border:1.5px solid rgba(255,255,255,.1);
display:grid;place-items:center;font-size:26px;font-weight:700;transition:border-color .2s,box-shadow .2s}
.pin div.on{border-color:var(--accent);box-shadow:0 0 0 4px rgba(108,92,231,.18)}
.pin div.on:empty:after{content:'';width:2px;height:26px;background:var(--lav);animation:blink 1s steps(1) infinite}
[data-k=bad] .pin div{border-color:rgba(255,122,122,.5);animation:shake .35s}
@keyframes shake{25%{transform:translateX(-4px)}75%{transform:translateX(4px)}}
.pin input{position:absolute;inset:0;width:100%;opacity:0;font-size:16px;border:0;background:none;color:transparent}
.btn{margin-top:16px}
.want .lab,.want .pin{display:none}
.want #form{background:none;border-color:transparent;box-shadow:none;-webkit-backdrop-filter:none;backdrop-filter:none;padding:0}
.want:not(.muted) .btn{margin-top:30px;background:rgba(23,26,40,.55);box-shadow:none;border:1px solid var(--stroke);color:var(--dim)}
.want:not(.muted) .btn:after{display:none}
.want.muted .btn{margin-top:30px}
/* 브라우저에 쌓인 소리 양. 따라잡기가 제대로 도는지 눈으로 보려고 둔다 */
.lag{margin-top:4px;font-size:12px;color:var(--faint);min-height:18px;font-variant-numeric:tabular-nums}
.fine{margin-top:16px;font-size:12.5px;color:var(--faint);line-height:1.55}
/* 아이폰에서만 보인다. 사파리보다 앱이 화면을 꺼도 계속 들리고 지연도 짧다 */
.applink{display:none;margin-top:14px;width:100%;padding:14px 16px;border-radius:18px;text-decoration:none;color:var(--text);
text-align:left;font-size:14px;line-height:1.45}
.ios .applink{display:block}
.applink b{display:block;font-size:15px;color:var(--lav)}
.applink span{color:var(--dim);font-size:12.5px}
</style></head><body>
<div class=aura><i class=a1></i><i class=a2></i><i class=a3></i></div><div class=grain></div>
<header class=top><div class=brand>이음<span class=seal aria-hidden=true>音</span></div></header>
<main>
<div class=orb><span class="ring r1"></span><span class="ring r2"></span>
<span class="wave w1"></span><span class="wave w2"></span><span class="wave w3"></span><span class=halo></span>
<div class=core><div class=eq><i></i><i></i><i></i><i></i><i></i></div></div></div>
<div><p class=eyebrow>초대받은 방</p><h1>%ROOM%</h1>
<div class=st><span class=dot></span><span id=s>호스트가 알려준 비밀번호를 입력하세요</span></div>
<div class=lag id=lag></div></div>
<div id=form class=glass><div class=lab>비밀번호 4자리</div>
<div class=pin id=pin><div></div><div></div><div></div><div></div>
<input id=p type=tel inputmode=numeric autocomplete=one-time-code maxlength=4 aria-label='비밀번호 4자리'></div>
<button id=b class=btn>재생</button></div>
<p class=fine id=fine>화면을 꺼도 계속 들려요 · 볼륨은 폰 옆 버튼으로</p>
<a id=app class="applink glass" href=#><b>이음 앱으로 듣기 ›</b><span>앱이 있으면 화면을 꺼도 계속 들리고 지연도 더 짧아요</span></a>
</main>
<audio id=a playsinline></audio>
<script>
var a=document.getElementById('a'),b=document.getElementById('b'),
s=document.getElementById('s'),p=document.getElementById('p'),
boxes=document.getElementById('pin').children,body=document.body;
// k: ''=안내, on=재생 중, mute=소리만 끔, wait=연결·재접속 중, bad=오류. 색·애니메이션이 이 값을 따른다.
function say(m,k){s.textContent=m;body.setAttribute('data-k',k||'');}
// 칸 네 개는 그림일 뿐이고, 실제 입력은 그 위에 투명하게 덮은 input 하나가 받는다.
function draw(){var v=p.value;for(var i=0;i<4;i++){boxes[i].textContent=v[i]||'';
boxes[i].className=(document.activeElement===p&&i===Math.min(v.length,3))?'on':'';}}
p.oninput=function(){p.value=p.value.replace(/\D/g,'').slice(0,4);draw();
if(body.getAttribute('data-k')==='bad')say('호스트가 알려준 비밀번호를 입력하세요');};
p.onfocus=p.onblur=draw;
// 방 코드 페이지(site/)에서 넘어오면 주소에 ?pw= 가 붙어 있다. 채워만 둔다 —
// 재생은 사용자 제스처가 있어야 시작되므로 버튼은 직접 눌러야 한다.
var q=(new URLSearchParams(location.search).get('pw')||'').replace(/\D/g,'');
if(q.length===4){p.value=q;say('재생을 누르세요');}
draw();
// 아이폰·아이패드 사파리는 끝없는 WAV 를 <audio> 로 못 튼다 — 앞 몇 바이트만 떠보고
// 본 요청을 안 보낸 채 포기한다(실측: bytes=0-1, 0-1445 만 반복). 그래서 아이폰에서는
// 소리를 fetch 로 직접 받아 Web Audio 로 이어 붙여 튼다. 덤으로 상태 코드를 바로 알고
// 지연도 0.3초 안팎으로 짧다. 대신 화면을 끄면 사파리가 소리를 멈출 수 있어 앱을 권한다.
var IOS=/iP(hone|ad|od)/.test(navigator.userAgent)||(navigator.platform==='MacIntel'&&navigator.maxTouchPoints>1);
var wa=null,h1=document.querySelector('h1');
if(IOS){body.classList.add('ios');
document.getElementById('fine').textContent='사파리는 화면을 끄면 소리가 멈출 수 있어요 · 앱을 쓰면 계속 들려요';}
document.getElementById('app').onclick=function(){
// 페이지 재생은 먼저 끊는다. 안 그러면 사파리가 백그라운드에서 한동안 스트림을 더 받아
// 호스트엔 청취자가 둘로 보이고 대역폭도 두 배로 쓴다.
if(want){setWant(false);say('이음 앱에서 듣는 중이에요');}
location.href='ieum://join?host='+encodeURIComponent(location.hostname)+'&pw='+encodeURIComponent(p.value||'')
+'&name='+encodeURIComponent(h1.textContent);return false;};
function WA(){var C=window.AudioContext||window.webkitAudioContext,ctx=new C(),g=ctx.createGain(),next=0;
g.connect(ctx.destination);
// 무음 스위치를 켜 둬도 들리게(사파리 17+). 없는 브라우저는 그냥 넘어간다.
try{if(navigator.audioSession)navigator.audioSession.type='playback';}catch(e){}
var w={ctx:ctx,t:0,ctl:null,
lag:function(){return Math.max(0,next-ctx.currentTime);},
mute:function(m){g.gain.value=m?0:1;},
stop:function(){if(w.ctl)w.ctl.abort();w.ctl=null;},
// 응답 상태 코드로 끝나는 약속을 돌려준다. 200 이면 그 뒤로 계속 받으며 튼다.
start:function(url,onend){w.stop();var ctl=new AbortController();w.ctl=ctl;next=0;ctx.resume();
return fetch(url,{cache:'no-store',signal:ctl.signal}).then(function(r){
if(r.status!==200&&r.status!==206){ctl.abort();return r.status;}
var rd=r.body.getReader(),head=new Uint8Array(0),rate=48000,ch=2,left=new Uint8Array(0);
function play(bytes,n){var dv=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength),
buf=ctx.createBuffer(2,n,rate),L=buf.getChannelData(0),R=buf.getChannelData(1),fb=2*ch;
for(var i=0;i<n;i++){L[i]=dv.getInt16(i*fb,true)/32768;R[i]=ch>1?dv.getInt16(i*fb+2,true)/32768:L[i];}
var now=ctx.currentTime;
if(next<now+0.03)next=now+0.25;   // 처음이거나 끊겼다: 0.25초 여유를 두고 다시 잇는다
else if(next>now+1.2)return;      // 너무 쌓였다: 이 조각은 버리고 따라잡는다
var src=ctx.createBufferSource();src.buffer=buf;src.connect(g);src.start(next);next+=n/rate;}
function pump(){return rd.read().then(function(x){if(ctl.signal.aborted)return;
if(x.done){onend();return;}
var d=x.value;w.t++;
if(head.length<44){var need=Math.min(44-head.length,d.length),h=new Uint8Array(head.length+need);
h.set(head);h.set(d.subarray(0,need),head.length);head=h;d=d.subarray(need);
if(head.length===44){var v=new DataView(head.buffer);ch=v.getUint16(22,true)||2;rate=v.getUint32(24,true)||48000;}}
if(d.length){var m=new Uint8Array(left.length+d.length);m.set(left);m.set(d,left.length);left=m;
var fb=2*ch;if(left.length>=Math.round(rate*0.05)*fb){   // 50ms 씩 모아 하나로 튼다
var n=Math.floor(left.length/fb);play(left.subarray(0,n*fb),n);left=left.slice(n*fb);}}
return pump();});}
pump().catch(function(){if(!ctl.signal.aborted)onend();});
return 200;});}};
return w;}
// want: 사용자가 '듣겠다'고 한 상태. 끊겨도 이게 true 면 계속 재시도한다.
var want=false,tries=0,lastT=-1,lastAt=0,timer=null,pw='';
// 호스트가 꺼졌는지 내 Wi-Fi 가 끊겼는지는 둘 다 '연결 실패'라 브라우저로는 못 가른다.
// 호스트가 직접 410 을 주면 확실한 종료다. 그 신호를 놓친 채 LOST_MS 동안 계속 실패하면
// '연결할 수 없어요'로 알리되, 진짜 끊김이었을 수도 있으니 LOST_RETRY 간격으로 계속 붙어 본다.
// 내 기기가 오프라인(navigator.onLine)인 동안은 확실히 내 쪽 문제라 이 시간에 넣지 않는다.
var LOST_MS=30000,LOST_RETRY=15000,failSince=0,lost=false;
// muted: 소리만 끈 상태. 연결은 그대로 두고 계속 받는다 — 다시 켜면 멈춘 자리가 아니라
// 지금 나오는 소리부터 들린다. 끊었다 붙으면 재접속 지연이 생기고 스트림도 새로 받아야 한다.
var muted=false;
var OK=IOS?'재생 중':'재생 중 — 화면을 꺼도 계속 들려요';
function setWant(w){want=w;body.classList.toggle('want',w);if(!w)body.classList.remove('muted');p.disabled=w;b.textContent=w?'연결 중…':'재생';
failSince=0;lost=false;
if(!w){muted=false;a.muted=false;clearTimeout(timer);timer=null;if(wa){wa.stop();wa.mute(false);}
else{a.removeAttribute('src');a.load();}}}
// 붙는 데 성공했거나 호스트가 대답했다 — 호스트는 살아 있다.
function alive(){failSince=0;if(lost){lost=false;b.textContent='연결 중…';}}
// 소리가 나오고 있을 때의 상태 표시. 음소거 중이면 그쪽으로 보여준다.
function live(){body.classList.toggle('muted',muted);b.textContent=muted?'다시 듣기':'소리 끄기';
if(muted)say('소리 꺼짐 — 가운데를 누르면 다시 들려요','mute');else say(OK,'on');
if('mediaSession' in navigator)navigator.mediaSession.playbackState=muted?'paused':'playing';}
function toggleMute(){var k=body.getAttribute('data-k');if(!want||(k!=='on'&&k!=='mute'))return;
muted=!muted;a.muted=muted;if(wa)wa.mute(muted);live();}
function connect(){
if(!lost)say(tries?('재접속 중… ('+tries+'회)'):'연결 중…','wait');
var url='/stream?pw='+encodeURIComponent(pw)+'&t='+Date.now()+'-'+tries;
lastT=-1;lastAt=Date.now();
if(wa){wa.start(url,function(){if(want)fail('스트림 끊김 — 재접속');}).then(function(st){if(!want)return;
if(st===200){tries=0;alive();live();meta();}else if(st===401||st===410||st===429)verdict(st);else fail('연결 실패');
}).catch(function(e){if(e&&e.name==='AbortError')return;unreachable();});return;}
a.src=url;
a.play().then(function(){tries=0;alive();live();meta();
}).catch(function(e){fail('재생 실패: '+e.name);});}
function meta(){
if('mediaSession' in navigator){navigator.mediaSession.metadata=
new MediaMetadata({title:'이음',artist:document.querySelector('h1').textContent});
// 잠금화면·이어폰의 일시정지도 음소거로 받는다. 진짜로 멈추면 감시견이 '멈춤'으로 보고 다시 붙는다.
try{navigator.mediaSession.setActionHandler('pause',function(){if(!muted)toggleMute();});
navigator.mediaSession.setActionHandler('play',function(){if(muted)toggleMute();});}catch(e){}}}
// 왜 끊겼는지 서버에 따로 물어본다. <audio> 는 실패해도 상태 코드를
// 안 주기 때문에, 이게 없으면 비밀번호가 틀린 건지 Wi-Fi 가 끊긴 건지
// 구분을 못 하고 영원히 '재접속 중…' 만 띄운다.
function fail(msg){if(!want)return;
fetch('/check?pw='+encodeURIComponent(pw),{cache:'no-store'}).then(function(r){
if(!want)return;
if(r.status===204){alive();say(msg,'wait');retry();return;}
verdict(r.status);
}).catch(unreachable);}
// 호스트가 들여보내지 않았다 — 끝났거나(410) 비밀번호 문제(401·429).
function verdict(st){
if(st===410){setWant(false);say('호스트가 공유를 종료했어요');return;}
setWant(false);p.value='';draw();
say(st===429?'시도가 너무 많아요 — 잠시 후 다시 해 주세요':'비밀번호가 맞지 않아요','bad');}
// 호스트에 아예 닿지 않음. 누구 탓인지 가를 수 있는 만큼만 가른다.
// '재생 실패: NotSupportedError' 같은 브라우저 사정은 여기선 의미가 없어 재접속 횟수만 보여준다.
function unreachable(){if(!want)return;
if(!navigator.onLine){failSince=0;lost=false;b.textContent='연결 중…';say('Wi-Fi 연결을 확인해 주세요','wait');retry();return;}
var now=Date.now();if(!failSince)failSince=now;
if(now-failSince>=LOST_MS){if(!lost){lost=true;b.textContent='다시 시도';}
say('연결할 수 없어요 — 방이 끝났거나 다른 Wi-Fi 일 수 있어요','bad');}
else{b.textContent='연결 중…';say('재접속 중… ('+(tries+1)+'회)','wait');}
retry();}
// 지수적으로는 안 늘리고 최대 5초에서 멈춘다. 여행 중 재접속은 빠를수록 좋다.
function retry(){if(!want||timer)return;tries++;
timer=setTimeout(function(){timer=null;connect();},lost?LOST_RETRY:Math.min(1000*tries,5000));}
// 바로 다시 붙어 본다. 기다리던 재시도는 버린다.
function retryNow(){clearTimeout(timer);timer=null;tries=0;connect();}
// ★비밀번호를 서버에 먼저 물어보고 나서 재생하면 안 된다. fetch 를 기다리는
//   사이 사용자 제스처가 풀려서 아이폰이 play() 를 거부한다. 그래서 일단
//   재생을 걸고, 실패했을 때만 fail() 이 이유를 따져 묻는다.
b.onclick=function(){if(want){if(lost)retryNow();else toggleMute();return;}
var v=(p.value||'').replace(/\D/g,'');
if(v.length!==4){say('비밀번호 4자리를 입력해 주세요','bad');p.focus();return;}
// 아이폰은 사용자 제스처 안에서 만든 오디오 컨텍스트만 소리를 낸다. 그래서 여기서 만든다.
if(IOS&&!wa){try{wa=WA();}catch(e){wa=null;}}
if(wa)wa.ctx.resume();
pw=v;tries=0;setWant(true);p.blur();connect();};
p.onkeydown=function(e){if(e.key==='Enter')b.click();};
a.onerror=function(){if(want)fail('스트림 끊김 — 재접속');};
// 호스트가 끄면 스트림이 '끝'으로 닫힌다. 그냥 다시 붙지 말고 왜 끝났는지부터 묻는다.
a.onended=function(){if(want)fail('스트림 끊김 — 재접속');};
a.onstalled=function(){if(want)say('버퍼링…','wait');};
a.onplaying=function(){tries=0;alive();live();};
// 가운데 구슬: 듣는 중이면 음소거 켜고 끄기, 아직이면 버튼과 똑같이 재생 시작
document.querySelector('.orb').onclick=function(){b.onclick();};
// 감시견: 이벤트가 아예 안 뜬 채 재생만 멈추는 경우(가장 흔한 실패)를 잡는다.
// currentTime 이 4초간 안 늘면 죽은 것으로 보고 다시 붙는다.
setInterval(function(){if(!want||timer)return;var now=Date.now();
var cur=wa?wa.t:a.currentTime;
if(cur!==lastT){lastT=cur;lastAt=now;return;}
if(now-lastAt>4000){if(!lost)say('멈춤 감지 — 재접속','wait');retry();}},1000);
// 따라잡기: <audio> 는 끊기지 않으려고 몇 초치를 쌓아 두고 튼다. 와이파이가 흔들려
// 한번 밀리면 그 뒤로도 밀린 자리부터 틀어서 지연이 계속 남는다.
// 받아 둔 끝(buffered.end)과 지금 위치의 차이를 보고, 많이 밀렸으면 끝 근처로 건너뛰고
// 조금 밀렸으면 살짝 빨리 틀어 티 안 나게 줄인다. 너무 바짝 붙으면 브라우저가 다시
// 버퍼링하느라 오히려 더 쌓이므로 LAG_KEEP 만큼은 남긴다.
var LAG_JUMP=1.0,LAG_FAST=0.6,LAG_KEEP=0.4,lagEl=document.getElementById('lag');
setInterval(function(){
if(wa){lagEl.textContent=want&&wa.ctl?'버퍼 '+wa.lag().toFixed(1)+'초':'';return;}
if(!want||a.paused||!a.buffered.length){lagEl.textContent='';return;}
var end=a.buffered.end(a.buffered.length-1),lag=end-a.currentTime;
if(lag>LAG_JUMP){a.playbackRate=1;a.currentTime=end-LAG_KEEP;lag=LAG_KEEP;}
else if(lag>LAG_FAST)a.playbackRate=1.05;
else if(lag<LAG_KEEP)a.playbackRate=1;
lagEl.textContent='버퍼 '+lag.toFixed(1)+'초';},1000);
// 화면을 다시 켰을 때 즉시 복구를 시도한다.
// 사파리는 화면이 꺼진 동안 오디오 컨텍스트를 세운다. 돌아오면 쌓인 건 지난 소리라 새로 붙는다.
document.addEventListener('visibilitychange',function(){
if(document.hidden||!want)return;
if(wa){wa.ctx.resume();retryNow();}else if(a.paused)retryNow();});
// Wi-Fi 가 돌아오면 기다리지 않고 바로 붙는다.
window.addEventListener('online',function(){if(want&&(wa||a.paused))retryNow();});
</script></body></html>
"""#
}
