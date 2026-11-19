#!/usr/bin/env bash
# Point the TV list screensaver at your Google Sheet and make it the TV's screensaver. The sheet must be shared as
# "Anyone with the link: Viewer". Install the app first with build-and-install.sh.
#
# Usage: TV_HOST=192.168.1.50 addons/tv-list/setup.sh 'https://docs.google.com/spreadsheets/d/<id>/edit'
#        TV_HOST=192.168.1.50 IDLE_MINUTES=10 addons/tv-list/setup.sh '<link>'   # start it after 10 idle minutes
#        TV_HOST=192.168.1.50 START=1 addons/tv-list/setup.sh '<link>'           # and show it now
#        TV_HOST=192.168.1.50 addons/tv-list/setup.sh                            # keep the sheet, set the screensaver
#        TV_HOST=192.168.1.50 addons/tv-list/setup.sh --off                      # previous screensaver back
set -euo pipefail
ARG="${1:-}"
cd "$(dirname "$0")/../../tools"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
PY=venv/bin/python
[ -x "$PY" ] || PY=venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
PKG=com.gathin.tvlist
DREAM=$PKG/.TvListDream
URI=content://$PKG.files
TMP=/data/local/tmp/cyt-tvlist-sheet.txt
# The screensaver the TV had before the first run, so --off can put it back.
BEFORE=/data/local/tmp/cyt-tvlist-before
IDLE_MINUTES="${IDLE_MINUTES:-0}"
tv() { "$PY" -I tv.py "$@"; }

[[ "$IDLE_MINUTES" =~ ^[0-9]+$ ]] && [ "$IDLE_MINUTES" -le 1440 ] || { echo "IDLE_MINUTES takes 1 to 1440" >&2; exit 1; }
tv "pm path $PKG; true" | grep -q package: \
  || { echo "the TV list screensaver is not on the TV: run addons/tv-list/build-and-install.sh first" >&2; exit 1; }

if [ "$ARG" = --off ]; then
  tv "if [ -f $BEFORE ]; then { read c; read e; } < $BEFORE; if [ \"\$c\" = null ]; then settings delete secure screensaver_components >/dev/null; else settings put secure screensaver_components \"\$c\"; fi; [ \"\$e\" = null ] || settings put secure screensaver_enabled \"\$e\"; rm -f $BEFORE; fi; echo screensaver: \$(settings get secure screensaver_components)"
  exit 0
fi

if [ -n "$ARG" ]; then
  # The id alone, from a full link or a bare id; only letters, digits, - and _ ever reach the TV's shell.
  ID="$(printf '%s' "$ARG" | sed -n 's#.*/spreadsheets/d/\([A-Za-z0-9_-]*\).*#\1#p')"
  [ -n "$ID" ] || ID="$ARG"
  [[ "$ID" =~ ^[A-Za-z0-9_-]{20,128}$ ]] || { echo "not a Google Sheets link or id: $ARG" >&2; exit 1; }
  tv "printf '%s' $ID > $TMP; content write --uri $URI/sheet.txt < $TMP; rm -f $TMP" >/dev/null
  echo "sheet: $ID"
fi

# Now playing: Android shows the media sessions only to an allowed notification listener (no notifications are read).
tv "cmd notification allow_listener /.MediaListener" >/dev/null || echo "note: could not allow now playing; the list works without it" >&2
tv "[ -f $BEFORE ] || { settings get secure screensaver_components; settings get secure screensaver_enabled; } > $BEFORE; settings put secure screensaver_components $DREAM; settings put secure screensaver_enabled 1" >/dev/null
[ "$IDLE_MINUTES" -eq 0 ] || tv "settings put system screen_off_timeout $((IDLE_MINUTES * 60000))" >/dev/null
echo "On the TV now:"
tv "s=\$(content read --uri $URI/sheet.txt 2>/dev/null); echo \"  sheet: \${s:-(none yet: pass your sheet link)}\"; echo \"  screen_off_timeout: \$(( \$(settings get system screen_off_timeout) / 60000 )) min\""
echo "Screensaver: TV list. Undo with: addons/tv-list/setup.sh --off"
[ -z "${START:-}" ] || tv "am start -n com.android.systemui/.Somnambulator >/dev/null; sleep 3; dumpsys dreams | grep mCurrentDreamName"
