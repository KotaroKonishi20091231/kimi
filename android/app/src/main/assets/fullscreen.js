// claude.ai の上のバー（共有・チャットなど）を隠して、KIMI の画面だけを全画面で見せる。
// KIMI の画面が入っている一番大きい枠を全画面に広げ、そのほかの部分は見えなくする。
// 確認ダイアログ（Claude の利用許可など）が出ている間だけは元に戻して、押せるようにする。
(function () {
  if (window.__kimiFull) return;
  window.__kimiFull = true;

  var css = document.createElement("style");
  css.textContent =
    ".kimi-full{position:fixed!important;inset:0!important;left:0!important;top:0!important;width:100vw!important;height:100%!important;" +
    "max-width:none!important;max-height:none!important;margin:0!important;border:0!important;border-radius:0!important;" +
    "transform:none!important;z-index:2147483646!important;background:#05070D!important;visibility:visible!important}" +
    ".kimi-anc{transform:none!important;filter:none!important;backdrop-filter:none!important;-webkit-backdrop-filter:none!important;" +
    "perspective:none!important;contain:none!important;will-change:auto!important;clip-path:none!important;visibility:visible!important}" +
    ".kimi-hide{visibility:hidden!important}" +
    "html.kimi-lock,html.kimi-lock body{overflow:hidden!important;background:#05070D!important}";
  document.documentElement.appendChild(css);

  var chosen = null;
  var marked = [];

  function largest() {
    var best = null, area = 0;
    document.querySelectorAll("iframe").forEach(function (f) {
      var r = f.getBoundingClientRect();
      var a = r.width * r.height;
      if (a > area) { area = a; best = f; }
    });
    return area > 40000 ? best : null;
  }

  function openDialog() {
    var list = document.querySelectorAll('[role="dialog"],[role="alertdialog"],[aria-modal="true"]');
    for (var i = 0; i < list.length; i++) {
      var d = list[i];
      if (chosen && d.contains(chosen)) continue; // KIMI の画面自体を包んでいる枠は確認画面ではない
      if (d.getClientRects().length === 0) continue; // 見えていない
      return d;
    }
    return null;
  }

  function mark(el, cls) { el.classList.add(cls); marked.push([el, cls]); }

  function unapply() {
    marked.forEach(function (m) { m[0].classList.remove(m[1]); });
    marked = [];
    document.documentElement.classList.remove("kimi-lock");
  }

  function apply() {
    unapply();
    mark(chosen, "kimi-full");
    for (var el = chosen; el && el !== document.documentElement; el = el.parentElement) {
      if (el !== chosen) mark(el, "kimi-anc");
      var parent = el.parentElement;
      if (!parent) break;
      for (var i = 0; i < parent.children.length; i++) {
        var sib = parent.children[i];
        if (sib === el || /^(SCRIPT|STYLE|LINK|META|HEAD)$/.test(sib.tagName)) continue;
        mark(sib, "kimi-hide");
      }
    }
    document.documentElement.classList.add("kimi-lock");
  }

  var applied = false;
  function tick() {
    var on = location.pathname.indexOf("artifact") !== -1;
    if (!chosen || !chosen.isConnected || !applied) {
      var next = largest();
      if (next !== chosen) { if (applied) unapply(); applied = false; chosen = next; }
    }
    var want = on && !!chosen && !openDialog();
    if (want && !applied) { apply(); applied = true; }
    else if (!want && applied) { unapply(); applied = false; }
  }

  tick();
  setInterval(tick, 400);
})();
