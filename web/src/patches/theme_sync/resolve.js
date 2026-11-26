/**
 * patches/theme_sync/resolve.js - theme resolution (body classList > Android > host > media).
 */
function fromClassList() {
  try {
    var b = doc && doc.body;
    if (b && b.classList) {
      if (b.classList.contains('theme-dark')) return 'dark';
      if (b.classList.contains('theme-light')) return 'light';
    }
  } catch (e) { /* ignore */ }
  return null;
}

function resolveTheme() {
  var cl = fromClassList();
  if (cl) return cl;
  if (typeof window !== 'undefined' && window.Android && typeof window.Android.getTheme === 'function') {
    try {
      var t = window.Android.getTheme();
      if (t === 'dark' || t === 'light') return t;
    } catch (e) {}
  }
  if (initialHostTheme === 'light') return 'light';
  if (initialHostTheme === 'dark') return 'dark';
  if (typeof window !== 'undefined' && window.__AG_RESOLVED_THEME__) {
    return window.__AG_RESOLVED_THEME__;
  }
  if (typeof window !== 'undefined' && window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
    return 'dark';
  }
  return 'light';
}

function updateColorSchemeMeta(theme) {
  try {
    if (!doc || !doc.head) return;
    var meta = doc.querySelector('meta[name="color-scheme"]');
    if (!meta) {
      meta = doc.createElement('meta');
      meta.setAttribute('name', 'color-scheme');
      doc.head.appendChild(meta);
    }
    meta.setAttribute('content', theme === 'light' ? 'only light' : 'dark');
  } catch (e) { /* ignore */ }
}
