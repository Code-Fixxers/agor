#!/usr/bin/env bash

set -euo pipefail

PKG="${AGOR_ANDROID_PKG:-}"
SERVER_URL="${AGOR_ANDROID_SERVER_URL:-http://192.168.88.116:3030}"
EMAIL="${AGOR_ANDROID_EMAIL:-}"
PASSWORD="${AGOR_ANDROID_PASSWORD:-}"
API_KEY="${AGOR_ANDROID_API_KEY:-}"
APK="${AGOR_ANDROID_APK:-}"
RESET_APP="${AGOR_ANDROID_RESET_APP:-0}"
UNLOCK_PIN="${AGOR_ANDROID_UNLOCK_PIN:-}"
SCREENSHOT_DIR="${AGOR_ANDROID_SCREENSHOT_DIR:-/tmp/agor-android-smoke}"
KEEP_WORKDIR="${AGOR_ANDROID_KEEP_WORKDIR:-0}"
SCROLL_LOOPS="${AGOR_ANDROID_SCROLL_LOOPS:-6}"
WAIT_TIMEOUT="${AGOR_ANDROID_WAIT_TIMEOUT:-30}"

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  ADB=(adb -s "$ANDROID_SERIAL")
else
  ADB=(adb)
fi

WORKDIR="$(mktemp -d)"
trap 'if [[ "$KEEP_WORKDIR" != "1" ]]; then rm -rf "$WORKDIR"; fi' EXIT

mkdir -p "$SCREENSHOT_DIR"

for cmd in adb awk date grep sed tr; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing required command: $cmd" >&2
    exit 2
  fi
done

if ! "${ADB[@]}" devices | awk 'NR > 1 && $2 == "device" { found = 1 } END { exit(found ? 0 : 1) }'; then
  echo "No connected adb device in 'device' state." >&2
  exit 2
fi

adb_shell() {
  "${ADB[@]}" shell "$@"
}

detect_package() {
  if [[ -n "$PKG" ]]; then
    echo "$PKG"
    return 0
  fi

  for candidate in live.agor.app.debug live.agor.app; do
    if adb_shell pm path "$candidate" >/dev/null 2>&1; then
      echo "$candidate"
      return 0
    fi
  done

  echo "live.agor.app.debug"
}

PKG="$(detect_package)"

ensure_unlocked() {
  adb_shell input keyevent 224 >/dev/null 2>&1 || true
  adb_shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb_shell input keyevent 82 >/dev/null 2>&1 || true
  adb_shell input swipe 540 2100 540 500 >/dev/null 2>&1 || true
  if [[ -n "$UNLOCK_PIN" ]]; then
    adb_shell input text "$UNLOCK_PIN" >/dev/null 2>&1 || true
    adb_shell input keyevent 66 >/dev/null 2>&1 || true
  fi
}

dump_ui() {
  local target="$1"
  adb_shell uiautomator dump /sdcard/agor-window.xml >/dev/null
  adb_shell cat /sdcard/agor-window.xml >"$target"
}

capture_screenshot() {
  local name="$1"
  "${ADB[@]}" exec-out screencap -p >"$SCREENSHOT_DIR/$name.png"
}

capture_logcat() {
  local name="$1"
  "${ADB[@]}" logcat -d -v time >"$SCREENSHOT_DIR/$name.log" || true
}

capture_gfxinfo() {
  local name="$1"
  adb_shell dumpsys gfxinfo "$PKG" >"$SCREENSHOT_DIR/$name-gfxinfo.txt" || true
  adb_shell dumpsys gfxinfo "$PKG" framestats >"$SCREENSHOT_DIR/$name-framestats.txt" || true
}

node_for_res() {
  local res="$1"
  local xml="$2"
  tr '>' '\n' <"$xml" | grep "resource-id=\"[^\"]*$res\"" | head -n1 || true
}

node_for_text() {
  local text="$1"
  local xml="$2"
  tr '>' '\n' <"$xml" | grep "text=\"$text\"" | head -n1 || true
}

has_node_for_chat_list() {
  local xml="$1"
  [[ -f "$xml" ]] && [[ -n "$(node_for_res "chat-list" "$xml")" ]]
}

is_login_screen() {
  local xml="$1"
  if [[ -n "$(node_for_res "login-email" "$xml")" ]]; then
    return 0
  fi
  if [[ -n "$(node_for_text "Sign in" "$xml")" ]] &&
    [[ -n "$(node_for_text "Server URL" "$xml")" ]] &&
    [[ -n "$(node_for_text "Email" "$xml")" ]] &&
    [[ -n "$(node_for_text "Password" "$xml")" ]]; then
    return 0
  fi
  return 1
}

is_home_screen() {
  local xml="$1"
  if [[ -n "$(node_for_res "agor-root" "$xml")" ]]; then
    return 0
  fi
  if [[ -n "$(node_for_content_desc "Zavřít navigační panel" "$xml")" ]]; then
    return 0
  fi
  if [[ -n "$(node_for_text "BOARDS" "$xml")" ]] || [[ -n "$(node_for_text "Boards" "$xml")" ]] || [[ -n "$(node_for_text "Agor" "$xml")" ]]; then
    if [[ -n "$(node_for_content_desc "Zavřít navigační panel" "$xml")" ]]; then
      return 0
    fi
    if [[ -n "$(node_for_text "Settings" "$xml")" ]] || [[ -n "$(node_for_text "Nastavení" "$xml")" ]]; then
      return 0
    fi
  fi
  if [[ -n "$(node_for_text "BOARDS" "$xml")" ]] && [[ -n "$(node_for_text "Settings" "$xml")" ]]; then
    return 0
  fi
  if [[ -n "$(node_for_text "Agor" "$xml")" ]] && [[ -n "$(node_for_text "Settings" "$xml")" ]]; then
    return 0
  fi
  if [[ -n "$(node_for_res "sidebar-list" "$xml")" ]] || [[ -n "$(node_for_res "sidebar-session-row" "$xml")" ]] || [[ -n "$(node_for_res "sidebar-worktree-row" "$xml")" ]] || [[ -n "$(node_for_res "sidebar-board-row" "$xml")" ]]; then
    return 0
  fi
  if grep -qi 'class=".*LazyColumn' "$xml" >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

wait_for_home() {
  local out="$1"
  local deadline=$((SECONDS + WAIT_TIMEOUT))
  while (( SECONDS < deadline )); do
    dump_ui "$out"
    if is_home_screen "$out"; then
      return 0
    fi
    if wait_for_text_in_xml "Agor" "$out" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_for_text_in_xml() {
  local text="$1"
  local out="$2"
  local deadline=$((SECONDS + WAIT_TIMEOUT))
  while (( SECONDS < deadline )); do
    dump_ui "$out"
    if [[ -n "$(node_for_text "$text" "$out")" ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

node_midpoint() {
  local node="$1"
  local bounds
  bounds="$(echo "$node" | sed -n 's/.*bounds="\[\([0-9]\+\),\([0-9]\+\)\]\[\([0-9]\+\),\([0-9]\+\)\]".*/\1 \2 \3 \4/p')"
  if [[ -z "$bounds" ]]; then
    return 1
  fi
  # shellcheck disable=SC2086
  set -- $bounds
  echo "$(((("$1" + "$3")) / 2)) $(((("$2" + "$4")) / 2))"
}

replace_text_node() {
  local node="$1"
  local value="$2"
  local midpoint x y
  midpoint="$(node_midpoint "$node")"
  x="$(echo "$midpoint" | awk '{print $1}')"
  y="$(echo "$midpoint" | awk '{print $2}')"
  adb_shell input tap "$x" "$y"
  sleep 0.2
  local i
  for i in $(seq 1 40); do
    adb_shell input keyevent 67 >/dev/null 2>&1 || true
  done
  adb_shell input text "$(escape_input_text "$value")"
}

wait_for_res() {
  local res="$1"
  local out="$2"
  local deadline=$((SECONDS + WAIT_TIMEOUT))
  while (( SECONDS < deadline )); do
    dump_ui "$out"
    if [[ -n "$(node_for_res "$res" "$out")" ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_for_not_login() {
  local out="$1"
  local deadline=$((SECONDS + WAIT_TIMEOUT))
  while (( SECONDS < deadline )); do
    dump_ui "$out"
    if ! is_login_screen "$out"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

tap_res() {
  local res="$1"
  local xml="$2"
  local node midpoint x y
  node="$(node_for_res "$res" "$xml")"
  if [[ -z "$node" ]]; then
    echo "Could not find UI resource containing: $res" >&2
    return 1
  fi
  midpoint="$(node_midpoint "$node")"
  x="$(echo "$midpoint" | awk '{print $1}')"
  y="$(echo "$midpoint" | awk '{print $2}')"
  adb_shell input tap "$x" "$y"
}

tap_text() {
  local text="$1"
  local xml="$2"
  local node midpoint x y
  node="$(node_for_text "$text" "$xml")"
  if [[ -z "$node" ]]; then
    echo "Could not find UI text: $text" >&2
    return 1
  fi
  midpoint="$(node_midpoint "$node")"
  x="$(echo "$midpoint" | awk '{print $1}')"
  y="$(echo "$midpoint" | awk '{print $2}')"
  adb_shell input tap "$x" "$y"
}

node_for_content_desc() {
  local desc="$1"
  local xml="$2"
  tr '>' '\n' <"$xml" | grep "content-desc=\"$desc\"" | head -n1 || true
}

escape_input_text() {
  printf '%s' "$1" | sed \
    -e 's/ /%s/g' \
    -e 's/\\/\\\\/g' \
    -e 's/&/\\\&/g' \
    -e 's/|/\\|/g' \
    -e 's/;/\\;/g' \
    -e 's/</\\</g' \
    -e 's/>/\\>/g' \
    -e 's/(/\\(/g' \
    -e 's/)/\\)/g' \
    -e 's/\$/\\$/g'
}

replace_text_res() {
  local res="$1"
  local value="$2"
  local xml="$3"
  tap_res "$res" "$xml"
  sleep 0.2
  local i
  for i in $(seq 1 40); do
    adb_shell input keyevent 67 >/dev/null 2>&1 || true
  done
  adb_shell input text "$(escape_input_text "$value")"
}

launch_app() {
  ensure_unlocked
  adb_shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
}

install_if_requested() {
  if [[ -z "$APK" ]]; then
    return 0
  fi
  if [[ ! -f "$APK" ]]; then
    echo "AGOR_ANDROID_APK does not exist: $APK" >&2
    exit 2
  fi
  "${ADB[@]}" install -r "$APK"
}

login_if_needed() {
  local xml="$WORKDIR/login.xml"
  dump_ui "$xml"
  if ! is_login_screen "$xml"; then
    return 0
  fi

  if [[ -n "$API_KEY" ]]; then
    echo "Logging in to $SERVER_URL with API key..."
    if [[ -n "$(node_for_res "login-mode-api-key" "$xml")" ]]; then
      tap_res "login-mode-api-key" "$xml"
      dump_ui "$xml"
      if [[ -n "$(node_for_res "login-server-url" "$xml")" ]] &&
        [[ -n "$(node_for_res "login-api-key" "$xml")" ]] &&
        [[ -n "$(node_for_res "login-submit" "$xml")" ]]; then
        replace_text_res "login-server-url" "$SERVER_URL" "$xml"
        dump_ui "$xml"
        replace_text_res "login-api-key" "$API_KEY" "$xml"
        dump_ui "$xml"
        tap_res "login-submit" "$xml"
      else
        # Fallback for older builds without Compose testTag resources.
        mapfile -t edit_nodes < <(tr '>' '\n' <"$xml" | grep 'class="android.widget.EditText"')
        if (( ${#edit_nodes[@]} < 2 )); then
          echo "Login screen API key mode detected, but editable fields were not found in fallback flow." >&2
          return 1
        fi
        replace_text_node "${edit_nodes[0]}" "$SERVER_URL"
        replace_text_node "${edit_nodes[1]}" "$API_KEY"
        if [[ -n "$(node_for_res "login-submit" "$xml")" ]]; then
          tap_res "login-submit" "$xml"
        else
          local api_key_button
          api_key_button="$(tr '>' '\n' <"$xml" | grep 'class="android.widget.Button"' | head -n1)"
          if [[ -n "$api_key_button" ]]; then
            local tap_button_node
            tap_button_node="$(node_midpoint "$api_key_button")"
            adb_shell input tap "$(echo "$tap_button_node" | awk '{print $1}')" "$(echo "$tap_button_node" | awk '{print $2}')"
          elif ! tap_text "Sign in with API key" "$xml"; then
            adb_shell input keyevent 66 >/dev/null 2>&1 || true
          fi
        fi
      fi
    else
      echo "AGOR_ANDROID_API_KEY is set, but API Key tab is unavailable in this UI." >&2
      exit 2
    fi
  else
    if [[ -z "$EMAIL" || -z "$PASSWORD" ]]; then
      echo "Login screen is visible, but AGOR_ANDROID_EMAIL or AGOR_ANDROID_PASSWORD is not set." >&2
      exit 2
    fi

    echo "Logging in to $SERVER_URL as AGOR_ANDROID_EMAIL..."
    if [[ -n "$(node_for_res "login-server-url" "$xml")" ]] &&
      [[ -n "$(node_for_res "login-email" "$xml")" ]] &&
      [[ -n "$(node_for_res "login-password" "$xml")" ]] &&
      [[ -n "$(node_for_res "login-submit" "$xml")" ]]; then
      replace_text_res "login-server-url" "$SERVER_URL" "$xml"
      dump_ui "$xml"
      replace_text_res "login-email" "$EMAIL" "$xml"
      dump_ui "$xml"
      replace_text_res "login-password" "$PASSWORD" "$xml"
      dump_ui "$xml"
      tap_res "login-submit" "$xml"
    else
      # Fallback for older builds without Compose testTag resources.
      mapfile -t edit_nodes < <(tr '>' '\n' <"$xml" | grep 'class="android.widget.EditText"')
      if (( ${#edit_nodes[@]} < 3 )); then
        echo "Login screen detected but editable fields were not found in fallback flow." >&2
        return 1
      fi
      replace_text_node "${edit_nodes[0]}" "$SERVER_URL"
      replace_text_node "${edit_nodes[1]}" "$EMAIL"
      replace_text_node "${edit_nodes[2]}" "$PASSWORD"
      local sign_in_button
      sign_in_button="$(tr '>' '\n' <"$xml" | grep 'class="android.widget.Button"' | head -n1)"
      if [[ -n "$sign_in_button" ]]; then
        local tap_button_node
        tap_button_node="$(node_midpoint "$sign_in_button")"
        adb_shell input tap "$(echo "$tap_button_node" | awk '{print $1}')" "$(echo "$tap_button_node" | awk '{print $2}')"
      elif ! tap_text "Sign in" "$xml"; then
        adb_shell input keyevent 66 >/dev/null 2>&1 || true
      fi
    fi
  fi

  local after="$WORKDIR/after-login.xml"
  if ! wait_for_not_login "$after"; then
    capture_screenshot "login-timeout"
    cp "$after" "$SCREENSHOT_DIR/login-timeout.xml" || true
    echo "Timed out waiting after login." >&2
    exit 1
  fi
}

open_drawer() {
  local xml="$1"
  if [[ -n "$(node_for_res "main-open-drawer" "$xml")" ]]; then
    tap_res "main-open-drawer" "$xml"
  elif [[ -n "$(node_for_res "chat-open-drawer" "$xml")" ]]; then
    tap_res "chat-open-drawer" "$xml"
  elif [[ -n "$(node_for_res "settings-open-drawer" "$xml")" ]]; then
    tap_res "settings-open-drawer" "$xml"
  elif [[ -n "$(node_for_content_desc "Open navigation panel" "$xml")" ]]; then
    tap_text "Open navigation panel" "$xml"
  elif [[ -n "$(node_for_content_desc "Open nav panel" "$xml")" ]]; then
    tap_text "Open nav panel" "$xml"
  elif [[ -n "$(node_for_content_desc "Zavřít navigační panel" "$xml")" ]]; then
    # Some locales expose only the close drawer text, indicating a Material drawer control exists.
    # Swipe from the edge is a stable fallback on these builds.
    adb_shell input swipe 15 400 520 400 220
  else
    # Fallback for builds without stable test tags: edge-swipe to open the modal drawer.
    adb_shell input swipe 15 450 520 450 220
  fi
  sleep 1
  dump_ui "$WORKDIR/sidebar.xml"
  if ! node_for_text "BOARDS" "$WORKDIR/sidebar.xml" >/dev/null &&
    ! node_for_res "sidebar-list" "$WORKDIR/sidebar.xml" &&
    ! node_for_res "sidebar-session-row" "$WORKDIR/sidebar.xml" &&
    ! node_for_res "sidebar-worktree-row" "$WORKDIR/sidebar.xml" &&
    ! node_for_res "sidebar-board-row" "$WORKDIR/sidebar.xml"; then
    return 1
  fi
}

tap_sidebar_row() {
  # A generic fallback for rows without testTags: tap the first likely board/worktree row.
  adb_shell input tap 440 420
}

exercise_sidebar_scroll() {
  echo "Exercising sidebar scroll..."
  adb_shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true
  for _ in $(seq 1 "$SCROLL_LOOPS"); do
    adb_shell input swipe 280 1800 280 500 300 >/dev/null
    sleep 0.2
  done
  for _ in $(seq 1 "$SCROLL_LOOPS"); do
    adb_shell input swipe 280 500 280 1800 300 >/dev/null
    sleep 0.2
  done
  capture_gfxinfo "sidebar-scroll"
}

open_first_session() {
  local xml="$WORKDIR/sidebar.xml"
  dump_ui "$xml"
  if [[ -n "$(node_for_res "sidebar-session-row" "$xml")" ]]; then
    tap_res "sidebar-session-row" "$xml"
    if wait_for_res "chat-list" "$WORKDIR/chat.xml"; then
      return 0
    fi
  else
    if [[ -n "$(node_for_res "sidebar-board-row" "$xml")" ]]; then
      tap_res "sidebar-board-row" "$xml"
      sleep 0.5
      if wait_for_res "chat-list" "$WORKDIR/chat.xml"; then
        return 0
      fi
    fi
    if ! has_node_for_chat_list "$WORKDIR/chat.xml" && [[ -n "$(node_for_res "sidebar-worktree-row" "$xml")" ]]; then
      tap_res "sidebar-worktree-row" "$xml"
      sleep 0.5
      if wait_for_res "chat-list" "$WORKDIR/chat.xml"; then
        return 0
      fi
    fi
    if ! has_node_for_chat_list "$WORKDIR/chat.xml"; then
      tap_sidebar_row
      sleep 0.5
      if wait_for_res "chat-list" "$WORKDIR/chat.xml"; then
        return 0
      fi
    fi
  fi

  if ! has_node_for_chat_list "$WORKDIR/chat.xml"; then
    capture_screenshot "no-session-row"
    cp "$xml" "$SCREENSHOT_DIR/no-session-row.xml" || true
    echo "No session row found in the drawer; skipping chat steps."
    return 1
  fi

  if ! wait_for_res "chat-list" "$WORKDIR/chat.xml"; then
    capture_screenshot "chat-open-timeout"
    cp "$WORKDIR/chat.xml" "$SCREENSHOT_DIR/chat-open-timeout.xml" || true
    echo "Timed out opening first session." >&2
    return 1
  fi
}

exercise_chat_scroll() {
  echo "Exercising chat scroll..."
  adb_shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true
  for _ in $(seq 1 "$SCROLL_LOOPS"); do
    adb_shell input swipe 540 1800 540 500 300 >/dev/null
    sleep 0.2
  done
  for _ in $(seq 1 "$SCROLL_LOOPS"); do
    adb_shell input swipe 540 500 540 1800 300 >/dev/null
    sleep 0.2
  done
  capture_gfxinfo "chat-scroll"
}

summarize_gfx() {
  local file="$1"
  if [[ ! -f "$file" ]]; then
    return 0
  fi
  grep -E 'Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile' "$file" || true
}

echo "=== Agor Android Smoke Test ==="
echo "Package: $PKG"
echo "Screenshots/logs: $SCREENSHOT_DIR"
echo "Server URL: $SERVER_URL"
echo ""

install_if_requested

if [[ "$RESET_APP" == "1" ]]; then
  adb_shell pm clear "$PKG" >/dev/null || true
fi

"${ADB[@]}" logcat -c || true
launch_app
sleep 2
login_if_needed

if ! wait_for_home "$WORKDIR/home.xml"; then
  capture_screenshot "home-timeout"
  cp "$WORKDIR/home.xml" "$SCREENSHOT_DIR/home-timeout.xml" || true
  echo "Timed out waiting for home screen." >&2
  exit 1
fi
capture_screenshot "home"
cp "$WORKDIR/home.xml" "$SCREENSHOT_DIR/home.xml"

open_drawer "$WORKDIR/home.xml"
capture_screenshot "sidebar"
cp "$WORKDIR/sidebar.xml" "$SCREENSHOT_DIR/sidebar.xml"
exercise_sidebar_scroll

if open_first_session; then
  capture_screenshot "chat"
  cp "$WORKDIR/chat.xml" "$SCREENSHOT_DIR/chat.xml"
  exercise_chat_scroll
else
  echo "Skipping chat interaction checks; no session row was available in sidebar."
fi

capture_logcat "logcat"

echo ""
echo "Sidebar frame summary:"
summarize_gfx "$SCREENSHOT_DIR/sidebar-scroll-gfxinfo.txt"
echo ""
echo "Chat frame summary:"
summarize_gfx "$SCREENSHOT_DIR/chat-scroll-gfxinfo.txt"
echo ""
echo "Result: PASS"
