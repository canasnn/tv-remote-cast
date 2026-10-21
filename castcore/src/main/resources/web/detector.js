/* Ruterika media detector -> window.RuterikaBridge.post(json). ES2017. */
(() => {
'use strict';
var W = window, D = document;
if (W.__ruterikaDetector) return;
W.__ruterikaDetector = 1;
var TOP = W.top === W, sent = {}, queue = [], timer = 0, mse = false, mseSent = false;
function abs(u) { try { return u ? new URL(String(u), D.baseURI).href : ''; } catch (e) { return ''; } }
function qa(s) { return D.querySelectorAll(s); }
function num(v) { return v > 0 && isFinite(v) ? Math.round(v) : null; }
function meta(n) { var m = D.querySelector('meta[property="' + n + '"],meta[name="' + n + '"]'); return (m && m.content) || null; }
function post(msg) {
  var b = W.RuterikaBridge;
  if (b && typeof b.post === 'function') { try { b.post(JSON.stringify(msg)); } catch (e) {} }
}
function toFrames(m) { for (var i = 0; i < W.frames.length; i++) { try { W.frames[i].postMessage(m, '*'); } catch (e) {} } }
function flush() {
  timer = 0;
  if (!queue.length && (!mse || mseSent)) return;
  var items = queue; queue = []; mseSent = mse;
  post({ type: 'media', items: items, pageTitle: D.title || '', pageUrl: location.href, top: TOP, mse: mse,
    ogTitle: meta('og:title') || meta('twitter:title'), ogImage: abs(meta('og:image') || meta('twitter:image')) || null });
}
function later() { if (!timer) timer = setTimeout(flush, 300); }
function add(it) {
  var u = it && it.url;
  if (!u || typeof u !== 'string') return;
  if (/^(blob|mediasource):/i.test(u)) { mse = true; later(); return; }
  // TCPlayer webrtc:// -> HLS guess, kept only if it loads as a playlist
  if (/^webrtc:/i.test(u)) { u = u.replace(/^webrtc:/i, 'https:').replace(/(\.\w{2,4})?(\?|$)/, '.m3u8$2'); it.kind = 'hls'; it.mime = null; it.validate = true; }
  u = abs(u);
  if (!/^https?:/i.test(u)) return;
  it.url = u;
  var sig = JSON.stringify(it);
  if (sent[u] === sig) return;
  sent[u] = sig; queue.push(it); later();
}
function kindOf(t) {
  t = String(t || '').toLowerCase();
  return /m3u8|mpegurl|hls/.test(t) ? 'hls' : /dash|mpd/.test(t) ? 'dash' : /^audio|\.(mp3|m4a|aac|ogg|flac)(\?|$)/.test(t) ? 'audio' : 'video';
}
function pc(u, type, title, poster, subs, dur) {
  if (typeof u !== 'string' || !u) return;
  add({ url: u, kind: kindOf(type || u), mime: type || null, title: title || null, poster: poster ? abs(poster) : null,
    durationMs: dur || null, src: 'player', subtitles: subs || [] });
}
function tracks(el) {
  var out = [];
  el.querySelectorAll('track').forEach((t) => {
    var k = (t.kind || 'subtitles').toLowerCase(), s = abs(t.src || t.getAttribute('src'));
    if ((k === 'subtitles' || k === 'captions') && s) out.push({ url: s, lang: t.srclang || null, label: t.label || null });
  });
  return out;
}
function media(el) {
  var d = el.duration, live = d === Infinity;
  var base = { kind: el.tagName === 'AUDIO' ? 'audio' : 'video', src: 'tag',
    title: el.title || el.getAttribute('aria-label') || null, poster: el.poster ? abs(el.poster) : null,
    durationMs: live ? null : num(d * 1000), positionMs: live ? null : num(el.currentTime * 1000),
    width: num(el.videoWidth), height: num(el.videoHeight), live: live, subtitles: tracks(el) };
  var list = [], seen = {};
  el.querySelectorAll('source').forEach((s) => { list.push([s.src || s.getAttribute('data-src'), s.type]); });
  el.querySelectorAll('a[href]').forEach((a) => { if (/\.(mp4|m3u8|webm|mkv|mov|mpd|mp3)(\?|$)/i.test(a.href) && !/videojs\.com/.test(a.href)) list.push([a.href]); });
  list.push([el.currentSrc], [el.src], [el.getAttribute('data-src')]);
  if (el.plyr && typeof el.plyr.source === 'string') list.push([el.plyr.source]);
  list.forEach((p) => {
    var u = p[0];
    if (!u || seen[u]) return;
    seen[u] = 1;
    add(Object.assign({}, base, { url: u, mime: p[1] || null }));
  });
}
function iso(s) {
  var m = /^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?$/i.exec(s || '');
  return m ? Math.round((((+m[1] || 0) * 24 + (+m[2] || 0)) * 3600 + (+m[3] || 0) * 60 + (+m[4] || 0)) * 1000) || null : null;
}
function walk(o, depth) {
  if (!o || typeof o !== 'object' || depth > 6) return;
  if (Array.isArray(o)) { o.forEach((x) => { walk(x, depth + 1); }); return; }
  if (/VideoObject|AudioObject/.test([].concat(o['@type'] || []).join(' ')) && typeof o.contentUrl === 'string') {
    pc(o.contentUrl, o.encodingFormat, o.name, [].concat(o.thumbnailUrl || [])[0], null, iso(o.duration));
  }
  Object.keys(o).forEach((k) => { if (k !== '@context' && o[k] && typeof o[k] === 'object') walk(o[k], depth + 1); });
}
// JW Player, video.js, Clappr, Flowplayer, og:video meta, JSON-LD (Plyr: el.plyr in media())
function players() {
  try {
    var j = typeof W.jwplayer === 'function' && W.jwplayer(), pl = j && j.getPlaylist && j.getPlaylist();
    (pl || []).forEach((it) => {
      var subs = (it.tracks || []).filter((t) => t.file && /captions|subtitles/.test(t.kind || 'captions'))
        .map((t) => ({ url: abs(t.file), label: t.label || null, lang: t.language || null }));
      (it.sources && it.sources.length ? it.sources : [it]).forEach((s) => { pc(s.file, s.type, it.title, it.image, subs); });
    });
  } catch (e) {}
  try {
    var vj = W.videojs, ps = vj && (vj.getPlayers ? vj.getPlayers() : vj.players);
    if (ps) Object.keys(ps).forEach((k) => {
      var p = ps[k];
      if (p && p.currentSrc) pc(p.currentSrc(), p.currentType && p.currentType(), null, p.poster && p.poster());
    });
  } catch (e) {}
  try {
    if (W.Clappr) ['player', 'clapprPlayer', 'clappr'].forEach((n) => {
      var o = W[n] && W[n].options;
      if (o) [].concat(o.source || o.sources || []).forEach((s) => {
        pc(typeof s === 'string' ? s : s && s.source, s && s.mimeType, null, o.poster);
      });
    });
  } catch (e) {}
  try {
    var fp = W.flowplayer;
    ((fp && fp.instances) || []).forEach((p) => {
      var v = p.video || {};
      pc(v.src || v.url, v.type, v.title);
      ((p.conf && p.conf.clip && p.conf.clip.sources) || []).forEach((s) => { pc(s.src, s.type); });
    });
  } catch (e) {}
  try {
    var type = meta('og:video:type'), title = meta('og:title'), img = meta('og:image');
    if (!/html|flash/i.test(type || '')) {
      qa('meta[property^="og:video"],meta[name="twitter:player:stream"]').forEach((m) => {
        var p = (m.getAttribute('property') || m.getAttribute('name') || '').toLowerCase();
        if (/^og:video(:url|:secure_url)?$|^twitter:player:stream$/.test(p)) pc(m.content, type, title, img);
      });
    }
  } catch (e) {}
  try {
    qa('script[type="application/ld+json"]').forEach((s) => {
      try { walk(JSON.parse(s.textContent), 0); } catch (e) {}
    });
  } catch (e) {}
}
// ok.ru/vk OneVideoPlayer, Facebook, RAI meta, #videoShow, urlVideo, Vimeo playerConfig
function extra() {
  qa('[data-options]').forEach((e) => {
    try {
      var o = JSON.parse(e.getAttribute('data-options')), md = (o.flashvars || o).metadata;
      if (typeof md === 'string') md = JSON.parse(md);
      if (!md) return;
      var mv = md.movie || {};
      pc(md.hlsManifestUrl || md.hlsMasterPlaylistUrl, 'hls', mv.title, mv.poster);
      (md.videos || []).forEach((v) => { pc(v.url, 'video/mp4', mv.title, mv.poster); });
    } catch (x) {}
  });
  qa('[data-store]').forEach((e) => { try { var s = JSON.parse(e.getAttribute('data-store')); pc(s.src || s.videoURL, 'video/mp4'); } catch (x) {} });
  if (/(^|\.)(facebook\.com|fb\.watch)$/.test(location.hostname)) {
    qa('script:not([src])').forEach((s) => {
      var t = s.textContent, re = /"(playable_url(?:_quality_hd)?|[hs]d_src(?:_no_ratelimit)?|browser_native_[hs]d_url)":"((?:[^"\\]|\\.)+)"/g, m;
      if (t.length > 4e6 || !/playable_url|d_src|native_/.test(t)) return;
      while ((m = re.exec(t))) { try { pc(JSON.parse('"' + m[2] + '"'), 'video/mp4'); } catch (x) {} }
    });
  }
  ['videourl_mp4', 'videourl_h264', 'videourl_m3u8', 'videourl'].forEach((n) => { var v = meta(n); if (v) pc(v, null, meta('og:title')); });
  var vs = D.querySelector('#videoShow[data-default]');
  if (vs) pc(vs.getAttribute('data-default'));
  if (typeof W.urlVideo === 'string') pc(W.urlVideo);
  try {
    var c = W.playerConfig, f = c && c.request && c.request.files, v = (c && c.video) || {}, th = v.thumbs || {};
    if (f) {
      var po = th.base || th['1280'] || th['640'], du = num(v.duration * 1000), h = f.hls, cd = h && h.cdns;
      if (cd) pc((cd[h.default_cdn] || cd[Object.keys(cd)[0]] || {}).url, 'hls', v.title, po, null, du);
      (f.progressive || []).forEach((p) => { pc(p.url, p.mime || 'video/mp4', v.title, po, null, du); });
    }
  } catch (e) {}
}
function scan() {
  try { qa('video,audio').forEach(media); } catch (e) {}
  players();
  try { extra(); } catch (e) {}
}
try {
  var oc = URL.createObjectURL;
  URL.createObjectURL = function (o) {
    if ((W.MediaSource && o instanceof W.MediaSource) || (W.ManagedMediaSource && o instanceof W.ManagedMediaSource)) { mse = true; later(); }
    return oc.apply(this, arguments);
  };
} catch (e) {}
['play', 'pause', 'seeked', 'loadedmetadata', 'loadstart', 'durationchange'].forEach((ev) => {
  D.addEventListener(ev, (e) => {
    var t = e.target;
    if (t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO')) {
      media(t);
      if (ev === 'play') {
        players();
        var b = W.RuterikaBridge, u = t.currentSrc || t.src || '';
        if (b && typeof b.playing === 'function') { try { b.playing(u.indexOf('blob:') === 0 ? '' : abs(u)); } catch (x) {} }
      }
    }
  }, true);
});
var mt = 0;
function soon() { if (!mt) mt = setTimeout(() => { mt = 0; scan(); }, 500); }
var TAGS = /^(VIDEO|AUDIO|SOURCE|TRACK)$/;
try {
  new MutationObserver((ms) => {
    for (var i = 0; i < ms.length; i++) {
      var m = ms[i];
      if (m.type === 'attributes') { if (TAGS.test(m.target.tagName)) { soon(); return; } continue; }
      for (var k = 0; k < m.addedNodes.length; k++) {
        var n = m.addedNodes[k];
        if (n.nodeType === 1 && (TAGS.test(n.tagName) || (n.querySelector && n.querySelector('video,audio')))) { soon(); return; }
      }
    }
  }).observe(D, { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'data-src'] });
} catch (e) {}
// SPA navigation: the app clears the list, so report everything again
function navd() { sent = {}; mseSent = false; setTimeout(scan, 800); }
try {
  ['pushState', 'replaceState'].forEach((k) => {
    var o = history[k];
    history[k] = function () { var a = location.href.split('#')[0], r = o.apply(this, arguments); if (location.href.split('#')[0] !== a) navd(); return r; };
  });
  W.addEventListener('popstate', navd);
} catch (e) {}
W.__ruterikaPauseAll = () => {
  qa('video,audio').forEach((m) => { try { m.pause(); } catch (e) {} });
  toFrames({ __ruterika: 'pause' });
};
// 1 = every video to its end (manual), 2 = skip buttons + videos that look like ads (smart)
W.__ruterikaSkipAds = (mode) => {
  qa('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.videoAdUiSkipButton,.jw-skip,.vast-skip-button,.vjs-skip-ad,[class*="skip-ad"],[class*="skipAd"]').forEach((b) => { try { b.click(); } catch (e) {} });
  qa('video').forEach((v) => {
    var d = v.duration;
    if (!(d > 0 && isFinite(d)) || v.currentTime >= d - 0.2) return;
    if (mode !== 1 && !(d <= 60 || (v.closest && v.closest('.ad-showing,.ad-playing,.jw-flag-ads,.vjs-ad-playing,[class*="ima-ad"],[id*="ima-ad"]')))) return;
    try { v.currentTime = d - 0.1; v.play(); } catch (e) {}
  });
  toFrames({ __ruterika: 'skip', m: mode });
};
W.__ruterikaScan = () => { scan(); toFrames({ __ruterika: 'scan' }); };
W.addEventListener('message', (e) => {
  var d = e.data && e.data.__ruterika;
  if (d === 'pause') W.__ruterikaPauseAll(); else if (d === 'scan') W.__ruterikaScan(); else if (d === 'skip') W.__ruterikaSkipAds(e.data.m);
});
if (D.readyState === 'loading') D.addEventListener('DOMContentLoaded', scan); else scan();
W.addEventListener('load', scan);
var runs = 0, iv = setInterval(() => { scan(); if (++runs >= 10) clearInterval(iv); }, 3000);
})();
