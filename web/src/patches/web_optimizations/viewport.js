/**
 * patches/web_optimizations/viewport.js - keep --agy-vvh / --agy-vvw honest
 * across soft-keyboard and foldable inner <-> cover display transitions, and
 * ensure the responsive sidebar drawer opens to full readable width on narrow
 * cover screens (e.g. Galaxy Z Fold closed 384dp display).
 */
function installViewport(global, doc) {
  function syncViewportVars() {
    try {
      if (!doc || !doc.documentElement) return;
      var vv = global.visualViewport;
      var h = (vv && vv.height) ? vv.height : (global.innerHeight || 0);
      var w = (vv && vv.width) ? vv.width : (global.innerWidth || 0);
      if (h > 0) {
        doc.documentElement.style.setProperty('--agy-vvh', Math.round(h) + 'px');
      }
      if (w > 0) {
        doc.documentElement.style.setProperty('--agy-vvw', Math.round(w) + 'px');
      }
      if (global.scrollX || global.scrollY) {
        global.scrollTo(0, 0);
      }
    } catch (e) {}
  }

  try {
    syncViewportVars();
    if (global.visualViewport && typeof global.visualViewport.addEventListener === 'function') {
      global.visualViewport.addEventListener('resize', syncViewportVars, { passive: true });
    }
    if (typeof global.addEventListener === 'function') {
      global.addEventListener('resize', syncViewportVars, { passive: true });
      global.addEventListener('agy:fold-layout', syncViewportVars);
    }
  } catch (e) {}

  try {
    if (doc && !doc.querySelector('style[data-agy="foldable-responsive"]')) {
      var style = doc.createElement('style');
      style.setAttribute('data-agy', 'foldable-responsive');
      style.textContent = [
        '@media (max-width: 767.98px) {',
        '  [data-testid="sidebar-popover-backdrop"] {',
        '    z-index: 60 !important;',
        '  }',
        '  [data-testid="sidebar-popover-backdrop"] + div[role="dialog"][aria-modal="true"] {',
        '    z-index: 61 !important;',
        '    width: min(308px, 86vw) !important;',
        '    min-width: min(260px, 82vw) !important;',
        '    max-width: 88vw !important;',
        '  }',
        '}'
      ].join('\n');
      (doc.head || doc.documentElement).appendChild(style);
    }
  } catch (e) {}
}
