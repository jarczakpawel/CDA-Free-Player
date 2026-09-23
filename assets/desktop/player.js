const CFG=window.__CDAFP_CONFIG__;
const root=document.getElementById('root');
const video=document.getElementById('video');
const loading=document.getElementById('loading');
const errorBox=document.getElementById('error');
const seek=document.getElementById('seek');
const play=document.getElementById('play');
const timeBox=document.getElementById('time');
const volume=document.getElementById('volume');
const mute=document.getElementById('mute');
const quality=document.getElementById('quality');
const full=document.getElementById('full');
const back=document.getElementById('back');
const description=document.getElementById('description');
const comments=document.getElementById('comments');
const contentOverlay=document.getElementById('contentOverlay');
const contentTitle=document.getElementById('contentTitle');
const contentBody=document.getElementById('contentBody');
const contentClose=document.getElementById('contentClose');
document.getElementById('title').textContent=CFG.title;
document.title=CFG.title?`CDA Free Player - ${CFG.title}`:'CDA Free Player';
let lastAudibleVolume=.8;
try{
  const saved=Number(localStorage.getItem('cdafp-volume'));
  const last=Number(localStorage.getItem('cdafp-last-volume'));
  if(Number.isFinite(saved)&&saved>=0&&saved<=1){video.volume=saved;volume.value=saved;}
  if(Number.isFinite(last)&&last>0&&last<=1)lastAudibleVolume=last;
  else if(video.volume>0)lastAudibleVolume=video.volume;
}catch(_){}
window.__cdafp={
  ready:false,error:'',quality:'auto',closeRequested:false,
  fullscreen:false,keyboardLock:false,keyboardLockError:'',contentRequest:'',contentBusy:false
};
let player=null;
let dragging=false;
let startApplied=CFG.start<=5;
let hideTimer=null;
let modalOpen=false;
function fmt(v){if(!Number.isFinite(v)||v<0)return'0:00';v=Math.floor(v);const h=Math.floor(v/3600),m=Math.floor((v%3600)/60),s=v%60;return h?`${h}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`:`${m}:${String(s).padStart(2,'0')}`;}
function fail(e){let t='Błąd odtwarzania';try{if(typeof e==='string')t=e;else t=JSON.stringify(e);}catch(_){t=String(e);}window.__cdafp.error=t.slice(0,800);errorBox.textContent=window.__cdafp.error;errorBox.style.display='block';loading.classList.remove('show');}
function showControls(){root.classList.add('active');clearTimeout(hideTimer);if(!modalOpen)hideTimer=setTimeout(()=>{if(!video.paused)root.classList.remove('active');},2500);}
function update(){const d=video.duration,c=video.currentTime;if(!dragging&&Number.isFinite(d)&&d>0)seek.value=Math.max(0,Math.min(1000,c/d*1000));timeBox.textContent=`${fmt(c)} / ${fmt(d)}`;play.textContent=video.paused?'▶':'❚❚';root.classList.toggle('paused',video.paused);mute.textContent=video.muted||video.volume===0?'🔇':'🔊';}
function saveVolume(){try{localStorage.setItem('cdafp-volume',String(video.volume));if(video.volume>0)localStorage.setItem('cdafp-last-volume',String(video.volume));}catch(_){} }
function setVolume(v){v=Math.max(0,Math.min(1,Number(v)||0));video.volume=v;volume.value=v;if(v>0){lastAudibleVolume=v;video.muted=false;}saveVolume();update();}
function toggleMute(){if(video.muted||video.volume===0){if(video.volume===0)setVolume(lastAudibleVolume>0?lastAudibleVolume:.8);video.muted=false;}else{if(video.volume>0){lastAudibleVolume=video.volume;try{localStorage.setItem('cdafp-last-volume',String(lastAudibleVolume));}catch(_){}}video.muted=true;}update();showControls();}
function requestClose(){if(window.__cdafp.closeRequested)return;window.__cdafp.closeRequested=true;try{if(navigator.keyboard&&navigator.keyboard.unlock)navigator.keyboard.unlock();}catch(_){}try{video.pause();}catch(_){} }
async function lockEscape(){window.__cdafp.keyboardLock=false;window.__cdafp.keyboardLockError='';try{if(navigator.keyboard&&navigator.keyboard.lock){await navigator.keyboard.lock(['Escape']);window.__cdafp.keyboardLock=true;}}catch(e){window.__cdafp.keyboardLockError=String(e&&e.message?e.message:e);}}
function unlockEscape(){try{if(navigator.keyboard&&navigator.keyboard.unlock)navigator.keyboard.unlock();}catch(_){}window.__cdafp.keyboardLock=false;}
async function enterFullscreen(){try{if(!document.fullscreenElement)await document.documentElement.requestFullscreen();await lockEscape();}catch(_){} }
async function exitFullscreen(){unlockEscape();try{if(document.fullscreenElement)await document.exitFullscreen();}catch(_){} }
function toggleFullscreen(){if(document.fullscreenElement)exitFullscreen();else enterFullscreen();}
function applyStart(){const d=video.duration;if(!startApplied&&Number.isFinite(d)&&d>CFG.start+1){try{video.currentTime=CFG.start;startApplied=true;}catch(_){} }}
function refreshQualities(){if(!player)return;let list=[];try{list=player.getBitrateInfoListFor('video')||[];}catch(_){}quality.innerHTML='<option value="auto">Auto</option>';for(const q of list){const o=document.createElement('option');o.value=String(q.qualityIndex);o.textContent=q.height?`${q.height}p`:`${Math.round((q.bitrate||0)/1000)} kb/s`;quality.appendChild(o);}}
function openContent(title,html){modalOpen=true;clearTimeout(hideTimer);root.classList.add('active','modal-open');contentTitle.textContent=title;contentBody.innerHTML=html;contentOverlay.classList.add('show');contentClose.focus();}
function closeContent(){modalOpen=false;contentOverlay.classList.remove('show');root.classList.remove('modal-open');showControls();}
function handleBack(){if(modalOpen)closeContent();else requestClose();}
function esc(s){return String(s==null?'':s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
function ratedTitle(base){let out=base;const r=Number(CFG.rating);if(Number.isFinite(r)&&r>0){out+=`   ★ ${r.toFixed(1)} / 5`;const v=Number(CFG.cdaVotes);if(Number.isFinite(v)&&v>0)out+=` • ${v} ocen`;}return out;}
function renderDescription(text){const value=String(text||'').trim();openContent(ratedTitle('Opis'),value?`<div class="descriptionText">${esc(value).replace(/\n/g,'<br>')}</div>`:'<div class="emptyText">Brak opisu.</div>');}
function renderComments(items){const list=Array.isArray(items)?items:[];const heading=ratedTitle('Komentarze');if(!list.length){openContent(heading,'<div class="emptyText">Brak komentarzy.</div>');return;}let html='<div class="commentsList">';for(const c of list){const meta=[c.author||'anonim',c.date||'',c.rate||''].filter(Boolean).map(esc).join(' • ');html+=`<article class="comment"><div class="commentMeta">${meta}</div><div class="commentText">${esc(c.text||'').replace(/\n/g,'<br>')}</div></article>`;}html+='</div>';openContent(heading,html);}
function showLoading(title,text){window.__cdafp.contentBusy=true;openContent(title,`<div class="contentLoading"><span class="smallSpinner"></span><span>${esc(text)}</span></div>`);}
function requestContent(kind){if(window.__cdafp.contentRequest||window.__cdafp.contentBusy)return;window.__cdafp.contentRequest=kind;if(kind==='description')showLoading('Opis','Wczytywanie opisu…');else showLoading('Komentarze','Wczytywanie komentarzy…');}
window.__cdafpDeliverContent=function(kind,payload){window.__cdafp.contentBusy=false;payload=payload||{};if(payload.rating!==undefined&&payload.rating!==null&&payload.rating!=='')CFG.rating=payload.rating;if(payload.cdaVotes!==undefined&&payload.cdaVotes!==null)CFG.cdaVotes=payload.cdaVotes;if(!payload.ok){openContent(kind==='description'?'Opis':'Komentarze',`<div class="contentError">${esc(payload.error||'Nie udało się wczytać danych.')}</div>`);return;}if(kind==='description')renderDescription(payload.description||'');else renderComments(payload.comments||[]);};
play.onclick=()=>{if(video.paused)video.play().catch(fail);else video.pause();showControls();};
back.onclick=handleBack;
mute.onclick=toggleMute;
description.onclick=()=>{if(CFG.descriptionReady)renderDescription(CFG.description);else requestContent('description');};
comments.onclick=()=>{if(CFG.commentsReady)renderComments(CFG.comments||[]);else requestContent('comments');};
contentClose.onclick=closeContent;
contentOverlay.addEventListener('mousedown',e=>{if(e.target===contentOverlay)closeContent();});
volume.oninput=()=>{setVolume(volume.value);showControls();};
seek.onpointerdown=()=>{dragging=true;showControls();};
seek.oninput=()=>{const d=video.duration;if(Number.isFinite(d)&&d>0)timeBox.textContent=`${fmt(Number(seek.value)/1000*d)} / ${fmt(d)}`;};
seek.onchange=()=>{const d=video.duration;if(Number.isFinite(d)&&d>0)video.currentTime=Number(seek.value)/1000*d;dragging=false;showControls();};
full.onclick=toggleFullscreen;
quality.onchange=()=>{if(!player)return;try{if(quality.value==='auto'){player.setAutoSwitchQualityFor('video',true);window.__cdafp.quality='auto';}else{player.setAutoSwitchQualityFor('video',false);player.setQualityFor('video',Number(quality.value));window.__cdafp.quality=quality.options[quality.selectedIndex].text;}}catch(e){fail(e);}showControls();};
video.addEventListener('loadedmetadata',()=>{applyStart();update();});
video.addEventListener('durationchange',()=>{applyStart();update();});
video.addEventListener('timeupdate',update);
video.addEventListener('playing',()=>{window.__cdafp.ready=true;loading.classList.remove('show');update();showControls();});
video.addEventListener('waiting',()=>loading.classList.add('show'));
video.addEventListener('canplay',()=>{applyStart();loading.classList.remove('show');});
video.addEventListener('pause',update);
video.addEventListener('volumechange',update);
video.addEventListener('error',()=>fail(video.error?`Media error ${video.error.code}`:'Media error'));
root.addEventListener('mousemove',showControls);
root.addEventListener('mousedown',showControls);
video.addEventListener('click',()=>{if(video.paused)video.play().catch(fail);else video.pause();showControls();});
root.addEventListener('dblclick',e=>{if(!contentOverlay.contains(e.target))toggleFullscreen();});
document.addEventListener('fullscreenchange',()=>{const active=!!document.fullscreenElement;window.__cdafp.fullscreen=active;if(!active)unlockEscape();});
function handleKey(e){const key=e.key||'';const code=e.code||'';if(key==='Escape'||key==='Backspace'||key==='BrowserBack'||key==='GoBack'||code==='BrowserBack'){e.preventDefault();e.stopImmediatePropagation();handleBack();return;}if(e.altKey&&key==='ArrowLeft'){e.preventDefault();e.stopImmediatePropagation();handleBack();return;}if(modalOpen){if(key==='ArrowDown'){contentBody.scrollBy({top:90,behavior:'smooth'});e.preventDefault();}else if(key==='ArrowUp'){contentBody.scrollBy({top:-90,behavior:'smooth'});e.preventDefault();}else if(key==='PageDown'||key===' '){contentBody.scrollBy({top:Math.max(240,contentBody.clientHeight*.75),behavior:'smooth'});e.preventDefault();}else if(key==='PageUp'){contentBody.scrollBy({top:-Math.max(240,contentBody.clientHeight*.75),behavior:'smooth'});e.preventDefault();}else if(key==='Home'){contentBody.scrollTo({top:0,behavior:'smooth'});e.preventDefault();}else if(key==='End'){contentBody.scrollTo({top:contentBody.scrollHeight,behavior:'smooth'});e.preventDefault();}return;}const jump=e.shiftKey?30:10;if(key==='ArrowRight'){video.currentTime=Math.min(video.duration||Infinity,video.currentTime+jump);e.preventDefault();}else if(key==='ArrowLeft'){video.currentTime=Math.max(0,video.currentTime-jump);e.preventDefault();}else if(key==='Home'){video.currentTime=0;e.preventDefault();}else if(key==='End'&&Number.isFinite(video.duration)){video.currentTime=Math.max(0,video.duration-.25);e.preventDefault();}else if(key===' '||key==='k'||key==='K'){play.click();e.preventDefault();}else if(key==='f'||key==='F'){toggleFullscreen();e.preventDefault();}else if(key==='m'||key==='M'){toggleMute();e.preventDefault();}else if(key==='ArrowUp'){setVolume(video.volume+.05);e.preventDefault();}else if(key==='ArrowDown'){setVolume(video.volume-.05);e.preventDefault();}showControls();}
window.addEventListener('keydown',handleKey,true);
window.addEventListener('mouseup',e=>{if(e.button===3){e.preventDefault();handleBack();}},true);
try{history.replaceState({cdafp:true},'',location.href);history.pushState({cdafp:true},'',location.href+'#player');window.addEventListener('popstate',handleBack);}catch(_){}
try{
  if(!window.dashjs)throw new Error('Nie udało się załadować silnika DASH.');
  player=dashjs.MediaPlayer().create();
  if(CFG.license){const widevine={serverURL:CFG.license};if(CFG.drmHeader)widevine.httpRequestHeaders={'x-dt-custom-data':CFG.drmHeader};player.setProtectionData({'com.widevine.alpha':widevine});}
  player.updateSettings({streaming:{abr:{autoSwitchBitrate:{video:true}}}});
  player.on(dashjs.MediaPlayer.events.STREAM_INITIALIZED,()=>{refreshQualities();applyStart();});
  player.on(dashjs.MediaPlayer.events.ERROR,e=>fail(e));
  player.initialize(video,CFG.manifest,true);
}catch(e){fail(e&&e.message?e.message:e);}
setInterval(update,250);
showControls();
