package com.mangatranslator.app.web

import android.net.Uri

/**
 * سیستم حذف تبلیغات سه‌لایه:
 *  1) مسدودسازی شبکه: درخواست‌های دامنه‌های تبلیغاتی/ردیاب هرگز ارسال نمی‌شوند.
 *  2) الگوی آدرس: درخواست‌های شخص‌ثالثی که مسیرشان شبیه تبلیغ است.
 *  3) پاکسازی صفحه: CSS + جاوااسکریپت که بنرها، پاپ‌آپ‌ها و لایه‌های تمام‌صفحه را حذف می‌کند.
 * لیست دامنه‌ها را می‌توانید گسترش دهید.
 */
object AdBlocker {

    private val domains = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com", "pagead2.googlesyndication.com",
        "amazon-adsystem.com", "adnxs.com", "adsrvr.org", "casalemedia.com", "openx.net", "pubmatic.com",
        "rubiconproject.com", "criteo.com", "criteo.net", "smartadserver.com", "bidswitch.net", "yieldmo.com",
        "taboola.com", "outbrain.com", "mgid.com", "revcontent.com", "scorecardresearch.com", "quantserve.com",
        "adsterra.com", "highperformanceformat.com", "effectivegatecpm.com", "profitablecpmrate.com",
        "profitablegatecpm.com", "creativecdn.com", "exoclick.com", "exosrv.com", "exdynsrv.com", "realsrv.com",
        "juicyads.com", "trafficjunky.com", "clickadu.com", "hilltopads.net", "adcash.com", "popads.net",
        "popcash.net", "propellerads.com", "propellerclick.com", "onclickads.net", "a-ads.com", "ad-maven.com",
        "admaven.com", "monetag.com", "richpartners.com", "tsyndicate.com", "acscdn.com", "histats.com",
        "statcounter.com", "yadro.ru", "mc.yandex.ru", "ads.yahoo.com", "adform.net", "adskeeper.com",
        "adspyglass.com", "bebi.com", "cdn4ads.com", "clksite.com", "dtscout.com", "go-mpulse.net",
        "pushance.com", "pushwhy.com", "notix.io", "evadav.com", "zeropark.com", "trk.hilltopads.net"
    )

    // مسیرهای مشکوک؛ فقط برای درخواست‌های شخص‌ثالث اعمال می‌شود
    private val pathPatterns = listOf(
        Regex("/pagead/|/adserver|/adframe|/ads/|/ad/banner|/popunder|/popads|/banner_ads|[?&]ad_?(unit|slot|zone)="),
        Regex("(^|/)(ads?|adv|banner)\\.(js|php|html)(\\?|$)")
    )

    private fun hostMatches(host: String): Boolean =
        domains.any { host == it || host.endsWith(".$it") }

    /** آیا این درخواست زیرمنبع باید مسدود شود؟ (ناوبری اصلی صفحه هرگز مسدود نمی‌شود) */
    fun shouldBlock(url: Uri, pageHost: String?): Boolean {
        val host = url.host?.lowercase() ?: return false
        if (hostMatches(host)) return true
        val thirdParty = pageHost != null && !(host == pageHost || host.endsWith(".$pageHost") || pageHost.endsWith(".$host"))
        if (thirdParty) {
            val full = (url.path ?: "") + (url.query?.let { "?$it" } ?: "")
            if (pathPatterns.any { it.containsMatchIn(full) }) return true
        }
        return false
    }

    /** جاوااسکریپت پاکسازی صفحه (idempotent). */
    val COSMETIC_JS = """
(function(){
  if (window.__mtAds) return; window.__mtAds = true;
  try { window.open = function(){ return null; }; } catch(e){}
  var css = 'ins.adsbygoogle,.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],' +
    'iframe[src*="doubleclick"],iframe[src*="googlesyndication"],iframe[src*="adsterra"],iframe[src*="exoclick"],iframe[src*="propeller"],' +
    '.ad,.ads,.adsbox,.advert,.advertisement,.advertise,.ad-container,.ads-container,.ad-banner,.banner-ad,.ad-wrapper,.ad-slot,' +
    '.sticky-ads,.popup-ad,.pop-ad,[class^="ads-"],[class*=" ads-"],[id^="ads-"],[id^="ad-"]' +
    '{display:none!important;visibility:hidden!important;height:0!important;min-height:0!important;max-height:0!important;overflow:hidden!important;}';
  var st = document.createElement('style'); st.textContent = css;
  (document.head || document.documentElement).appendChild(st);

  // لایه‌های تمام‌صفحه/پاپ‌آپ (کلیک‌دزد) را حذف می‌کند؛ تصاویر چپتر را لمس نمی‌کند
  function sweep(){
    try {
      var vw = window.innerWidth, vh = window.innerHeight;
      var els = document.querySelectorAll('div,a,iframe,section');
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        if (el.querySelector && el.querySelector('img[data-mt-id]')) continue;
        var cs = getComputedStyle(el);
        if (cs.position !== 'fixed') continue;
        var r = el.getBoundingClientRect();
        var big = r.width >= vw * 0.8 && r.height >= vh * 0.5;
        var hasFrame = el.tagName === 'IFRAME' || (el.querySelector && el.querySelector('iframe'));
        var z = parseInt(cs.zIndex, 10) || 0;
        if ((big && z >= 100) || (hasFrame && r.width >= 200 && z >= 1000)) el.remove();
      }
      document.body && (document.body.style.overflow = document.body.style.overflow === 'hidden' ? 'auto' : document.body.style.overflow);
    } catch(e){}
  }
  var t = null;
  function later(){ if (t) return; t = setTimeout(function(){ t = null; sweep(); }, 700); }
  new MutationObserver(later).observe(document.documentElement, {childList:true, subtree:true});
  setTimeout(sweep, 1200);
})();
"""
}
