package com.mangatranslator.app.web

/**
 * جاوااسکریپتی که داخل صفحه تزریق می‌شود:
 *  - MT_scan(): تصاویر چپتر را پیدا می‌کند (پشتیبانی از lazy-load) و {visible, items:[{id,url}]} برمی‌گرداند
 *  - MT_replace(id, url): تصویر ترجمه‌شده را جای تصویر اصلی می‌گذارد
 *  - MT_show(original): جابه‌جایی بین نسخه اصلی و ترجمه
 */
object ReaderScripts {
    const val HOST = "mt.local"

    val INJECT = """
(function(){
  if (window.MT_scan) return;
  var LAZY = ['data-src','data-lazy-src','data-original','data-lazy','data-cfsrc'];
  function abs(u){ try { return new URL(String(u).trim(), location.href).href; } catch(e){ return null; } }
  function hasLazy(img){ for (var i=0;i<LAZY.length;i++){ if (img.hasAttribute(LAZY[i])) return true; } return false; }
  function srcOf(img){
    for (var i=0;i<LAZY.length;i++){
      var v = img.getAttribute(LAZY[i]);
      if (v && v.trim() && v.trim().indexOf('data:') !== 0) return abs(v);
    }
    var s = img.getAttribute('src');
    if (s && s.trim() && s.trim().indexOf('data:') !== 0) return abs(s);
    return img.currentSrc ? abs(img.currentSrc) : null;
  }
  function candidate(img){
    var r = img.getBoundingClientRect();
    var nw = img.naturalWidth || 0, nh = img.naturalHeight || 0;
    if (nw >= 250 && nh >= 120) return true;
    if (nw === 0 && hasLazy(img)) return true;
    return r.width >= 250 && r.height >= 120;
  }
  function setSrc(el, u){
    for (var i=0;i<LAZY.length;i++){ if (el.hasAttribute(LAZY[i])) el.setAttribute(LAZY[i], u); }
    el.removeAttribute('srcset'); el.removeAttribute('data-srcset');
    el.removeAttribute('sizes'); el.removeAttribute('loading');
    el.classList.remove('lazyload','lazy'); el.classList.add('lazyloaded');
    el.src = u;
  }
  window.MT_scan = function(){
    var sel = document.querySelectorAll('.reading-content img, .wp-manga-chapter-img, #readerarea img, .chapter-content img, .container-chapter-reader img');
    var imgs = sel.length >= 2 ? Array.prototype.slice.call(sel) : Array.prototype.slice.call(document.images);
    var seen = [], out = [], seq = window.__mtSeq || 0, sy = window.scrollY, visible = 0, found = false;
    for (var k=0;k<imgs.length;k++){
      var img = imgs[k];
      if (seen.indexOf(img) >= 0) continue; seen.push(img);
      if (img.getAttribute('data-mt-tr')) continue;
      var url = img.getAttribute('data-mt-url') || srcOf(img);
      if (!url || !candidate(img)) continue;
      var id = img.getAttribute('data-mt-id');
      if (id === null){ id = seq++; img.setAttribute('data-mt-id', id); img.setAttribute('data-mt-url', url); }
      else { id = parseInt(id, 10); }
      var r = img.getBoundingClientRect();
      if (!found && r.bottom > 0){ visible = out.length; found = true; }
      out.push({id: id, url: img.getAttribute('data-mt-url')});
    }
    window.__mtSeq = seq;
    return JSON.stringify({visible: visible, items: out});
  };
  window.MT_replace = function(id, u){
    var el = document.querySelector('img[data-mt-id="' + id + '"]');
    if (!el) return;
    el.setAttribute('data-mt-tr', u);
    setSrc(el, u);
  };
  window.MT_show = function(original){
    var els = document.querySelectorAll('img[data-mt-tr]');
    for (var i=0;i<els.length;i++){
      var el = els[i];
      setSrc(el, original ? el.getAttribute('data-mt-url') : el.getAttribute('data-mt-tr'));
    }
  };
})();
"""
}
