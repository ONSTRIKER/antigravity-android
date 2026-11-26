(function(){
/**
 * patches/web_optimizations/taps.js - kill the 300ms double-tap delay.
 */
'use strict';

function installTaps(doc) {
  try {
    var style = doc.createElement('style');
    style.setAttribute('data-agy', 'web-optimizations');
    style.textContent =
      'button,a,[role="button"],input,select,textarea{touch-action:manipulation;}';
    (doc.head || doc.documentElement).appendChild(style);
  } catch (e) {}
}

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

/**
 * patches/web_optimizations/gpu.js - GPU-accelerate scroll/animation surfaces
 * and isolate layout for lists.
 */
function installGpu(doc) {
  try {
    if (!doc.querySelector('style[data-agy="gpu-acceleration"]')) {
      var gpu = doc.createElement('style');
      gpu.setAttribute('data-agy', 'gpu-acceleration');
      // Promote ONLY the real scroll containers. Never inline <code>/<pre>:
      // per-element layers explode on Mali and saturate RenderThread.
      gpu.textContent =
        'main, [data-testid="chat-container"], .scrollable {' +
        ' transform: translateZ(0);' +
        ' -webkit-overflow-scrolling: touch; }' +
        '[role="listitem"], .message-row, .artifact-viewer {' +
        ' contain: layout style; }' +
        '*{ -webkit-tap-highlight-color: transparent; }';
      (doc.head || doc.documentElement).appendChild(gpu);
    }
  } catch (e) {}
}

/**
 * patches/web_optimizations/public-api.js - install and expose.
 */
var global = window;

if (global.__AG_WEB_OPTIMIZATIONS__) return;
global.__AG_WEB_OPTIMIZATIONS__ = true;

var doc = global.document;
if (!doc) return;

installTaps(doc);
installViewport(global, doc);
installGpu(doc);

global.__agWebOptimizations = { installed: true };


})();
