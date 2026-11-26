---
name: android-phone-agent
description: Operate any Android app, screen, notification, deep link, or system setting autonomously using the native `android-device` MCP tools (`android_agent_status`, `android_apps`, `android_intent`, `android_ui_tree`, `android_ui_act`, `android_screenshot`, `android_notifications`). Use whenever the user asks you to perform tasks on their phone across installed apps, inspect or tap UI elements, read or reply to notifications, open apps/settings/maps/calls, or automate multi-step mobile workflows.
---

# Android Autonomous Phone Agent (`android-phone-agent`)

Antigravity includes native persistent Android OS agent services (`AgyAccessibilityService`, `AgyNotificationListenerService`, and `PhoneAgentHandler` on `SystemGatewayServer`) exposed through the `android-device` MCP server (`$PREFIX/bin/agy-mcp-android`). Unlike ADB/Shizuku-only scripts, once **Accessibility** and **Notification Access** are enabled once in Android Settings, cross-app UI perception, gestures, screenshots, app launching, intents, and notification replies stay active across reboots.

## 1. Readiness Check & One-Tap Settings Onboarding

Before starting a multi-step cross-app UI workflow, call `android_agent_status`:
- Verify `accessibility_connected: true` (required for `android_ui_tree`, `android_ui_act`, and `android_screenshot`).
- Verify `notification_listener_connected: true` (required for `android_notifications`).
- If a service is not enabled yet, open the exact Android Settings screen for the user in one call:
  - `android_agent_status(open_setting="accessibility")` -> user taps **Installed apps -> Antigravity -> On**.
  - `android_agent_status(open_setting="notification_listener")` -> user toggles **Antigravity -> Allow**.
  - `android_agent_status(open_setting="all_files")` / `"battery"` / `"overlay"` / `"install_unknown_apps"` as needed.

## 2. Prefer Intents & App Launch Over Blind Tapping

Never waste turns swiping the home screen to find an app or opening a browser URL manually when an Intent or package launch jumps straight to the target:
1. **Launch an app by name or package:**
   - `android_apps(action="launch", query="Messages")` or `android_apps(action="list", query="bank")`
2. **Jump straight via Deep Link / Intent (`android_intent`):**
   - Navigation / Maps: `android_intent(uri="geo:0,0?q=Starbucks+near+me")` or `google.navigation:q=...`
   - Dialer (stage number safely without auto-calling): `android_intent(action="android.intent.action.DIAL", uri="tel:+15551234567")`
   - Compose SMS: `android_intent(action="android.intent.action.SENDTO", uri="smsto:+15551234567", extras={"sms_body": "On my way!"})`
   - System Settings: `android_intent(setting="wifi")` / `"bluetooth"` / `"display"` / `"sound"`
3. **Read or reply to incoming messages instantly from the notification shade (`android_notifications`):**
   - `android_notifications(action="list")` returns `key`, `package`, `title`, `text`, `big_text`, and `actions` (`has_reply_input: true`).
   - Send an inline `RemoteInput` reply (SMS, WhatsApp, Telegram, Slack, Discord, Gmail) without even switching foreground apps:
     `android_notifications(action="reply", key="<sbn_key>", reply_text="Sounds good!")`

## 3. The Perceive -> Act -> Verify UI Loop

When interacting inside an Android app UI:

### Step A: Perceive (`android_ui_tree` first, `android_screenshot` when visual)
- Call `android_ui_tree(interactive_only=true)` first — it is fast (~20 ms), structured, and returns exact `text`, `desc`, `view_id`, `bounds`, and `center: [x, y]` for every button, input, and scrollable list.
- Call `android_screenshot()` and inspect the saved PNG with `read_file` (or run `tesseract <screen.png> stdout` in shell for rapid OCR) when an app renders custom canvases, WebViews without accessibility labels, images, charts, or maps.

### Step B: Act (`android_ui_act`)
- **Semantic selectors first** (resilient to fold/unfold resolution changes):
  - Click by visible label: `android_ui_act(action="click", target_text="Send")`
  - Click by content description: `android_ui_act(action="click", desc="More options")`
  - Click by view ID: `android_ui_act(action="click", view_id="search_src_text")`
  - Enter text + submit: `android_ui_act(action="set_text", target_text="Search", text="wireless earbuds", submit=true)`
  - Scroll list: `android_ui_act(action="scroll_down")` or `scroll_up`
- **Coordinate gestures** (when semantic nodes lack `clickable` or inside games/canvases):
  - `android_ui_act(action="tap", x=540, y=1200)`
  - `android_ui_act(action="swipe", x1=540, y1=1800, x2=540, y2=600, duration_ms=300)`
- **Global hardware navigation:**
  - `android_ui_act(action="back")` / `"home"` / `"recents"` / `"notifications"` / `"quick_settings"` / `"split_screen"`

### Step C: Verify
- After launching an app, submitting a form, or navigating to a new screen, call `android_ui_tree(interactive_only=true)` (after a brief pause if the app is loading) to confirm the transition succeeded before chaining further clicks.

## 4. Safety & User Confirmation Guardrails

Even with full phone permissions enabled, always confirm with the user (in chat or via `android_notify(approval=true)` + `android_wait_approval` when running in the background) before final irreversible actions:
- Completing financial payments, purchases, or bank transfers.
- Sending messages/emails to external recipients unless the user explicitly asked you to send it.
- Deleting user photos, contacts, accounts, or uninstalling apps.
