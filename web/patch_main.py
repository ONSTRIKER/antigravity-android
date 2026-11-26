#!/usr/bin/env python3
"""
web/patch_main.py - Dynamic, resilient patcher for web/generated/main.js.

Applies Android host compatibility & foldable responsive layout patches:
1. Disables unsupported desktop bubblewrap/seccomp terminal sandbox presets.
2. Switches sidebarComponent layout from fixed desktop split ("SIDEBAR") to
   "RESPONSIVE" so narrow/foldable cover screens (< 768px) open a full-width
   slide-over drawer instead of squashing the split-pane to ~75px, while
   unfolded screens (>= 768px) keep the docked desktop split-pane.
3. Switches auxPaneComponent location to "responsive" so narrow screens (< 640px)
   open the side panel as a 95%-width slide-over popover instead of crushing chat.
4. Clamps persisted sidebarWidth to [256, 456] so previously squashed widths
   stored in localStorage/storageService heal automatically.
5. Injects the Desktop Mode toggle into Appearance settings when matched.

Safe, idempotent, and graceful when upstream signatures shift.
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile

IDENT_RE = r"[A-Za-z0-9_$]+"


def _identifier_declared(content, ident):
    """Verify a minified component identifier is declared in the bundle."""
    if not ident or not re.fullmatch(IDENT_RE, ident):
        return False
    pattern = re.compile(
        r"(?:^|[;,{\s])(?:var|let|const|function|class)\s+" + re.escape(ident) + r"(?=\s*[=(;,{])"
    )
    return pattern.search(content) is not None


def _detect_setting_components(content, react_id):
    """Dynamically locate the setting row and switch components around rightElement:."""
    escaped_react = re.escape(react_id)
    combo_re = re.compile(
        escaped_react
        + r"\.createElement\(("
        + IDENT_RE
        + r"),\{label:[^}]*rightElement:"
        + escaped_react
        + r"\.createElement\(("
        + IDENT_RE
        + r"),\{checked:"
    )

    for match in re.finditer(r"rightElement:", content):
        idx = match.start()
        window = content[max(0, idx - 240) : min(len(content), idx + 180)]
        found = combo_re.search(window)
        if found:
            item_comp = found.group(1)
            switch_comp = found.group(2)
            if _identifier_declared(content, item_comp) and _identifier_declared(content, switch_comp):
                return item_comp, switch_comp
    return None, None


def _verify_js_syntax(js_path):
    """Optionally verify patched JS syntax using `node --check` when node is available."""
    node_bin = shutil.which("node")
    if not node_bin:
        return True, "node not found; skipped syntax verification"
    try:
        proc = subprocess.run(
            [node_bin, "--check", js_path],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=30,
        )
    except Exception as exc:
        return False, f"node --check invocation error: {exc}"
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout or "").strip()
        return False, f"node --check failed: {err}"
    return True, "node --check ok"


def _apply_structural_fixes(content):
    """Apply resilient Android sandbox & foldable responsive sidebar patches."""
    changed = []

    next_c = content.replace("enableTerminalSandbox:!0", "enableTerminalSandbox:!1")
    if next_c != content:
        content = next_c
        changed.append("sandbox=off")

    sidebar_src = 'sidebarComponent:{feature:{layout:"SIDEBAR"'
    sidebar_dst = 'sidebarComponent:{feature:{layout:"RESPONSIVE"'
    if sidebar_src in content:
        content = content.replace(sidebar_src, sidebar_dst, 1)
        changed.append("sidebar=RESPONSIVE")

    aux_src = 'auxPaneComponent:{feature:{location:"sidebar",'
    aux_dst = 'auxPaneComponent:{feature:{location:"responsive",closeSidePanelPopoverOnNavigation:!0,'
    if aux_src in content:
        content = content.replace(aux_src, aux_dst, 1)
        changed.append("auxPane=responsive")

    sw_re = re.compile(
        r'("sidebarWidth",'
        + IDENT_RE
        + r"=>String\("
        + IDENT_RE
        + r"\),("
        + IDENT_RE
        + r")=>\{\2=Number\(\2\);return isNaN\(\2\)\?256:)(\2)(\})"
    )
    sw_match = sw_re.search(content)
    if sw_match:
        v = sw_match.group(2)
        repl = f"{sw_match.group(1)}({v}<240?256:{v}>456?456:{v}){sw_match.group(4)}"
        content = content[: sw_match.start()] + repl + content[sw_match.end() :]
        changed.append("sidebarWidth=clamped")

    return content, changed


def _write_verified(file_path, patched_content):
    target_dir = os.path.dirname(os.path.abspath(file_path))
    tmp_path = None
    try:
        fd, tmp_path = tempfile.mkstemp(prefix=".patch_main_", suffix=".js", dir=target_dir)
        with os.fdopen(fd, "w", encoding="utf-8") as tmp_f:
            tmp_f.write(patched_content)

        ok, msg = _verify_js_syntax(tmp_path)
        if not ok:
            print(
                f"[web-patch] WARNING: patched main.js failed syntax check ({msg}); "
                "keeping unpatched copy (mobile_bridge.js provides resilient fallback controls).",
                file=sys.stderr,
            )
            os.remove(tmp_path)
            tmp_path = None
            return False, msg

        os.replace(tmp_path, file_path)
        tmp_path = None
        return True, msg
    finally:
        if tmp_path and os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except OSError:
                pass


def patch_main(file_path):
    if not os.path.isfile(file_path):
        print(f"[web-patch] file not found: {file_path}", file=sys.stderr)
        return False

    with open(file_path, "r", encoding="utf-8") as f:
        orig_content = f.read()

    content, structural_changes = _apply_structural_fixes(orig_content)

    if "Desktop Mode" in content or "ag_desktop_mode" in content:
        if content != orig_content:
            wrote, msg = _write_verified(file_path, content)
            if wrote:
                print(f"[web-patch] Applied structural fixes ({', '.join(structural_changes)}; {msg})")
        else:
            print("[web-patch] Desktop Mode toggle and structural fixes already present in main.js")
        return True

    # 1. JSX Theme element injection: find Theme element in Appearance
    theme_idx = content.find('{label:"Theme"')
    theme_match = None
    react_id = None
    if theme_idx != -1:
        window_start = max(0, theme_idx - 100)
        window_end = min(len(content), theme_idx + 500)
        window_text = content[window_start:window_end]
        theme_pattern = re.compile(
            r"(" + IDENT_RE + r")\.createElement\((" + IDENT_RE + r'),\{label:"Theme",.*?onSelect:' + IDENT_RE + r"\}\)"
        )
        theme_match = theme_pattern.search(window_text)
        if theme_match:
            react_id = theme_match.group(1)

    # 2. State hook anchor in Appearance component:
    hook_anchor = None
    if react_id and theme_idx != -1:
        app_window_start = max(0, theme_idx - 400)
        app_window = content[app_window_start:theme_idx]
        ret_pattern = re.compile(
            r"(;return\s+" + re.escape(react_id) + r"\.createElement\([^;]{0,160}\{title:\"Appearance\"\})"
        )
        ret_match = ret_pattern.search(app_window)
        if ret_match:
            hook_anchor = ret_match.group(1)

    if not hook_anchor:
        hook_pattern = re.compile(
            r"(var\s+"
            + IDENT_RE
            + r"="
            + IDENT_RE
            + r"!==3,\s*"
            + IDENT_RE
            + r"="
            + IDENT_RE
            + r"!==2,\s*"
            + IDENT_RE
            + r"="
            + IDENT_RE
            + r"=>\s*"
            + IDENT_RE
            + r"=>\{.*?\};)"
        )
        hook_match = hook_pattern.search(content)
        if hook_match:
            hook_anchor = hook_match.group(1)

    item_comp, switch_comp = (
        _detect_setting_components(content, react_id) if react_id else (None, None)
    )

    if not hook_anchor or not theme_match or not react_id or not item_comp or not switch_comp:
        if content != orig_content:
            _write_verified(file_path, content)
        print(
            "[web-patch] WARNING: upstream main.js Appearance signatures not matched "
            f"(hook={bool(hook_anchor)}, theme={bool(theme_match)}, "
            f"item={item_comp}, switch={switch_comp}); "
            "mobile_bridge.js provides resilient fallback controls.",
            file=sys.stderr,
        )
        return True

    hook_code = (
        f"var [isDesktop,setIsDesktop]=(0,{react_id}.useState)(()=>{{"
        "try{if(window.Android&&typeof window.Android.isDesktopMode==='function')return window.Android.isDesktopMode();"
        "var s=localStorage.getItem('ag_desktop_mode');return s===null?!1:s==='true'}catch(_){return !1}});"
        f"var onDesktopToggle=(0,{react_id}.useCallback)(v=>{{"
        "setIsDesktop(v);try{localStorage.setItem('ag_desktop_mode',String(v));"
        "if(window.Android&&typeof window.Android.setDesktopMode==='function')window.Android.setDesktopMode(v)}catch(_){}},[]);"
    )
    if hook_anchor.startswith(";return"):
        injected_hook = ";" + hook_code + hook_anchor[1:]
    else:
        injected_hook = hook_code + hook_anchor

    orig_theme = theme_match.group(0)
    injected_theme = (
        orig_theme
        + f',{react_id}.createElement({item_comp},{{label:"Desktop Mode",description:"Use desktop workspace layout and user agent",'
        + f"rightElement:{react_id}.createElement({switch_comp},{{checked:isDesktop,onCheckedChange:onDesktopToggle}})}})"
    )

    patched = content.replace(hook_anchor, injected_hook, 1)
    patched = patched.replace(orig_theme, injected_theme, 1)

    wrote, msg = _write_verified(file_path, patched)
    if wrote:
        extras = f"; {', '.join(structural_changes)}" if structural_changes else ""
        print(
            f"[web-patch] Successfully patched main.js "
            f"(react={react_id}, item={item_comp}, switch={switch_comp}{extras}; {msg})"
        )
    return True


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "main.js")
    if not patch_main(path):
        sys.exit(1)
