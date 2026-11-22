#!/bin/sh
# Copy this TV's whole Clean My TV setup onto another TV of the same model.
#
#   tools/clone-tv.sh capture <this-tv-ip>              snapshot apps, files and settings into the bundle
#   tools/clone-tv.sh setup   <new-tv-ip> "<Name>"      apply the bundle to a new TV, greeting <Name>
#   tools/clone-tv.sh check   <tv-ip>                   show what is installed, disabled and set
#
# DOCK=pkg1,pkg2,... before setup replaces the dock apps (e.g. DOCK=com.google.android.youtube.tv,com.netflix.ninja).
# The bundle lives outside the repo (APKs and pictures): $BUNDLE, default ~/cleanyourtv-backup/clone-bundle.
# On the new TV first: Settings > Device Preferences > About > tap Build 7 times, then Developer options >
# USB debugging on (network debugging), note the IP, and press Allow on the TV when the computer connects.
# Nothing is uninstalled: apps are only disabled, and `pm enable --user 0 <pkg>` brings any back.
set -eu

ADB=${ADB:-"$HOME/Library/Android/sdk/platform-tools/adb"}
BUNDLE=${BUNDLE:-"$HOME/cleanyourtv-backup/clone-bundle"}
FILES=/sdcard/Android/data/com.gathin.tvhome/files
# Our own apps, copied exactly as installed. Streaming apps come from the Play Store (they need sign-in anyway).
OWN_APPS="com.gathin.tvhome io.github.sumyabazar1.weather com.gathin.tvhome.foryou com.poppy.scoreboard
com.cleanmytv.arcade.chess com.cleanmytv.arcade.highway com.cleanmytv.arcade.lantern com.cleanmytv.arcade.prism"
SECURE_KEYS="screensaver_components screensaver_enabled screensaver_activate_on_sleep screensaver_activate_on_dock"
SYSTEM_KEYS="screen_off_timeout"
STOCK_LAUNCHER=com.google.android.apps.tv.launcherx

die() { echo "ERROR: $*" >&2; exit 1; }
host() { case "$1" in *:*|emulator-*) echo "$1" ;; *) echo "$1:5555" ;; esac; }

connect() {
  T=$(host "$1")
  "$ADB" connect "$T" >/dev/null 2>&1 || true
  i=0
  until [ "$("$ADB" -s "$T" get-state 2>/dev/null)" = device ]; do
    i=$((i + 1)); [ $i -gt 30 ] && die "$T did not answer. Is network debugging on, and did you press Allow on the TV?"
    [ $i -eq 1 ] && echo "Waiting for $T (press Allow on the TV if it asks)..."
    sleep 2; "$ADB" connect "$T" >/dev/null 2>&1 || true
  done
}
sh_() { "$ADB" -s "$T" shell "$@" </dev/null | tr -d '\r'; }

capture() {
  connect "$1"
  mkdir -p "$BUNDLE/apks"
  echo "Capturing from $T ($(sh_ getprop ro.product.model), SDK $(sh_ getprop ro.build.version.sdk)) into $BUNDLE"
  sh_ getprop ro.product.model > "$BUNDLE/model.txt"
  sh_ getprop ro.build.version.sdk > "$BUNDLE/sdk.txt"
  for p in $OWN_APPS; do
    paths=$(sh_ pm path "$p" | sed -n 's/^package://p')
    [ -n "$paths" ] || { echo "  $p not installed here, skipped"; continue; }
    rm -rf "$BUNDLE/apks/$p"; mkdir -p "$BUNDLE/apks/$p"
    for a in $paths; do "$ADB" -s "$T" pull "$a" "$BUNDLE/apks/$p/" >/dev/null 2>&1; done
    echo "  app  $p"
  done
  sh_ pm list packages -d | sed -n 's/^package://p' | sort > "$BUNDLE/disabled.txt"
  echo "  disabled apps: $(wc -l < "$BUNDLE/disabled.txt" | tr -d ' ')"
  : > "$BUNDLE/settings.txt"
  for k in $SECURE_KEYS; do echo "secure $k $(sh_ settings get secure "$k")" >> "$BUNDLE/settings.txt"; done
  for k in $SYSTEM_KEYS; do echo "system $k $(sh_ settings get system "$k")" >> "$BUNDLE/settings.txt"; done
  sh_ settings get secure enabled_notification_listeners > "$BUNDLE/listeners.txt"
  sh_ pm list packages -3 | sed -n 's/^package://p' | sort > "$BUNDLE/third-party.txt"
  rm -rf "$BUNDLE/files"
  "$ADB" -s "$T" pull "$FILES" "$BUNDLE/" >/dev/null 2>&1
  echo "  launcher files: $(find "$BUNDLE/files" -type f | wc -l | tr -d ' ') ($(du -sh "$BUNDLE/files" | cut -f1))"
  date -u +%Y-%m-%dT%H:%MZ > "$BUNDLE/captured-at.txt"
  echo "Done. Next: tools/clone-tv.sh setup <new-tv-ip> \"<Name>\""
}

install_app() {
  dir="$BUNDLE/apks/$1"
  set -- "$dir"/*.apk
  if [ $# -eq 1 ]; then "$ADB" -s "$T" install -r "$1" >/dev/null; else "$ADB" -s "$T" install-multiple -r "$@" >/dev/null; fi
}

setup() {
  [ -d "$BUNDLE/apks" ] || die "no bundle at $BUNDLE; run capture first"
  NAME=${2:-}
  [ -n "$NAME" ] || die "give the welcome name: tools/clone-tv.sh setup <ip> \"Name\""
  connect "$1"
  model=$(sh_ getprop ro.product.model); sdk=$(sh_ getprop ro.build.version.sdk)
  echo "Setting up $T ($model, SDK $sdk) for \"$NAME\""
  if [ "$model" != "$(cat "$BUNDLE/model.txt")" ] || [ "$sdk" != "$(cat "$BUNDLE/sdk.txt")" ]; then
    [ "${FORCE:-}" = 1 ] || die "this is $model SDK $sdk, the bundle is from $(cat "$BUNDLE/model.txt") SDK $(cat "$BUNDLE/sdk.txt"). The disable list may not suit it; FORCE=1 to go on anyway."
    echo "  (FORCE=1: different model, going on)"
  fi

  echo "1/6 Apps"
  for d in "$BUNDLE"/apks/*/; do p=$(basename "$d"); install_app "$p" && echo "  installed $p"; done

  echo "2/6 Permissions"
  sh_ pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
  sh_ pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
  sh_ pm grant com.gathin.tvhome.foryou android.permission.READ_TV_LISTINGS 2>/dev/null || true
  sh_ cmd appops set com.gathin.tvhome AUTO_START allow 2>/dev/null || true
  for l in $(tr ':' ' ' < "$BUNDLE/listeners.txt"); do
    case "$l" in null|"") ;; *) sh_ cmd notification allow_listener "$l" >/dev/null 2>&1 && echo "  listener $l" ;; esac
  done

  echo "3/6 Launcher files (wallpapers, config) with greeting \"$NAME\""
  sh_ am start -n com.gathin.tvhome/.MainActivity >/dev/null   # creates its files folder
  sleep 3
  tmp=$(mktemp -d)
  cp -R "$BUNDLE/files" "$tmp/"
  python3 -I - "$tmp/files/config.json" "$NAME" "${DOCK:-}" <<'PY'
import json, sys
path, name, dock = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path, encoding="utf-8") as f: cfg = json.load(f)
cfg["greeting_name"] = name
# Vu remote: Settings 83 (the launcher's Settings screen), YouTube 293, Hotstar 337, Prime Video 295, Cricket 662 (SonyLIV), Cinema 664 (Zee5),
# mic/Jarvis 4355 (assist: what Ask Jarvis opens); nothing answers them once launcherx is disabled.
cfg.setdefault("app_keys", ["83:homesettings", "293:com.google.android.youtube.tv", "337:in.startv.hotstar",
    "295:com.amazon.amazonvideo.livingroom", "662:com.sonyliv", "664:com.graymatrix.did", "4355:assist", "225:homesettings"])
cfg.setdefault("restore_key_services", True)
if dock: cfg["dock_apps"] = [p.strip() for p in dock.split(",") if p.strip()]
with open(path, "w", encoding="utf-8") as f: json.dump(cfg, f, ensure_ascii=False, indent=2)
PY
  # adb push cannot create folders under Android/data on newer builds; stage, then copy as the shell user
  sh_ rm -rf /data/local/tmp/cmt-files
  "$ADB" -s "$T" push "$tmp/files" /data/local/tmp/cmt-files >/dev/null 2>&1 || die "pushing the launcher files failed"
  sh_ "mkdir -p $FILES && cp -R /data/local/tmp/cmt-files/. $FILES/ && rm -rf /data/local/tmp/cmt-files && echo ok" \
    | grep -q ok || die "copying the launcher files into place failed"
  rm -rf "$tmp"
  echo "  pushed $(find "$BUNDLE/files" -type f | wc -l | tr -d ' ') files"

  echo "4/6 Home screen"
  sh_ cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity >/dev/null

  echo "5/6 Debloat (disable, not uninstall)"
  n=0
  while read -r p; do
    [ -n "$p" ] || continue
    sh_ pm path "$p" | grep -q package: || continue
    sh_ pm disable-user --user 0 "$p" >/dev/null 2>&1 && n=$((n + 1))
  done < "$BUNDLE/disabled.txt"
  echo "  disabled $n apps"

  echo "6/6 Screensaver and sleep settings"
  while read -r ns k v; do
    case "$v" in null|"") continue ;; esac
    sh_ settings put "$ns" "$k" "$v"
  done < "$BUNDLE/settings.txt"

  sh_ am force-stop com.gathin.tvhome
  sh_ am start -n com.gathin.tvhome/.MainActivity >/dev/null
  # Remote buttons (app_keys in config.json, read when the helper connects, so after the files are in place):
  # on the Vu the Settings and app buttons only worked through the disabled stock launcher.
  sh_ settings put secure enabled_accessibility_services com.gathin.tvhome/.InputKeyService
  sh_ settings put secure accessibility_enabled 1
  missing=$(sh_ pm list packages | sed -n 's/^package://p' | sort | comm -13 - "$BUNDLE/third-party.txt" | comm -23 - "$BUNDLE/disabled.txt")
  echo
  echo "Done. Home is up with \"$NAME\"."
  [ -z "$missing" ] || { echo "Install these from the Play Store and sign in:"; echo "$missing" | sed 's/^/  /'; }
  echo "Choices made inside the launcher's own Settings screen (not config.json) start at their defaults."
  echo "If Home asks \"New card from Weather\" (or another add-on), answer it once on the TV."
}

check() {
  connect "$1"
  echo "$T: $(sh_ getprop ro.product.model), SDK $(sh_ getprop ro.build.version.sdk)"
  echo "Home: $(sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1)"
  for p in $OWN_APPS; do printf '  %-34s %s\n' "$p" "$(sh_ dumpsys package "$p" | sed -n 's/^ *versionName=//p' | head -1)"; done
  echo "Disabled: $(sh_ pm list packages -d | wc -l | tr -d ' ')"
  echo "Greeting: $(sh_ cat $FILES/config.json 2>/dev/null | sed -n 's/.*"greeting_name": *"\([^"]*\)".*/\1/p')"
  echo "Everyday pictures: $(sh_ ls $FILES/collections/everyday 2>/dev/null | wc -l | tr -d ' ')"
  echo "Screensaver: $(sh_ settings get secure screensaver_components)"
  echo "Key helper: $(sh_ settings get secure enabled_accessibility_services)"
}

case "${1:-}" in
  capture) [ $# -eq 2 ] || die "usage: $0 capture <ip>"; capture "$2" ;;
  setup) [ $# -eq 3 ] || die "usage: $0 setup <ip> \"Name\""; setup "$2" "$3" ;;
  check) [ $# -eq 2 ] || die "usage: $0 check <ip>"; check "$2" ;;
  *) sed -n '2,11p' "$0"; exit 2 ;;
esac
