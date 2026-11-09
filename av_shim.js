(function () {
  if (window.__avShim) return;
  window.__avShim = true;
  function send(o) { try { AlokNative.postMessage(JSON.stringify(o)); return true; } catch (e) { return false; } }

  // Web Share API -> Android share sheet
  if (!navigator.share) {
    navigator.share = function (d) {
      d = d || {};
      send({ t: 'share', title: d.title || '', text: d.text || '', url: d.url || '' });
      return Promise.resolve();
    };
  }
  if (!navigator.canShare) navigator.canShare = function (d) { return !(d && d.files && d.files.length); };

  // Notification API -> native notifications
  if (!('Notification' in window)) {
    var N = function (title, opts) {
      opts = opts || {};
      send({ t: 'notify', title: String(title || ''), body: String(opts.body || '') });
      this.close = function () {};
    };
    N.permission = 'granted';
    N.requestPermission = function (cb) {
      send({ t: 'notifPerm' });
      if (typeof cb === 'function') cb('granted');
      return Promise.resolve('granted');
    };
    window.Notification = N;
  }

  // Blob / data: downloads -> save into Downloads folder
  function saveFromUrl(a) {
    var name = a.getAttribute('download') || ('alokverse-' + Date.now());
    fetch(a.href).then(function (r) { return r.blob(); }).then(function (b) {
      var fr = new FileReader();
      fr.onloadend = function () {
        var d = String(fr.result);
        send({ t: 'save', name: name, mime: b.type || 'application/octet-stream', data: d.substring(d.indexOf(',') + 1) });
      };
      fr.readAsDataURL(b);
    });
  }
  function isLocalData(h) { return h && (h.indexOf('blob:') === 0 || h.indexOf('data:') === 0); }
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (isLocalData(this.href) && this.hasAttribute('download')) { saveFromUrl(this); return; }
    return origClick.apply(this, arguments);
  };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (a && isLocalData(a.href) && a.hasAttribute('download')) {
      e.preventDefault(); e.stopPropagation(); saveFromUrl(a);
    }
  }, true);
})();
