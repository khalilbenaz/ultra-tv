// Pages HTML. Règles : tout texte dynamique passe par escapeHtml ; aucun
// attribut on*= ni style="" (la CSP n'autorise que les balises portant le nonce).

import { escapeHtml as e, html } from "./http.js";
import { parsePairQr } from "./qr.js";
import { displayUrl, xtreamCredsOf } from "./store.js";

const CSS = `
@font-face{font-family:Sora;src:url(/assets/sora.woff2) format("woff2");font-weight:100 800;font-display:swap}
@font-face{font-family:Manrope;src:url(/assets/manrope.woff2) format("woff2");font-weight:200 800;font-display:swap}
:root{color-scheme:dark light;--bg:#0A0A0C;--s1:#141418;--s2:#1C1C21;--bd:#26262D;--fg:#F5F5F7;--fg2:#C4C4CC;--mut:#A1A1AA;--acc:#D91E2B;--acc-h:#EB2F3C;--on-acc:#fff;--acc-soft:rgba(217,30,43,.14);--acc-fg:#FF6B74;--dng:#FF6B74;--ok:#4ADE80;--ok-soft:rgba(74,222,128,.12);--ring:#FF8A92;--glow:rgba(217,30,43,.16);--shadow:0 1px 0 rgba(255,255,255,.03) inset,0 8px 24px rgba(0,0,0,.35);--fr:#F5F5F7;--ff:Manrope,system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;--fh:Sora,Manrope,system-ui,sans-serif}
@media (prefers-color-scheme:light){:root:not([data-theme=dark]){--bg:#F6F6F8;--s1:#FFFFFF;--s2:#F0F0F3;--bd:#E1E1E7;--fg:#121216;--fg2:#3A3A44;--mut:#5B5B66;--acc-h:#B9141F;--acc-soft:rgba(217,30,43,.08);--acc-fg:#C4121E;--dng:#B4121D;--ok:#15803D;--ok-soft:rgba(21,128,61,.09);--ring:#D91E2B;--glow:rgba(217,30,43,.07);--shadow:0 1px 2px rgba(18,18,22,.05),0 8px 24px rgba(18,18,22,.06);--fr:#121216}}
:root[data-theme=light]{color-scheme:light;--bg:#F6F6F8;--s1:#FFFFFF;--s2:#F0F0F3;--bd:#E1E1E7;--fg:#121216;--fg2:#3A3A44;--mut:#5B5B66;--acc-h:#B9141F;--acc-soft:rgba(217,30,43,.08);--acc-fg:#C4121E;--dng:#B4121D;--ok:#15803D;--ok-soft:rgba(21,128,61,.09);--ring:#D91E2B;--glow:rgba(217,30,43,.07);--shadow:0 1px 2px rgba(18,18,22,.05),0 8px 24px rgba(18,18,22,.06);--fr:#121216}
:root[data-theme=dark]{color-scheme:dark}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%}
body{margin:0;min-height:100vh;background:var(--bg) radial-gradient(900px 420px at 50% -140px,var(--glow),transparent 70%) no-repeat;color:var(--fg);font:15px/1.55 var(--ff);padding:0 16px 40px;-webkit-font-smoothing:antialiased;overflow-wrap:anywhere}
a{color:var(--acc-fg);text-decoration:none;font-weight:600;border-radius:6px} a:hover{text-decoration:underline}
h1,h2,h3{font-family:var(--fh);margin:0;letter-spacing:-.01em;line-height:1.2}
h1{font-size:26px;font-weight:700} h2{font-size:18px;font-weight:650} h3{font-size:15px;font-weight:600}
p{margin:0}
.sub,.muted{color:var(--mut)} .sub{margin-top:6px} .small{font-size:13px}
:focus-visible{outline:3px solid var(--ring);outline-offset:2px}
.skip{position:absolute;left:-999px;top:8px;background:var(--acc);color:var(--on-acc);padding:10px 14px;border-radius:10px;z-index:9}
.skip:focus{left:16px}
/* Marque */
.brand{white-space:nowrap;flex:none;display:inline-flex;align-items:center;gap:10px;color:var(--fg);font-family:var(--fh);font-weight:700;font-size:18px}
.logo{width:36px;height:36px;flex:none;display:block}
.logo .lg-bg{fill:#0A0A0C} .logo .lg-fr{stroke:#F5F5F7}
@media (prefers-color-scheme:light){:root:not([data-theme=dark]) .logo .lg-bg{fill:#0A0A0C}}
:root[data-theme=light] .logo .lg-bg{fill:#0A0A0C}
.theme-toggle{position:fixed;right:16px;bottom:16px;z-index:50;display:inline-flex;align-items:center;gap:8px;min-height:40px;padding:0 14px;border-radius:999px;border:1px solid var(--bd);background:var(--s1);color:var(--fg2);font:600 13px var(--ff);cursor:pointer;box-shadow:var(--shadow)}
.theme-toggle:hover{color:var(--fg);border-color:var(--mut)}
.theme-toggle:focus-visible{outline:3px solid var(--ring);outline-offset:2px}
/* Surfaces */
.layout{max-width:1120px;margin:0 auto}
.panel{background:var(--s1);border:1px solid var(--bd);border-radius:20px;padding:20px;margin-bottom:16px;box-shadow:var(--shadow)}
.panel-h{display:flex;align-items:center;gap:10px;margin-bottom:4px;flex-wrap:wrap}
.count{display:inline-flex;align-items:center;justify-content:center;min-width:26px;height:26px;padding:0 8px;border-radius:999px;background:var(--s2);border:1px solid var(--bd);color:var(--fg2);font:600 13px var(--ff)}
.grid{display:grid;gap:16px;align-items:start}
@media (min-width:900px){.grid{grid-template-columns:minmax(0,5fr) minmax(0,7fr)}.grid>.col{min-width:0}}
.col>.panel:last-child{margin-bottom:0}
@media (max-width:899px){.col{display:contents}.grid>.col>.panel{margin:0}.o1{order:1}.o2{order:2}.o3{order:3}.o4{order:4}.o5{order:5}}
.stack{display:grid;gap:12px;margin-top:16px}
/* Formulaires */
label,.lbl{display:block;color:var(--fg2);font-size:13px;font-weight:600;margin:14px 0 6px}
label .hint,.hint{color:var(--mut);font-weight:500}
input,select{width:100%;min-height:48px;background:var(--bg);color:var(--fg);border:1.5px solid var(--bd);border-radius:14px;padding:11px 14px;font:inherit;font-size:16px;transition:border-color .15s,box-shadow .15s}
input::placeholder{color:var(--mut);opacity:.75}
input:hover{border-color:var(--mut)}
input:focus-visible{outline:none;border-color:var(--acc);box-shadow:0 0 0 4px var(--acc-soft),0 0 0 2px var(--acc)}
input[type=checkbox]{appearance:none;width:22px;height:22px;min-height:0;flex:none;margin:0;padding:0;border-radius:7px;display:inline-grid;place-content:center;cursor:pointer;background:var(--bg)}
input[type=checkbox]::after{content:"";width:11px;height:6px;border:solid var(--on-acc);border-width:0 0 2.5px 2.5px;transform:rotate(-45deg) translate(1px,-1px) scale(0);transition:transform .12s}
input[type=checkbox]:checked{background:var(--acc);border-color:var(--acc)} input[type=checkbox]:checked::after{transform:rotate(-45deg) translate(1px,-1px) scale(1)}
input[type=checkbox]:focus-visible{outline:3px solid var(--ring);outline-offset:2px;box-shadow:none}
button,.btn{display:inline-flex;align-items:center;justify-content:center;gap:8px;min-height:48px;padding:0 20px;background:var(--acc);color:var(--on-acc);border:1.5px solid transparent;border-radius:14px;font:700 15px var(--ff);cursor:pointer;transition:background .15s,transform .1s,border-color .15s}
button:hover{background:var(--acc-h)} button:active{transform:scale(.98)}
button.secondary{background:var(--s2);color:var(--fg);border-color:var(--bd);font-weight:600} button.secondary:hover{border-color:var(--mut);background:var(--s2)}
button.danger{background:transparent;color:var(--dng);border-color:var(--bd);font-weight:600} button.danger:hover{border-color:var(--dng);background:transparent}
button.block{width:100%}
.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-top:16px}
.reveal-box .row{margin-top:6px;flex-wrap:nowrap}.reveal-box input{flex:1;min-width:0;font-family:ui-monospace,Menlo,monospace;font-size:.85rem}
.row.end{justify-content:flex-end}
.ico{width:20px;height:20px;flex:none;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}
.inline{display:inline}
/* Messages */
.notice{display:flex;gap:10px;align-items:flex-start;padding:12px 14px;border-radius:14px;font-size:14px;font-weight:600;margin:0 0 16px;border:1px solid}
.notice.err{color:var(--dng);background:var(--acc-soft);border-color:var(--dng)} .notice.ok{color:var(--ok);background:var(--ok-soft);border-color:var(--ok)}
/* Auth */
.auth{max-width:420px;margin:0 auto;padding-top:max(32px,9vh)}
.auth .brand{margin-bottom:22px} .auth .logo{width:44px;height:44px}
.auth .panel{padding:24px}
.auth h1{margin-bottom:2px}
.auth form{margin-top:8px}
.auth .alt{margin-top:18px;text-align:center;color:var(--mut)}
.note{display:flex;gap:10px;margin-top:16px;padding:12px 14px;border-radius:14px;background:var(--s2);color:var(--fg2);font-size:13px}
/* En-tête */
.topbar{flex-wrap:wrap;display:flex;justify-content:space-between;align-items:center;gap:12px;padding:16px 0;margin-bottom:8px}
.who{display:flex;align-items:center;gap:10px;flex-wrap:wrap;justify-content:flex-end}
.pill{display:inline-flex;align-items:center;gap:8px;min-height:36px;padding:0 14px;border-radius:999px;background:var(--s1);border:1px solid var(--bd);color:var(--fg2);font-weight:600;font-size:13px;max-width:55vw}
.pill span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.topbar button{min-height:44px;padding:0 16px}
/* Appairage */
.pair{display:grid;gap:20px}
@media (min-width:900px){.pair{grid-template-columns:1fr 1fr;align-items:start;gap:40px}}
.steps{margin:12px 0 0;padding:0;list-style:none;display:grid;gap:10px;counter-reset:s}
.steps li{display:flex;gap:12px;align-items:flex-start;color:var(--fg2);font-size:14px;counter-increment:s}
.steps li::before{content:counter(s);flex:none;width:26px;height:26px;border-radius:50%;background:var(--acc-soft);color:var(--acc-fg);font:700 13px/26px var(--fh);text-align:center}
.codebox{display:flex;gap:6px;justify-content:center;align-items:center}
.cell{width:100%;max-width:46px;min-width:0;height:60px;padding:0;text-align:center;font:700 26px var(--fh);text-transform:uppercase;border-radius:14px;background:var(--bg)}
.codebox .sep{width:10px;height:3px;border-radius:2px;background:var(--mut);flex:none;margin:0 2px}
@media (max-width:380px){.codebox{gap:4px}.cell{height:54px;font-size:22px}}
.mt10{margin-top:10px}
.scanner{border:0;padding:0;margin:0;width:100%;max-width:none;height:100%;max-height:none;background:#050507;color:#F5F5F7;overflow:hidden}
.scanner[open]{display:flex;flex-direction:column}
.scanner::backdrop{background:#000}
.scan-bar{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:12px 16px;padding-top:max(12px,env(safe-area-inset-top))}
.scan-bar h2{font-size:17px;color:#F5F5F7}
.scan-x{background:rgba(255,255,255,.12);color:#F5F5F7;border-color:rgba(255,255,255,.28);min-height:48px;min-width:48px}
.scan-x:hover{background:rgba(255,255,255,.2)}
.scan-view{position:relative;flex:1;min-height:0;overflow:hidden;background:#000}
.scan-view video{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.scan-frame{position:absolute;left:50%;top:50%;width:min(68vw,68vh,340px);aspect-ratio:1;transform:translate(-50%,-50%);border:3px solid var(--acc);border-radius:22px;box-shadow:0 0 0 100vmax rgba(0,0,0,.55)}
.scan-msg{padding:14px 16px;padding-bottom:max(14px,env(safe-area-inset-bottom));text-align:center;font-weight:600;color:#E4E4EA;min-height:56px}
.scan-msg.bad{color:#FF8A92}
.hint.bad{color:#E5484D}
.code-fallback{font-family:var(--fh);font-weight:700;font-size:24px;letter-spacing:.18em;text-align:center;text-transform:uppercase}
/* Cartes appareils / sources */
.card{background:var(--s2);border:1px solid var(--bd);border-radius:16px;padding:14px 16px;transition:border-color .15s}
.card:hover{border-color:var(--mut)}
.card-top{display:flex;gap:12px;align-items:center}
.badge-ico{width:44px;height:44px;flex:none;border-radius:14px;background:var(--bg);border:1px solid var(--bd);display:grid;place-items:center;color:var(--acc-fg)}
.card-title{font-weight:700;font-size:16px;line-height:1.3}
.card-meta{color:var(--mut);font-size:13px}.acct strong{color:var(--fg)}.acct.warn,.acct.warn strong{color:#d08a00}.acct.bad,.acct.bad strong{color:#d6334a}
.grow{min-width:0;flex:1}
.kind{display:inline-flex;align-items:center;min-height:22px;padding:0 8px;border-radius:7px;background:var(--acc-soft);color:var(--acc-fg);font:700 11px var(--ff);letter-spacing:.06em;margin-right:6px;vertical-align:1px}
.chips{display:flex;flex-wrap:wrap;gap:6px;margin-top:12px}
a.button{display:inline-flex;align-items:center;min-height:44px;padding:0 18px;border-radius:14px;background:var(--acc);color:#fff;font-weight:700;font-size:14px;text-decoration:none}a.button:hover{text-decoration:none;filter:brightness(1.08)}
.chip{display:inline-flex;align-items:center;gap:6px;min-height:26px;padding:0 10px;border-radius:999px;border:1px solid var(--bd);background:var(--bg);color:var(--fg2);font-size:12px;font-weight:600}
.chip.all{background:var(--ok-soft);border-color:transparent;color:var(--ok)}
.chip.none{color:var(--dng)}
.chip.acc{background:var(--acc-soft);border-color:transparent;color:var(--acc-fg)}
.actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}
.actions button,.actions summary{min-height:44px}
details>summary{list-style:none;display:inline-flex;align-items:center;gap:8px;min-height:44px;padding:0 16px;border-radius:14px;background:var(--bg);border:1.5px solid var(--bd);color:var(--fg);font-weight:600;font-size:14px;cursor:pointer;transition:border-color .15s}
details>summary::-webkit-details-marker{display:none}
details>summary:hover{border-color:var(--mut)}
details[open]>summary{border-color:var(--acc);color:var(--acc-fg)}
details>.drop{margin-top:12px;padding-top:4px;flex-basis:100%}
.actions details{display:contents}
.actions details[open]{display:block;flex-basis:100%}
.actions details[open]>summary{margin-bottom:0}
.picks{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}
.pick{display:inline-flex;align-items:center;gap:10px;min-height:44px;margin:0;padding:0 14px;border-radius:12px;background:var(--bg);border:1.5px solid var(--bd);color:var(--fg);font-size:14px;font-weight:600;cursor:pointer}
.pick:has(input:checked){border-color:var(--acc)}
.pick.all{border-style:dashed}
.empty{display:flex;flex-direction:column;align-items:center;text-align:center;gap:8px;padding:28px 16px;border:1.5px dashed var(--bd);border-radius:16px;color:var(--mut)}
.empty .badge-ico{width:56px;height:56px;border-radius:18px;background:var(--s2)} .empty .badge-ico .ico{width:26px;height:26px}
.empty strong{color:var(--fg);font:600 16px var(--fh)}
/* Onglets d'ajout (CSS seul : fonctionne sans JavaScript) */
.tabbed{position:relative}
.seg{display:flex;gap:4px;padding:4px;background:var(--bg);border:1px solid var(--bd);border-radius:16px;margin:16px 0 4px}
.tabbed>input[type=radio]{position:absolute;opacity:0;width:1px;height:1px;min-height:0;pointer-events:none}
.seg label{flex:1;margin:0;min-height:44px;display:grid;place-items:center;border-radius:12px;color:var(--fg2);font-size:14px;cursor:pointer;transition:background .15s,color .15s}
#t-xtream:checked~.seg label[for=t-xtream],#t-m3u:checked~.seg label[for=t-m3u]{background:var(--acc);color:var(--on-acc)}
#t-xtream:focus-visible~.seg label[for=t-xtream],#t-m3u:focus-visible~.seg label[for=t-m3u]{outline:3px solid var(--ring);outline-offset:2px}
.tabbed form{display:none} #t-xtream:checked~#form-xtream,#t-m3u:checked~#form-m3u{display:block}
.secure{display:flex;gap:8px;align-items:flex-start;margin-top:14px;color:var(--mut);font-size:13px}
.secure .ico{width:16px;height:16px;margin-top:3px}
.danger-zone{border-color:color-mix(in srgb,var(--dng) 40%,var(--bd))}
/* Journaux (exploitation) */
.tablewrap{overflow-x:auto;border:1px solid var(--bd);border-radius:16px;background:var(--s1)}
table{width:100%;border-collapse:collapse;font-size:12px}
th,td{padding:8px 10px;border-bottom:1px solid var(--bd);text-align:left;vertical-align:top}
th{color:var(--mut);text-transform:uppercase;letter-spacing:.06em;font-size:10px}
pre{white-space:pre-wrap;word-break:break-word;margin:8px 0 0;font:12px ui-monospace,Menlo,monospace;color:var(--fg2)}
.lvl-error td.level,.lvl-warn td.level{color:var(--dng)}
.mt0{margin-top:0}.mt6{margin-top:6px}.mt10{margin-top:10px}.mt16{margin-top:16px}.mb16{margin-bottom:16px}
/* Console (refonte) : barre latérale + contenu */
.shell{display:flex;min-height:100vh;margin:0 -16px}
.side{width:248px;flex:none;position:sticky;top:0;height:100vh;padding:24px 16px;display:flex;flex-direction:column;gap:4px;background:var(--s1);border-right:1px solid var(--bd)}
.side .brand{padding:0 8px 20px}
.side nav{display:flex;flex-direction:column;gap:4px;flex:1}
.side nav a{display:flex;align-items:center;gap:12px;min-height:44px;padding:0 12px;border-radius:12px;color:var(--fg2);font-weight:600;font-size:14px;text-decoration:none}
.side nav a:hover{background:var(--s2);color:var(--fg);text-decoration:none}
.side nav a.on{background:var(--s2);color:var(--fg);box-shadow:inset 3px 0 0 var(--acc)}
.side .me{border-top:1px solid var(--bd);padding:14px 8px 0;display:grid;gap:10px}
.side .me .login{display:flex;align-items:center;gap:8px;color:var(--fg2);font-weight:600;font-size:13px;min-width:0}
.side .me .login span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.side .me button{width:100%;min-height:44px}
.content{flex:1;min-width:0;padding:32px 40px 56px;max-width:1180px}
.hello h1{font-size:30px}
.tiles{display:grid;gap:12px;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));margin:22px 0 8px}
.tile{background:var(--s1);border:1px solid var(--bd);border-radius:18px;padding:16px 18px;display:flex;flex-direction:column;gap:2px;box-shadow:var(--shadow)}
.tile b{font:700 30px var(--fh);color:var(--fg);line-height:1.15}
.tile span{color:var(--mut);font-size:13px;font-weight:600}
.tile.hero{background:linear-gradient(135deg,var(--acc),#8f1019);border-color:transparent}
.tile.hero b,.tile.hero span{color:#fff}
.tile.warn b{color:#d08a00}.tile.bad b{color:#d6334a}
.section-h{display:flex;align-items:baseline;justify-content:space-between;gap:12px;margin:34px 0 12px;flex-wrap:wrap;scroll-margin-top:16px}
.section-h h2{font-size:21px}
.two{display:grid;gap:16px;align-items:start}
@media (min-width:1000px){.two{grid-template-columns:minmax(0,1fr) minmax(0,1fr)}}
.cards{display:grid;gap:12px}
@media (min-width:1100px){.cards.c2{grid-template-columns:minmax(0,1fr) minmax(0,1fr)}}
.content .card{background:var(--s1);border-radius:18px;padding:18px;box-shadow:var(--shadow)}
.meter{height:6px;border-radius:999px;background:var(--s2);overflow:hidden;margin-top:8px}
.meter i{display:block;height:100%;width:0;background:var(--ok);border-radius:999px;transition:width .4s}
.acct.warn .meter i{background:#d08a00}.acct.bad .meter i{background:#d6334a}
@media (max-width:899px){.shell{flex-direction:column;margin:0;min-width:0;max-width:100%}.side,.content{min-width:0;max-width:100%}
.side{width:auto;height:auto;position:sticky;top:0;z-index:5;flex-direction:row;align-items:center;flex-wrap:wrap;padding:10px 12px;gap:8px;border-right:0;border-bottom:1px solid var(--bd)}
.side .brand{padding:0}.side nav{flex-direction:row;flex:1 1 100%;min-width:0;max-width:100%;order:3;overflow-x:auto;gap:2px;scrollbar-width:none}.side nav a{min-height:38px;padding:0 10px;white-space:nowrap}
.side .me{border:0;padding:0;display:flex;align-items:center;margin-left:auto}.side .me .login{display:none}.side .me button{width:auto;min-height:38px}
.content{padding:20px 0 40px}.hello h1{font-size:24px}}
.kind.pro{background:linear-gradient(135deg,var(--acc),#8f1019);color:#fff;margin:0 0 0 6px}.kind.std{background:var(--s2);color:var(--fg2);margin:0 0 0 6px}
.brand .kind.pro{font-size:10px;min-height:20px}
.listbar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:0 0 12px}
.listbar .lsearch{flex:1 1 240px;min-height:44px}
.pager{display:inline-flex;align-items:center;gap:8px}.pager button{min-height:44px;min-width:44px;padding:0 12px}
.pinfo{color:var(--mut);font-size:13px;font-weight:600;white-space:nowrap}
[hidden]{display:none!important}
@media (prefers-reduced-motion:reduce){*{transition:none!important;animation:none!important}}
`;

const ICONS = {
  tv: '<rect x="3" y="4" width="18" height="13" rx="3"/><path d="M8 21h8"/>',
  list: '<path d="M8 6h13M8 12h13M8 18h13"/><circle cx="3.5" cy="6" r="1"/><circle cx="3.5" cy="12" r="1"/><circle cx="3.5" cy="18" r="1"/>',
  lock: '<rect x="4" y="11" width="16" height="10" rx="3"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c1-4 4-6 8-6s7 2 8 6"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
  edit: '<path d="M4 20h4L19 9l-4-4L4 16z"/>',
  share: '<circle cx="6" cy="12" r="2.5"/><circle cx="18" cy="6" r="2.5"/><circle cx="18" cy="18" r="2.5"/><path d="M8.2 11l7.6-3.8M8.2 13l7.6 3.8"/>',
  scan: '<path d="M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2M7 12h10"/>',
  close: '<path d="M6 6l12 12M18 6L6 18"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  home: '<path d="M4 11l8-7 8 7v9a1 1 0 0 1-1 1h-4v-6H9v6H5a1 1 0 0 1-1-1z"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1"/>',
};
const ico = (k) => `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true">${ICONS[k]}</svg>`;

const LOGO = `<svg class="logo" viewBox="0 0 512 512" role="img" aria-label="Ultra TV"><rect class="lg-bg" width="512" height="512" rx="116"/><rect class="lg-fr" x="96" y="120" width="320" height="216" rx="40" fill="none" stroke-width="28"/><path d="M224 188v80l70-40z" fill="#D91E2B"/><path class="lg-fr" d="M196 392h120" stroke-width="28" stroke-linecap="round"/></svg>`;
const FAVICON = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 512 512'%3E%3Crect width='512' height='512' rx='116' fill='%230A0A0C'/%3E%3Crect x='96' y='120' width='320' height='216' rx='40' fill='none' stroke='%23F5F5F7' stroke-width='28'/%3E%3Cpath d='M224 188v80l70-40z' fill='%23D91E2B'/%3E%3Cpath d='M196 392h120' stroke='%23F5F5F7' stroke-width='28' stroke-linecap='round'/%3E%3C/svg%3E";

// Thème choisi (Automatique / Clair / Sombre), gardé dans le navigateur ; appliqué AVANT le premier rendu (pas de flash).
const THEME_HEAD = `try{var t=localStorage.getItem('utv-theme');if(t==='light'||t==='dark')document.documentElement.setAttribute('data-theme',t);}catch(e){}`;
const THEME_TOGGLE = `(function(){var b=document.getElementById('theme-toggle');if(!b)return;var L={auto:'Thème : automatique',light:'Thème : clair',dark:'Thème : sombre'},O=['auto','light','dark'];
function cur(){try{return localStorage.getItem('utv-theme')||'auto';}catch(e){return 'auto';}}
function show(t){b.textContent=L[t];b.setAttribute('aria-label',L[t]+' (changer)');}
b.addEventListener('click',function(){var t=O[(O.indexOf(cur())+1)%3];try{if(t==='auto')localStorage.removeItem('utv-theme');else localStorage.setItem('utv-theme',t);}catch(e){}
if(t==='auto')document.documentElement.removeAttribute('data-theme');else document.documentElement.setAttribute('data-theme',t);show(t);});show(cur());})();`;

function layout(title, body, n, extra = "", camera = false) {
  return html(`<!doctype html><html lang="fr"><head><script nonce="${n}">${THEME_HEAD}</script><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/><meta name="robots" content="noindex"/><meta name="color-scheme" content="dark light"/><meta name="theme-color" content="#0A0A0C"/><link rel="icon" href="${FAVICON}"/><title>${e(title)}</title><style nonce="${n}">${CSS}</style></head><body><a class="skip" href="#main">Aller au contenu</a>${body}<button type="button" class="theme-toggle" id="theme-toggle">Thème</button><script nonce="${n}">${THEME_TOGGLE}</script>${extra}</body></html>`, n, 200, { camera });
}

const AUTH_ERR = {
  pw: "Identifiant ou mot de passe incorrect.",
  login: "Identifiant invalide (3 à 64 caractères : lettres, chiffres, . _ @ : + -).",
  short: "Mot de passe trop court (10 caractères minimum).",
  mismatch: "Les deux mots de passe ne correspondent pas.",
  taken: "Cet identifiant n'est pas disponible.",
};

const authErr = (err) => (err && AUTH_ERR[err] ? `<div class="notice err" role="alert">${ico("info")}<span>${e(AUTH_ERR[err])}</span></div>` : "");

const nextField = (next) => (next && next !== "/" ? `<input type="hidden" name="next" value="${e(next)}"/>` : "");
const nextQ = (next) => (next && next !== "/" ? `?next=${encodeURIComponent(next)}` : "");

export function loginPage(n, err, next = "/") {
  return layout("Ultra TV — connexion", `
<main id="main" class="auth"><div class="brand">${LOGO}<span>Ultra TV</span></div>
<div class="panel"><h1>Connexion</h1><p class="sub">Retrouve ta configuration et tes appareils.</p>
${authErr(err)}
<form method="post" action="/login">${nextField(next)}
<label for="login">Identifiant <span class="hint">(ou ton ancienne adresse MAC)</span></label>
<input id="login" name="login" required autocomplete="username" autocapitalize="none" spellcheck="false" autofocus />
<label for="pw">Mot de passe</label>
<input id="pw" name="password" type="password" required autocomplete="current-password" />
<div class="row"><button class="block" type="submit">Se connecter</button></div>
</form></div>
<p class="alt">Pas encore de compte ? <a href="/signup${e(nextQ(next))}">Créer un compte</a></p></main>`, n);
}

export function signupPage(n, err, next = "/") {
  return layout("Ultra TV — inscription", `
<main id="main" class="auth"><div class="brand">${LOGO}<span>Ultra TV</span></div>
<div class="panel"><h1>Créer un compte</h1><p class="sub">Une seule configuration pour toutes tes TV.</p>
${authErr(err)}
<form method="post" action="/signup">${nextField(next)}
<label for="login">Identifiant <span class="hint">(3 à 64 caractères)</span></label>
<input id="login" name="login" required autocomplete="username" autocapitalize="none" spellcheck="false" minlength="3" maxlength="64" />
<label for="pw">Mot de passe <span class="hint">(10 caractères minimum)</span></label>
<input id="pw" name="password" type="password" required minlength="10" maxlength="200" autocomplete="new-password" />
<label for="pw2">Confirmer le mot de passe</label>
<input id="pw2" name="confirm" type="password" required minlength="10" maxlength="200" autocomplete="new-password" />
<div class="note">${ico("info")}<span>L'adresse MAC de ta box n'est plus un identifiant : ton appareil s'associe à ton compte avec un code affiché sur la TV.</span></div>
<div class="row"><button class="block" type="submit">Créer le compte</button></div>
</form></div>
<p class="alt">Déjà inscrit ? <a href="/login${e(nextQ(next))}">J'ai déjà un compte</a></p></main>`, n);
}

const DASH_MSG = {
  err: {
    code: "Code d'appairage invalide, expiré ou déjà utilisé.",
    limit: "Limite atteinte (100 fournisseurs, 50 appareils).",
    os_denied: "OpenSubtitles a refusé ces identifiants.",
    trakt: "Connexion à Trakt annulée ou refusée : réessaie.",
    trakt_na: "Trakt n'est pas encore disponible sur ce site.",
    os_busy: "OpenSubtitles est saturé : réessaie dans une minute.",
    os_upstream: "OpenSubtitles ne répond pas : réessaie plus tard.",
    kind: "Type de fournisseur inconnu.",
    url: "URL invalide : seules les URL http:// et https:// sont acceptées.",
    creds: "Utilisateur et mot de passe requis pour Xtream.",
    short: "Nouveau mot de passe trop court (10 caractères minimum).",
    pw: "Mot de passe actuel incorrect.",
    assign: "Choisis au moins un appareil de ton compte.",
  },
  ok: { trakt: "Trakt connecté : tes appareils y envoient ce que tu regardes.", trakt_off: "Trakt déconnecté.", os: "Compte OpenSubtitles relié : tes téléchargements de sous-titres utilisent ton quota.", os_off: "Compte OpenSubtitles délié.", paired: "Appareil appairé.", added: "Fournisseur ajouté.", revoked: "Appareil révoqué.", renamed: "Appareil renommé.", assigned: "Affectation enregistrée.", pw: "Mot de passe mis à jour." },
};

/** « il y a 3 jours » : durée relative lisible, calculée côté serveur. */
function ago(t, now = Date.now()) {
  if (!t) return "";
  const s = Math.max(0, Math.round((now - t) / 1000));
  if (s < 60) return "à l'instant";
  const steps = [[3600, 60, "min"], [86400, 3600, "h"], [2592000, 86400, "j"], [31536000, 2592000, "mois"]];
  for (const [lim, div, unit] of steps) if (s < lim) return `il y a ${Math.floor(s / div)} ${unit}`;
  const y = Math.floor(s / 31536000);
  return `il y a ${y} an${y > 1 ? "s" : ""}`;
}

/** Badges de partage + formulaire (cases à cocher + raccourci « Tous »). Sans JavaScript, tout fonctionne. */
function sharing(p, devices, csrfInput) {
  const a = p.assign === undefined || p.assign === "all" ? "all" : Array.isArray(p.assign) ? p.assign : "all";
  const shown = a === "all" ? [] : devices.filter((d) => a.includes(d.id));
  const chips = a === "all"
    ? `<span class="chip all">${ico("share")}Tous les appareils</span>`
    : shown.length ? shown.map((d) => `<span class="chip">${e(d.name)}</span>`).join("") : `<span class="chip none">Aucun appareil</span>`;
  const picks = devices.map((d) => `<label class="pick"><input type="checkbox" name="d" value="${e(d.id)}"${a === "all" || a.includes(d.id) ? " checked" : ""}/>${e(d.name)}</label>`).join("");
  return { chips: `<div class="chips" aria-label="Partagé avec">${chips}</div>`,
    form: `<details><summary>${ico("share")}Partage</summary><form method="post" action="/providers/${e(p.id)}/assign" class="drop share-form">${csrfInput}
<div class="lbl mt0">Reçu par</div>
<div class="picks"><label class="pick all"><input type="checkbox" name="all" value="1"${a === "all" ? " checked" : ""}/>Tous les appareils</label>${picks}</div>
<div class="row"><button type="submit">Enregistrer le partage</button></div></form></details>` };
}

const PAIR_JS = `(function(){var box=document.getElementById('codecells'),fb=document.getElementById('code');if(!box||!fb)return;
 var cs=Array.prototype.slice.call(box.querySelectorAll('.cell'));box.hidden=false;fb.hidden=true;fb.required=false;fb.disabled=true;
 var hid=document.createElement('input');hid.type='hidden';hid.name='code';fb.parentNode.appendChild(hid);
 function sync(){hid.value=cs.map(function(c){return c.value;}).join('');}
 function fill(txt,from){var t=txt.toUpperCase().replace(/[^A-Z0-9]/g,'');for(var i=0;i<t.length&&from+i<cs.length;i++)cs[from+i].value=t[i];sync();var k=Math.min(from+t.length,cs.length-1);cs[k].focus();}
 cs.forEach(function(c,i){
  c.addEventListener('input',function(){var v=c.value;c.value='';if(v)fill(v,i);else sync();});
  c.addEventListener('keydown',function(ev){if(ev.key==='Backspace'&&!c.value&&i>0){cs[i-1].value='';cs[i-1].focus();sync();}
   else if(ev.key==='ArrowLeft'&&i>0){cs[i-1].focus();}else if(ev.key==='ArrowRight'&&i<cs.length-1){cs[i+1].focus();}});
  c.addEventListener('paste',function(ev){ev.preventDefault();fill((ev.clipboardData||window.clipboardData).getData('text'),i);});
  c.addEventListener('focus',function(){c.select();});});sync();
 box.closest('form').addEventListener('submit',function(){sync();});})();
`;

// Scanner de QR : caméra arrière, décodage sur le thread principal (~8 images/s), aucun worker/blob.
// BarcodeDetector natif si présent, sinon jsQR (/assets/jsqr.js, chargé à l'ouverture seulement).
// Le contenu lu n'est jamais ouvert : parsePairQr n'en extrait que le code, l'utilisateur confirme avec « Appairer ».
const SCAN_JS = `(function(){var nonce=document.currentScript&&document.currentScript.nonce||'';
 var btn=document.getElementById('scan-btn'),dlg=document.getElementById('scanner');
 var parse=${parsePairQr.toString()};
 var imp=document.getElementById('scan-file-btn'),file=document.getElementById('scan-file'),fmsg=document.getElementById('scan-file-msg');
 if(!btn||!dlg)return;
 var cam=!!(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia);
 var video=document.getElementById('scan-video'),msg=document.getElementById('scan-msg'),shut=document.getElementById('scan-close');
 var stream=null,timer=0,det=null,canvas=null,ctx=null,running=false,loading=null;
 var BAD='Ce QR ne vient pas d\\u2019Ultra TV',DENIED='Autorise la cam\\u00e9ra dans les r\\u00e9glages du navigateur, ou saisis le code.';
 btn.hidden=!cam;
 function say(t,bad){msg.textContent=t;msg.className=bad?'scan-msg bad':'scan-msg';}
 function stop(){running=false;clearTimeout(timer);if(stream){stream.getTracks().forEach(function(t){t.stop();});stream=null;}video.srcObject=null;}
 function loadJsqr(){if(window.jsQR)return Promise.resolve();if(loading)return loading;
  loading=new Promise(function(ok,ko){var s=document.createElement('script');s.src='/assets/jsqr.js';s.nonce=nonce;s.onload=function(){window.jsQR?ok():ko();};s.onerror=function(){loading=null;ko();};document.head.appendChild(s);});return loading;}
 function decode(){
  if(det)return det.detect(video).then(function(r){return r.length?r[0].rawValue:null;});
  var w=video.videoWidth,h=video.videoHeight;if(!w||!h)return Promise.resolve(null);
  var k=Math.min(1,640/Math.max(w,h));w=Math.round(w*k);h=Math.round(h*k);
  if(!canvas){canvas=document.createElement('canvas');ctx=canvas.getContext('2d',{willReadFrequently:true});}
  canvas.width=w;canvas.height=h;ctx.drawImage(video,0,0,w,h);
  var r=window.jsQR(ctx.getImageData(0,0,w,h).data,w,h,{inversionAttempts:'dontInvert'});return Promise.resolve(r?r.data:null);}
 function tick(){if(!running)return;
  decode().then(function(text){if(!running)return;
   if(text){var code=parse(text,location.origin);
    if(code){found(code);return;}
    say(BAD,true);}
   timer=setTimeout(tick,125);},function(){if(running)timer=setTimeout(tick,250);});}
 function found(code){stop();if(dlg.open)dlg.close();
  var cs=document.querySelectorAll('#codecells .cell');for(var i=0;i<cs.length&&i<8;i++)cs[i].value=code.charAt(i);
  var fb=document.getElementById('code');if(fb)fb.value=code;
  var h=document.querySelector('input[name=code][type=hidden]');if(h)h.value=code;
  var d=document.getElementById('dname');if(d)d.focus();}
 function open(){say('Place le QR affich\\u00e9 sur la TV dans le cadre.');
  if(!dlg.open)dlg.showModal();
  navigator.mediaDevices.getUserMedia({video:{facingMode:{ideal:'environment'},width:{ideal:1280},height:{ideal:720}},audio:false}).then(function(s){
   if(!dlg.open){s.getTracks().forEach(function(t){t.stop();});return;}
   stream=s;video.srcObject=s;return video.play().catch(function(){}).then(function(){
    if(window.BarcodeDetector){try{det=new BarcodeDetector({formats:['qr_code']});}catch(x){det=null;}}
    return det?null:loadJsqr();}).then(function(){running=true;tick();},function(){stop();say('Lecteur de QR indisponible. Saisis le code \\u00e0 la main.',true);});
  },function(){say(DENIED,true);});}
 // Import d'une image (capture d'écran ou photo du QR de la TV) : bureau sans caméra, ou Ctrl/Cmd+V.
 // createImageBitmap lit le fichier sans URL blob (CSP img-src 'self' data:). Rien n'est envoyé au serveur.
 function fsay(t,bad){if(fmsg){fmsg.textContent=t;fmsg.className=bad?'hint small mt6 bad':'hint small mt6';}}
 function fromImage(blob){
  if(!blob||String(blob.type||'').indexOf('image/')!==0){fsay('Choisis une image (PNG, JPEG\u2026).',true);return;}
  fsay('Lecture du QR\u2026');
  createImageBitmap(blob).then(function(bmp){
   var nat=null;if(window.BarcodeDetector){try{nat=new BarcodeDetector({formats:['qr_code']});}catch(x){nat=null;}}
   var viaNative=nat?nat.detect(bmp).then(function(r){return r.length?r[0].rawValue:null;},function(){return null;}):Promise.resolve(null);
   return viaNative.then(function(t){if(t)return t;return loadJsqr().then(function(){
     var w=bmp.width,h=bmp.height,k=Math.min(1,1600/Math.max(w,h));w=Math.max(1,Math.round(w*k));h=Math.max(1,Math.round(h*k));
     var c=document.createElement('canvas');c.width=w;c.height=h;var x=c.getContext('2d',{willReadFrequently:true});x.drawImage(bmp,0,0,w,h);
     var r=window.jsQR(x.getImageData(0,0,w,h).data,w,h,{inversionAttempts:'attemptBoth'});return r?r.data:null;});});
  }).then(function(text){
   if(!text){fsay('Aucun QR trouv\u00e9 dans cette image. Recadre-le ou saisis le code.',true);return;}
   var code=parse(text,location.origin);
   if(!code){fsay(BAD,true);return;}
   fsay('Code lu : '+code.slice(0,4)+'-'+code.slice(4)+'. V\u00e9rifie-le puis appuie sur Appairer.');found(code);
  },function(){fsay('Image illisible. Saisis le code \u00e0 la main.',true);});}
 if(imp&&file){imp.hidden=false;
  imp.addEventListener('click',function(){file.value='';file.click();});
  file.addEventListener('change',function(){if(file.files&&file.files[0])fromImage(file.files[0]);});
  document.addEventListener('paste',function(ev){var it=ev.clipboardData&&ev.clipboardData.items;if(!it)return;
   for(var i=0;i<it.length;i++){if(it[i].kind==='file'&&String(it[i].type||'').indexOf('image/')===0){ev.preventDefault();fromImage(it[i].getAsFile());return;}}});}
 btn.addEventListener('click',open);
 shut.addEventListener('click',function(){dlg.close();});
 dlg.addEventListener('close',stop);dlg.addEventListener('cancel',stop);
})();
`;

function pairForm(csrfInput, code = "") {
  const c = code || "";
  const cells = Array.from({ length: 8 }, (_, i) => `${i === 4 ? '<span class="sep" aria-hidden="true"></span>' : ""}<input class="cell" maxlength="1" inputmode="text" autocapitalize="characters" autocomplete="off" spellcheck="false" value="${e(c[i] || "")}" aria-label="Caractère ${i + 1} sur 8"/>`).join("");
  return `<form method="post" action="/pair">${csrfInput}
<div class="lbl mt0" id="l-code">Code affiché sur la TV</div>
<div id="codecells" class="codebox" role="group" aria-labelledby="l-code" hidden>${cells}</div>
<input id="code" class="code-fallback" name="code" required maxlength="12" autocomplete="off" autocapitalize="characters" spellcheck="false" placeholder="ABCD-EFGH" aria-labelledby="l-code" aria-describedby="code-hint" value="${e(c ? `${c.slice(0, 4)}-${c.slice(4)}` : "")}"/>
<p class="hint small mt6" id="code-hint">8 caractères. Le tiret est facultatif et les minuscules sont acceptées.</p>
<button type="button" id="scan-btn" class="secondary block mt10" hidden>${ico("scan")}Scanner le QR de la TV</button>
<button type="button" id="scan-file-btn" class="secondary block mt10" hidden>${ico("scan")}Importer une image du QR</button>
<input type="file" id="scan-file" accept="image/*" hidden/>
<p class="hint small mt6" id="scan-file-msg" role="status" aria-live="polite">Sur ordinateur : choisis une photo ou une capture du QR, ou colle-la (Ctrl/Cmd+V).</p>
<label for="dname">Nom de l'appareil <span class="hint">(facultatif)</span></label><input id="dname" name="name" maxlength="40" placeholder="Salon"/>
<div class="row"><button class="block" type="submit">Appairer</button></div></form>
<dialog id="scanner" class="scanner" aria-labelledby="scan-title"><div class="scan-bar"><h2 id="scan-title">Scanner le QR de la TV</h2><button type="button" id="scan-close" class="scan-x">${ico("close")}Fermer</button></div>
<div class="scan-view"><video id="scan-video" playsinline muted></video><div class="scan-frame" aria-hidden="true"></div></div>
<p id="scan-msg" class="scan-msg" role="status" aria-live="polite"></p></dialog>`;
}

export function pairPage(n, { acct, csrf, code, invalid }) {
  const csrfInput = `<input type="hidden" name="csrf" value="${e(csrf)}"/>`;
  const msg = invalid ? `<div class="notice err" role="alert">${ico("info")}<span>Ce code n'est pas valide : il doit faire 8 caractères. Vérifie l'écran de ta TV ou saisis-le à la main.</span></div>` : "";
  return layout("Ultra TV — appairer", `
<main id="main" class="auth"><div class="brand">${LOGO}<span>Ultra TV</span></div>
<div class="panel"><h1>${code ? "Appairer cet appareil\u00a0?" : "Appairer un appareil"}</h1>
<p class="sub">${code ? "Une TV demande l'accès à ta configuration. Vérifie que le code ci-dessous est bien celui affiché sur son écran." : "Saisis le code affiché sur ta TV."}</p>
${msg}<p class="muted small mt10">Compte : <strong>${e(acct.login)}</strong></p>
${pairForm(csrfInput, code || "")}
<p class="alt"><a href="/">Annuler</a></p></div></main>`, n, `<script nonce="${n}">${PAIR_JS}${SCAN_JS}</script>`, true);
}

const MOIS = ["janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc."];
const frDate = (ms) => { const d = new Date(ms); return `${d.getUTCDate()} ${MOIS[d.getUTCMonth()]} ${d.getUTCFullYear()}`; };
const LIC_STATUS = { active: "Active", trial: "Essai", expired: "Expirée", suspended: "Suspendue", revoked: "Révoquée" };

/** Licence d'un appareil Pro (statut signé par le revendeur, vérifié à la réception). */
function licenseLine(lic, now = Date.now()) {
  if (!lic) return `<div class="card-meta mt10 muted">Licence Pro : en attente de la prochaine synchronisation de l'appareil.</div>`;
  const left = lic.until ? Math.ceil((lic.until - now) / 86_400_000) : null;
  const expired = lic.status === "expired" || (left !== null && left < 0);
  const cls = expired || lic.status === "suspended" || lic.status === "revoked" ? " bad" : left !== null && left <= 15 ? " warn" : "";
  const parts = [`<strong>${e(LIC_STATUS[lic.status] || lic.status || "?")}</strong>`];
  if (lic.until) parts.push(expired ? `expirée le ${e(frDate(lic.until))}` : `jusqu'au ${e(frDate(lic.until))} (${left === 0 ? "aujourd'hui" : `dans ${left} j`})`);
  if (lic.devices) parts.push(`${lic.devices.used}/${lic.devices.max} appareil${lic.devices.max > 1 ? "s" : ""}`);
  const r = lic.reseller;
  const contact = r ? [
    r.name ? `revendeur <strong>${e(r.name)}</strong>` : "",
    r.whatsapp && /^\+?[0-9 ]{6,20}$/.test(r.whatsapp) ? `<a href="https://wa.me/${e(r.whatsapp.replace(/[^0-9]/g, ""))}" target="_blank" rel="noopener noreferrer">WhatsApp</a>` : "",
    r.telegram && /^@?[A-Za-z0-9_]{4,32}$/.test(r.telegram) ? `<a href="https://t.me/${e(r.telegram.replace(/^@/, ""))}" target="_blank" rel="noopener noreferrer">Telegram</a>` : "",
  ].filter(Boolean).join(" · ") : "";
  const stale = now - lic.issuedAt > 2 * 86_400_000 ? ` <span class="muted">(vérifiée le ${e(frDate(lic.issuedAt))})</span>` : "";
  return `<div class="acct card-meta mt10${cls}">Licence Pro : ${parts.join(" · ")}${stale}${contact ? `<br/>${contact}` : ""}</div>`;
}

export function dashboardPage(n, { acct, providers, csrf, err, ok, admin = false }) {
  const csrfInput = `<input type="hidden" name="csrf" value="${e(csrf)}"/>`;
  const devices = acct.devices || [];
  const when = (t) => (t ? new Date(t).toISOString().slice(0, 10) : "");
  const provRows = providers.map((p) => {
    const sh = sharing(p, devices, csrfInput);
    const origin = p.originName ? `Ajouté depuis ${e(p.originName === "dashboard" ? "le tableau de bord" : p.originName)}` : "Origine inconnue";
    return `
<article class="card" data-q="${e(`${p.name} ${displayUrl(p.url)} ${p.kind}`.toLowerCase())}"><div class="card-top"><div class="badge-ico">${ico("list")}</div>
<div class="grow"><div class="card-title"><span class="kind">${e(p.kind)}</span>${e(p.name)}</div><div class="card-meta">${e(displayUrl(p.url))}</div></div></div>
${sh.chips}${p.managed === "reseller" ? `<div class="chips"><span class="chip acc">Gérée par ton revendeur (Pro)</span></div>` : ""}
<div class="card-meta mt10">${origin}${p.createdAt ? ` · le ${e(when(p.createdAt))}` : ""}${p.updatedAt && p.updatedAt > (p.createdAt || 0) + 60000 ? ` · modifié le ${e(when(p.updatedAt))}` : ""}</div>
${xtreamCredsOf(p) ? `<div class="acct card-meta mt10" data-id="${e(p.id)}" aria-live="polite"><span class="muted">Abonnement : chargement…</span></div>` : ""}
<div class="reveal-box mt10" id="lk-${e(p.id)}" hidden><label class="mt0" for="lki-${e(p.id)}">Lien IPTV</label>
<div class="row"><input id="lki-${e(p.id)}" readonly spellcheck="false" autocomplete="off"/><button type="button" class="secondary copy-link" data-for="lki-${e(p.id)}">Copier</button></div></div>
<div class="actions">${sh.form}
${p.managed === "reseller"
    ? `<span class="muted small">Ses identifiants appartiennent à ton revendeur : ni affichés ni supprimables ici.</span>`
    : `<button type="button" class="secondary reveal-link" data-id="${e(p.id)}" aria-controls="lk-${e(p.id)}" aria-expanded="false">Afficher le lien IPTV</button>
<form method="post" action="/providers/${e(p.id)}/delete" data-confirm="Supprimer ce fournisseur ?">${csrfInput}<button class="danger" type="submit">Supprimer</button></form>`}</div></article>`;
  }).join("");
  const devRows = devices.map((d) => `
<article class="card" data-q="${e(`${d.name} ${d.label || ""} ${d.edition || ""}`.toLowerCase())}"><div class="card-top"><div class="badge-ico">${ico("tv")}</div>
<div class="grow"><div class="card-title">${e(d.name)} ${d.edition === "pro" ? `<span class="kind pro">PRO</span>` : d.edition === "standard" ? `<span class="kind std">STANDARD</span>` : ""}</div><div class="card-meta">${d.label ? `${e(d.label)} · ` : ""}appairé ${e(ago(d.createdAt))}</div></div></div>
${d.edition === "pro" ? licenseLine(d.license) : ""}
<div class="actions"><details><summary>${ico("edit")}Renommer</summary><form method="post" action="/devices/${e(d.id)}/rename" class="drop">${csrfInput}
<label class="mt0" for="rn-${e(d.id)}">Nom de l'appareil</label><input id="rn-${e(d.id)}" name="name" maxlength="40" value="${e(d.name)}"/>
<div class="row"><button type="submit">Renommer</button></div></form></details>
<form method="post" action="/devices/${e(d.id)}/revoke" data-confirm="Révoquer cet appareil ? Il ne pourra plus lire ta configuration.">${csrfInput}<button class="danger" type="submit">Révoquer</button></form></div></article>`).join("");
  const msg = (err && DASH_MSG.err[err] && `<div class="notice err" role="alert">${ico("info")}<span>${e(DASH_MSG.err[err])}</span></div>`)
    || (ok && DASH_MSG.ok[ok] && `<div class="notice ok" role="status">${ico("info")}<span>${e(DASH_MSG.ok[ok])}</span></div>`) || "";
  const script = `<script nonce="${n}">
document.querySelectorAll('form[data-confirm]').forEach(function(f){f.addEventListener('submit',function(ev){if(!confirm(f.dataset.confirm))ev.preventDefault();});});
document.querySelectorAll('.share-form').forEach(function(f){var all=f.querySelector('input[name=all]'),ds=f.querySelectorAll('input[name=d]');
 all.addEventListener('change',function(){ds.forEach(function(d){d.checked=all.checked;});});
 ds.forEach(function(d){d.addEventListener('change',function(){all.checked=Array.prototype.every.call(ds,function(x){return x.checked;});});});});
document.querySelectorAll('.reveal-link').forEach(function(b){b.addEventListener('click',function(){
 var box=document.getElementById('lk-'+b.dataset.id);
 if(!box.hidden){box.hidden=true;box.querySelector('input').value='';b.setAttribute('aria-expanded','false');b.textContent='Afficher le lien IPTV';return;}
 b.disabled=true;
 fetch('/providers/'+b.dataset.id+'/link',{method:'POST',credentials:'same-origin',headers:{'content-type':'application/x-www-form-urlencoded'},body:'csrf='+encodeURIComponent(${JSON.stringify(csrf)})})
  .then(function(r){if(!r.ok)throw new Error(String(r.status));return r.json();})
  .then(function(j){var i=box.querySelector('input');i.value=j.link||'';box.hidden=false;b.setAttribute('aria-expanded','true');b.textContent='Masquer le lien';i.focus();i.select();})
  .catch(function(){alert('Impossible de récupérer le lien.');})
  .then(function(){b.disabled=false;});
});});
// Abonnement de chaque fournisseur Xtream : lu par le serveur (identifiants jamais exposés), un appel par carte.
(function(){
 var MOIS=['janv.','févr.','mars','avr.','mai','juin','juil.','août','sept.','oct.','nov.','déc.'];
 var dt=function(ms){var d=new Date(ms);return d.getDate()+' '+MOIS[d.getMonth()]+' '+d.getFullYear();};
 var ERR={unsupported:'non disponible pour une liste M3U',port:'port non joignable depuis Cloudflare : informations visibles seulement dans l\\'application',denied:'identifiants refusés par le serveur',unreachable:'le serveur ne répond pas (essaie plus tard)'};
 // Le statut vient du serveur du FOURNISSEUR (non fiable) : toujours échappé avant insertion.
 var x=function(v){return String(v).replace(/[&<>"']/g,function(c){return '&#'+c.charCodeAt(0)+';';});};
 var set=function(el,html,cls){el.innerHTML=html;el.className='acct card-meta mt10'+(cls?' '+cls:'');};
 // Chargé à la DEMANDE : seulement pour les cartes affichées (pagination) — un compte avec beaucoup de fournisseurs ne
 // lance pas des dizaines de requêtes d'un coup. La tuile résume ce qui a été vérifié.
 var total=document.querySelectorAll('.acct[data-id]').length,done=0,soon=0,expired=0,ok=0;
 var tally=function(){done++;var n=document.getElementById('sub-n'),l=document.getElementById('sub-l'),t=document.getElementById('sub-tile');if(!n)return;
  var of=done<total?' (sur '+done+' vérifié'+(done>1?'s':'')+')':'';t.classList.remove('bad','warn');
  if(expired){n.textContent=String(expired);l.textContent='abonnement'+(expired>1?'s':'')+' expiré'+(expired>1?'s':'')+of;t.classList.add('bad');}
  else if(soon){n.textContent=String(soon);l.textContent='expire'+(soon>1?'nt':'')+' dans moins de 15 jours'+of;t.classList.add('warn');}
  else if(ok){n.textContent=String(ok);l.textContent='abonnement'+(ok>1?'s':'')+' actif'+(ok>1?'s':'')+of;}
  else if(done>=total){n.textContent='—';l.textContent='abonnements : infos indisponibles';}};
 window.utvLoadAccounts=function(root){(root||document).querySelectorAll('.acct[data-id]:not([data-loaded])').forEach(function(el){
  if(el.closest('[hidden]'))return;el.setAttribute('data-loaded','1');
  fetch('/providers/'+el.dataset.id+'/account',{method:'POST',credentials:'same-origin',headers:{'content-type':'application/x-www-form-urlencoded'},body:'csrf='+encodeURIComponent(${JSON.stringify(csrf)})})
   .then(function(r){if(!r.ok)throw new Error(String(r.status));return r.json();})
   .then(function(a){
    if(a.error){set(el,'<span class="muted">Abonnement : '+(a.error==='port'&&a.port?'port '+x(a.port)+' non joignable depuis Cloudflare : informations visibles seulement dans l\\'application':a.error==='unreachable'&&a.detail?x(a.detail):(ERR[a.error]||ERR.unreachable))+'</span>');tally();return;}
    var parts=[],cls='';
    var st=(a.status||'').toLowerCase();
    var active=st==='active'||st==='';
    if(a.expiresAt){
     var days=Math.ceil((a.expiresAt-Date.now())/86400000);
     if(days<0){parts.push('<strong>Expiré</strong> le '+dt(a.expiresAt));cls='bad';}
     else{parts.push((active?'<strong>Actif</strong>':'<strong>'+x(a.status||'')+'</strong>')+' · expire le '+dt(a.expiresAt)+' ('+(days===0?'aujourd\\'hui':'dans '+days+' j')+')');if(days<=15)cls='warn';}
    }else parts.push((active?'<strong>Actif</strong>':'<strong>'+x(a.status||'')+'</strong>')+' · sans date d\\'expiration');
    if(!active&&cls!=='bad')cls='bad';
    if(a.maxCons!=null)parts.push((a.activeCons!=null?a.activeCons+'/':'')+a.maxCons+' connexion'+(a.maxCons>1?'s':''));
    if(a.trial)parts.push('essai');
    if(a.createdAt)parts.push('créé le '+dt(a.createdAt));
    set(el,'Abonnement : '+parts.join(' · '),cls);
    if(cls==='bad')expired++;else if(cls==='warn')soon++;else ok++;
    // Jauge : part de la période (création → expiration) déjà écoulée ; largeur posée par le CSSOM (pas d'attribut style).
    if(a.expiresAt&&a.createdAt&&a.expiresAt>a.createdAt){var m=document.createElement('div');m.className='meter';var i=document.createElement('i');m.appendChild(i);el.appendChild(m);
     var f=Math.min(1,Math.max(0,(Date.now()-a.createdAt)/(a.expiresAt-a.createdAt)));requestAnimationFrame(function(){i.style.width=Math.round(f*100)+'%';});}
    tally();
   })
   .catch(function(e){var c=e&&/^\\d+$/.test(e.message)?e.message:'';set(el,'<span class="muted">Abonnement : '+(c==='429'?'trop de vérifications, réessaie dans un moment':c?'vérification impossible (erreur '+c+' du site)':ERR.unreachable)+'</span>');tally();});
 });};
})();
// Recherche + pagination des listes (utile au-delà de quelques éléments ; sans JavaScript, tout reste affiché).
(function(){var SIZE=10;
 document.querySelectorAll('.listbar[data-list]').forEach(function(bar){
  var list=document.getElementById(bar.dataset.list);if(!list)return;
  var cards=Array.prototype.slice.call(list.querySelectorAll(':scope>article.card'));
  var q=bar.querySelector('.lsearch'),pg=bar.querySelector('.pager'),info=bar.querySelector('.pinfo'),prev=bar.querySelector('.pprev'),next=bar.querySelector('.pnext');
  if(cards.length<=6){if(window.utvLoadAccounts)window.utvLoadAccounts(list);return;}
  bar.hidden=false;var page=0;
  var render=function(){var term=(q.value||'').trim().toLowerCase();
   var hits=cards.filter(function(c){return !term||(c.dataset.q||'').indexOf(term)>=0;});
   var pages=Math.max(1,Math.ceil(hits.length/SIZE));if(page>=pages)page=pages-1;
   cards.forEach(function(c){c.hidden=true;});
   hits.slice(page*SIZE,page*SIZE+SIZE).forEach(function(c){c.hidden=false;});
   pg.hidden=hits.length<=SIZE;info.textContent=hits.length?('Page '+(page+1)+' / '+pages+' · '+hits.length+' élément'+(hits.length>1?'s':'')):'Aucun résultat';
   prev.disabled=page===0;next.disabled=page>=pages-1;
   if(window.utvLoadAccounts)window.utvLoadAccounts(list);};
  q.addEventListener('input',function(){page=0;render();});
  prev.addEventListener('click',function(){page--;render();});
  next.addEventListener('click',function(){page++;render();});
  render();
 });
 if(window.utvLoadAccounts)window.utvLoadAccounts();
})();
// Barre latérale : la section visible est mise en avant.
(function(){var links=document.querySelectorAll('.side nav a');if(!('IntersectionObserver' in window)||!links.length)return;
 var io=new IntersectionObserver(function(es){es.forEach(function(en){if(!en.isIntersecting)return;links.forEach(function(a){a.classList.toggle('on',a.getAttribute('href')==='#'+en.target.id);});});},{rootMargin:'-30% 0px -60% 0px'});
 ['apercu','fournisseurs','appareils','compte'].forEach(function(id){var el=document.getElementById(id);if(el)io.observe(el);});})();
document.querySelectorAll('.copy-link').forEach(function(c){c.addEventListener('click',function(){
 var i=document.getElementById(c.dataset.for);i.select();
 var done=function(){c.textContent='Copié';setTimeout(function(){c.textContent='Copier';},1500);};
 if(navigator.clipboard){navigator.clipboard.writeText(i.value).then(done,function(){document.execCommand('copy');done();});}else{document.execCommand('copy');done();}
});});
${PAIR_JS}
${SCAN_JS}
</script>`;
  const xtreamCount = providers.filter((p) => xtreamCredsOf(p)).length;
  // Compte « Pro » : au moins un appareil de l'édition Pro (déclarée par l'appli, licence vérifiée à la réception).
  const proDevices = devices.filter((d) => d.edition === "pro");
  const isPro = proDevices.length > 0;
  const nextLic = proDevices.map((d) => d.license?.until).filter((u) => typeof u === "number").sort((a, b) => a - b)[0];
  return layout(`Ultra TV — ${acct.login}`, `
<div class="shell">
<aside class="side" aria-label="Navigation">
<div class="brand">${LOGO}<span>Ultra TV</span>${isPro ? `<span class="kind pro">PRO</span>` : ""}</div>
<nav>
<a href="#apercu" class="on">${ico("home")}Vue d'ensemble</a>
<a href="#fournisseurs">${ico("list")}Fournisseurs</a>
<a href="#appareils">${ico("tv")}Appareils</a>
<a href="#compte">${ico("gear")}Compte</a>
${admin ? `<a href="/admin">${ico("user")}Administration</a>` : ""}
</nav>
<div class="me"><div class="login" title="Compte connecté">${ico("user")}<span>${e(acct.login)}</span></div>
<form method="post" action="/logout">${csrfInput}<button class="secondary" type="submit">Se déconnecter</button></form></div>
</aside>
<main id="main" class="content">
${msg}
<div class="hello" id="apercu"><h1>Bonjour ${e(acct.login)}</h1><p class="sub">${isPro
    ? `Compte <strong>Ultra TV Pro</strong> : ${proDevices.length} appareil${proDevices.length > 1 ? "s" : ""} sous licence revendeur. Sources, appareils, abonnements et licences.`
    : "Ta configuration Ultra TV : sources, appareils et abonnements."}</p></div>
<div class="tiles">
<div class="tile hero"><b>${providers.length}</b><span>fournisseur${providers.length > 1 ? "s" : ""}</span></div>
<div class="tile"><b>${devices.length}</b><span>appareil${devices.length > 1 ? "s" : ""} appairé${devices.length > 1 ? "s" : ""}</span></div>
${isPro ? `<div class="tile${nextLic && nextLic < Date.now() ? " bad" : nextLic && nextLic - Date.now() < 15 * 86_400_000 ? " warn" : ""}"><b>${nextLic ? e(frDate(nextLic)) : "—"}</b><span>${nextLic ? "prochaine échéance de licence Pro" : "licence Pro : en attente de l'appareil"}</span></div>` : ""}
<div class="tile" id="sub-tile"><b id="sub-n">${xtreamCount ? "…" : "—"}</b><span id="sub-l">${xtreamCount ? "abonnements : vérification" : "aucun abonnement Xtream"}</span></div>
</div>

<div class="two">
<section class="panel" aria-labelledby="h-pair"><h2 id="h-pair">Appairer un appareil</h2><p class="sub">Relie une TV à ton compte en quelques secondes.</p>
<ol class="steps"><li>Ouvre Ultra TV sur ta TV.</li><li>Va dans Réglages, puis Synchronisation cloud.</li><li>Saisis ici le code à 8 caractères affiché à l'écran.</li></ol>
${pairForm(csrfInput)}</section>
<section class="panel" aria-labelledby="h-add"><h2 id="h-add">Ajouter un fournisseur</h2>
<div class="tabbed"><input type="radio" name="tab" id="t-xtream" checked aria-label="Xtream Codes"/><input type="radio" name="tab" id="t-m3u" aria-label="Playlist M3U"/><div class="seg" aria-hidden="true"><label for="t-xtream">Xtream Codes</label><label for="t-m3u">Playlist M3U</label></div>
<form method="post" action="/providers" id="form-xtream">${csrfInput}<input type="hidden" name="kind" value="XTREAM"/>
<label for="xn">Nom <span class="hint">(facultatif)</span></label><input id="xn" name="name" maxlength="64" placeholder="Mon fournisseur"/>
<label for="xu">URL du serveur</label><input id="xu" name="url" type="url" required maxlength="2048" placeholder="http://provider.com:8080" autocapitalize="none" spellcheck="false"/>
<label for="xl">Utilisateur</label><input id="xl" name="username" required maxlength="256" autocomplete="off" autocapitalize="none" spellcheck="false"/>
<label for="xp">Mot de passe</label><input id="xp" name="password" type="password" required maxlength="256" autocomplete="off"/>
<div class="row"><button type="submit">${ico("plus")}Ajouter</button></div></form>
<form method="post" action="/providers" id="form-m3u">${csrfInput}<input type="hidden" name="kind" value="M3U"/>
<label for="mn">Nom <span class="hint">(facultatif)</span></label><input id="mn" name="name" maxlength="64" placeholder="Ma playlist"/>
<label for="mu">URL de la playlist</label><input id="mu" name="url" type="url" required maxlength="2048" placeholder="https://exemple.com/liste.m3u" autocapitalize="none" spellcheck="false"/>
<div class="row"><button type="submit">${ico("plus")}Ajouter</button></div></form></div>
<p class="secure">${ico("lock")}<span>Les identifiants sont chiffrés au repos et ne sont plus jamais réaffichés.</span></p></section>
</div>

<div class="section-h" id="fournisseurs"><h2 id="h-src">Fournisseurs <span class="count">${providers.length}</span></h2><span class="muted small">Tes sources de chaînes, leur abonnement, et les appareils qui les reçoivent.</span></div>
<div class="listbar" data-list="prov-list" hidden><input type="search" class="lsearch" placeholder="Rechercher un fournisseur" aria-label="Rechercher un fournisseur" autocomplete="off"/><span class="pager"><button type="button" class="secondary pprev" aria-label="Page précédente">‹</button><span class="pinfo" aria-live="polite"></span><button type="button" class="secondary pnext" aria-label="Page suivante">›</button></span></div>
<div class="cards c2" id="prov-list">${provRows || `<div class="empty"><div class="badge-ico">${ico("list")}</div><strong>Aucun fournisseur</strong><span class="small">Ajoute une source Xtream Codes ou une playlist M3U ci-dessus.</span></div>`}</div>

<div class="section-h" id="appareils"><h2 id="h-dev">Appareils <span class="count">${devices.length}</span></h2><span class="muted small">Les TV qui lisent ta configuration.</span></div>
<div class="listbar" data-list="dev-list" hidden><input type="search" class="lsearch" placeholder="Rechercher un appareil" aria-label="Rechercher un appareil" autocomplete="off"/><span class="pager"><button type="button" class="secondary pprev" aria-label="Page précédente">‹</button><span class="pinfo" aria-live="polite"></span><button type="button" class="secondary pnext" aria-label="Page suivante">›</button></span></div>
<div class="cards c2" id="dev-list">${devRows || `<div class="empty"><div class="badge-ico">${ico("tv")}</div><strong>Aucun appareil</strong><span class="small">Appaire ta première TV avec le code affiché à l'écran.</span></div>`}</div>

<div class="section-h" id="compte"><h2>Compte</h2></div>
<div class="two">
${traktPanel(acct.trakt, csrfInput)}
${osPanel(acct.os, csrfInput)}
<section class="panel" aria-labelledby="h-pw"><h2 id="h-pw">Mot de passe</h2>
<form method="post" action="/password">${csrfInput}
<label for="cur">Mot de passe actuel</label><input id="cur" name="current" type="password" required autocomplete="current-password"/>
<label for="npw">Nouveau mot de passe <span class="hint">(10 caractères minimum)</span></label><input id="npw" name="password" type="password" required minlength="10" maxlength="200" autocomplete="new-password"/>
<div class="row"><button type="submit">Mettre à jour</button></div></form></section>
<section class="panel danger-zone" aria-labelledby="h-dz"><h2 id="h-dz">Zone dangereuse</h2><p class="muted small mt6">Supprime le compte, ses fournisseurs et révoque tous ses appareils.</p>
<form method="post" action="/account/delete" data-confirm="Supprimer définitivement ce compte ?">${csrfInput}
<label for="dpw">Mot de passe</label><input id="dpw" name="password" type="password" required autocomplete="current-password"/>
<div class="row"><button class="danger" type="submit">Supprimer mon compte</button></div></form></section>
</div>
</main></div>`, n, script, true);
}

/** Trakt (facultatif) : connexion OAuth depuis l'espace client ; les appareils n'ont rien à configurer. */
function traktPanel(t, csrfInput) {
  const linked = t && !t.revoked;
  return `<section class="panel" aria-labelledby="h-trakt"><h2 id="h-trakt">Trakt <span class="hint">(facultatif)</span></h2>
${linked ? `<p class="mt6">Connecté${t.user ? ` au compte <strong>${e(t.user)}</strong>` : ""}.</p>
<p class="muted small mt6">Films et épisodes regardés sur tes appareils sont envoyés à Trakt (en cours, puis vu à la fin). Rien à régler sur la TV.</p>
<form method="post" action="/trakt/disconnect">${csrfInput}<div class="row"><button class="secondary" type="submit">Déconnecter Trakt</button></div></form>`
    : `<p class="muted small mt6">${t && t.revoked ? "L'accès a été retiré chez Trakt : reconnecte-toi pour reprendre le suivi. " : ""}Relie ton compte Trakt : ce que tu regardes sur tes appareils (films, épisodes) y est enregistré automatiquement, sans rien régler sur la TV.</p>
<form method="post" action="/trakt/connect">${csrfInput}<div class="row"><button type="submit">Connecter Trakt</button></div></form>
<p class="secure">${ico("lock")}<span>Connexion faite chez Trakt : ton mot de passe Trakt ne passe jamais par ce site.</span></p>`}</section>`;
}

/**
 * Compte OpenSubtitles du client (facultatif) : sans lui, les sous-titres passent par la clé commune (quota partagé) ;
 * avec lui, par SON quota. Le mot de passe n'est jamais réaffiché.
 */
function osPanel(os, csrfInput) {
  const quota = (o) => [o.vip ? "VIP" : o.level ? e(o.level) : "", o.allowed != null ? `${e(o.allowed)} téléchargements par jour` : ""].filter(Boolean).join(" · ");
  return `<section class="panel" aria-labelledby="h-os"><h2 id="h-os">Sous-titres · OpenSubtitles <span class="hint">(facultatif)</span></h2>
${os ? `<p class="mt6">Relié au compte <strong>${e(os.user)}</strong>${quota(os) ? ` · ${quota(os)}` : ""}.</p>
<p class="muted small mt6">Les sous-titres téléchargés par tes appareils sont décomptés sur ce compte, pas sur le quota commun.</p>
<form method="post" action="/subtitles/unlink">${csrfInput}<div class="row"><button class="secondary" type="submit">Délier ce compte</button></div></form>`
    : `<p class="muted small mt6">Les sous-titres fonctionnent déjà sur tes appareils, avec un quota commun limité. Relie ton compte OpenSubtitles (gratuit ou VIP) pour utiliser ton propre quota. Pas de compte ? <a href="https://www.opensubtitles.com/fr/users/sign_up" target="_blank" rel="noopener noreferrer">En créer un</a>.</p>
<form method="post" action="/subtitles/link">${csrfInput}
<label for="osu">Identifiant OpenSubtitles</label><input id="osu" name="os_user" required maxlength="100" autocomplete="off"/>
<label for="osp">Mot de passe OpenSubtitles</label><input id="osp" name="os_pass" type="password" required maxlength="200" autocomplete="off"/>
<div class="row"><button type="submit">Relier</button></div></form>
<p class="secure">${ico("lock")}<span>Chiffré au repos, jamais réaffiché ni envoyé aux appareils.</span></p>`}</section>`;
}

const fmtTime = (ts) => (ts ? new Date(ts).toISOString().replace("T", " ").slice(0, 19) : "");

/** Drapeau + nom du pays (code ISO à 2 lettres), ou « — ». */
function countryLabel(code) {
  if (!code || !/^[A-Z]{2}$/.test(code)) return "—";
  const flag = String.fromCodePoint(...[...code].map((c) => 0x1f1e6 + c.charCodeAt(0) - 65));
  let name = code;
  try { name = new Intl.DisplayNames(["fr"], { type: "region" }).of(code) || code; } catch { /* ICU absent */ }
  return `${flag} ${name}`;
}

/** Administration : comptes, appareils, pays (aucun secret). Réservée aux identifiants de ADMIN_LOGINS. */
export function adminPage(n, { rows, me, now = Date.now() }) {
  const d = (t) => (t ? new Date(t).toISOString().slice(0, 16).replace("T", " ") : "—");
  const signed = rows.filter((r) => !r.legacy);
  const withDev = rows.filter((r) => r.devices.length);
  const active7 = rows.filter((r) => r.lastSeen && now - r.lastSeen < 7 * 86400_000);
  const byCountry = new Map();
  for (const r of rows) {
    const cs = new Set([r.country, ...r.devices.map((x) => x.country)].filter(Boolean));
    if (!cs.size) continue;
    for (const c of cs) byCountry.set(c, (byCountry.get(c) || 0) + 1);
  }
  const countries = [...byCountry].sort((a, b) => b[1] - a[1]).map(([c, k]) => `<span class="chip">${e(countryLabel(c))} · ${k}</span>`).join(" ");
  const row = (r) => {
    const cs = [...new Set([r.country, ...r.devices.map((x) => x.country)].filter(Boolean))];
    const devs = r.devices.map((x) => `${e(x.name)} <span class="muted">(${e(x.model)}${x.edition === "pro" ? ", PRO" : ""}${x.lastSeen ? `, vu ${e(d(x.lastSeen))}` : ""})</span>`).join("<br>");
    return `<tr data-legacy="${r.legacy ? 1 : 0}"><td><strong>${e(r.login)}</strong>${r.login === me ? ` <span class="kind pro">ADMIN</span>` : ""}</td>
<td>${r.legacy ? "ancien (MAC)" : "inscrit"}</td><td>${e(d(r.createdAt))}</td><td>${e(d(r.lastLoginAt))}</td><td>${e(d(r.lastSeen))}</td>
<td>${cs.length ? cs.map((c) => e(countryLabel(c))).join("<br>") : "—"}</td><td>${devs || "—"}</td><td>${r.sources ? "oui" : "non"}</td><td>${e(r.trakt || "—")}</td><td>${e(r.opensubtitles || "—")}</td></tr>`;
  };
  const script = `document.getElementById('legacy').addEventListener('change',function(){var on=this.checked;document.querySelectorAll('tr[data-legacy="1"]').forEach(function(t){t.hidden=!on;});});
document.querySelectorAll('tr[data-legacy="1"]').forEach(function(t){t.hidden=true;});`;
  return layout("Ultra TV — administration", `<main id="main" class="layout"><header class="topbar"><div class="brand">${LOGO}<span>Administration</span></div><a href="/">Retour au tableau de bord</a></header>
<div class="tiles"><div class="tile"><b>${signed.length}</b><span>comptes inscrits</span></div>
<div class="tile"><b>${rows.length - signed.length}</b><span>anciens comptes (adresse MAC)</span></div>
<div class="tile"><b>${withDev.length}</b><span>comptes avec un appareil</span></div>
<div class="tile"><b>${active7.length}</b><span>actifs ces 7 jours</span></div></div>
<p class="mt16"><strong>Pays</strong> ${countries || `<span class="muted">aucun encore : relevé depuis le 9 octobre 2026, à l'inscription, à la connexion et à l'activité des appareils.</span>`}</p>
<p class="muted small">Aucun mot de passe, aucune source IPTV ni jeton n'apparaît ici. Le pays vient de Cloudflare ; l'adresse IP n'est jamais gardée.</p>
<div class="row mt16"><a class="button" href="/admin/comptes.csv">Exporter en CSV</a> <label class="small"><input type="checkbox" id="legacy"/> Afficher les anciens comptes (adresse MAC)</label></div>
<div class="tablewrap mt16"><table><thead><tr><th>Compte</th><th>Type</th><th>Créé le</th><th>Dernière connexion</th><th>Dernière activité</th><th>Pays</th><th>Appareils</th><th>Sources</th><th>Trakt</th><th>OpenSubtitles</th></tr></thead>
<tbody>${rows.map(row).join("")}</tbody></table></div></main>`, n, `<script nonce="${n}">${script}</script>`);
}

export function eventsPage(n, items) {
  const rows = items.map((it) => {
    const lvl = String(it.level || "info").toLowerCase();
    return `<tr class="lvl-${e(lvl)}"><td>${e(fmtTime(it.ts))}</td><td class="level">${e(lvl.toUpperCase())}</td><td>${e(it.tag || "")}</td><td>${e(it.message || "")}</td><td>${e(it.device || "")}</td><td>${e(it.version || "")} (${e(it.versionCode ?? "?")})</td><td>${e(it.deviceId || "")}</td></tr>`;
  }).join("");
  return layout("Ultra TV — journaux", `<main id="main" class="layout"><header class="topbar"><div class="brand">${LOGO}<span>Journaux — ${items.length}</span></div></header><p class="sub mb16">Fenêtre glissante de 7 jours. Messages nettoyés côté serveur.</p>
<div class="tablewrap"><table><thead><tr><th>Date</th><th>Niveau</th><th>Tag</th><th>Message</th><th>Appareil</th><th>Version</th><th>ID</th></tr></thead><tbody>${rows}</tbody></table></div>
${rows ? "" : `<div class="empty mt16"><strong>Aucun événement</strong></div>`}</main>`, n);
}

export function crashesPage(n, items) {
  const rows = items.map((it) => `<div class="panel"><div><strong>${e(it.version || "?")} (${e(it.versionCode ?? "?")})</strong> · ${e(it.device || "")} · SDK ${e(it.androidSdk ?? "?")} · ${e(fmtTime(it.ts))} · ${e(it.deviceId || "")}</div><pre>${e(it.stack || "")}</pre></div>`).join("");
  return layout("Ultra TV — crashs", `<main id="main" class="layout"><header class="topbar"><div class="brand">${LOGO}<span>Crashs — ${items.length}</span></div></header><p class="sub mb16">Fenêtre glissante de 30 jours.</p>${rows || `<div class="empty"><strong>Aucun crash</strong></div>`}</main>`, n);
}
