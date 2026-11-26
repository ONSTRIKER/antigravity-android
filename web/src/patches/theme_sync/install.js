/**
 * patches/theme_sync/install.js - observe class/storage changes immediately.
 */
function observeBody() {
  if (doc && doc.body && state.observer) {
    try {
      state.observer.observe(doc.body, {
        attributes: true, attributeFilter: ['class']
      });
      return true;
    } catch (e) { /* ignore */ }
  }
  return false;
}

function install() {
  if (state.installed) return;
  state.installed = true;

  if (window.MutationObserver && doc && doc.documentElement) {
    try {
      state.observer = new MutationObserver(function () { notify(); });
      state.observer.observe(doc.documentElement, {
        attributes: true, attributeFilter: ['class']
      });
      if (!observeBody() && doc.addEventListener) {
        doc.addEventListener('DOMContentLoaded', function () {
          observeBody();
          notify();
        }, { once: true });
      }
    } catch (e) { /* ignore */ }
  }

  if (!state.storageBound && window.addEventListener) {
    window.addEventListener('storage', function () { notify(); });
    state.storageBound = true;
  }

  if (!state.mediaBound && window.matchMedia) {
    try {
      var mq = window.matchMedia('(prefers-color-scheme: dark)');
      var handler = function () { notify(); };
      if (mq.addEventListener) mq.addEventListener('change', handler);
      else if (mq.addListener) mq.addListener(handler);
      state.mediaBound = true;
    } catch (e) { /* ignore */ }
  }

  window.__agOnSystemThemeChanged = function () { notify(); };
  notify();
}
