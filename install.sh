#!/usr/bin/env bash

set -uo pipefail

cd "$(dirname "$(readlink -f "$0")")"

PKG=com.oberon.healthbridge
UI_DUMP=/sdcard/healthbridge-ui.xml

BUILD=1
VARIANT=debug
ALL=0
START=1
TARGETS=()

usage() {
    cat <<'EOF'
Install HealthBridge on a phone and leave it ready to answer.

The install itself is one line (adb install -r). The rest of this script is
the steps that come after and are easy to forget, above all the battery
optimisation exemption: without it, a read that takes 5 seconds on an awake
phone took 52 with the screen off for a few hours, and callers time out.

    ./install.sh                  the only connected phone
    ./install.sh "moto g24"       by model, serial or part of the name
    ./install.sh --all            every connected phone
    ./install.sh --no-build       skip gradle, use the APK already built
    ./install.sh --release        install the release variant
    ./install.sh --no-start       install only, do not start the server

Needs adb and python3. curl is used for the final check, if present.

Set HEALTHBRIDGE_PAIR_CMD to a command that stores the key on this PC; it is
called as "$HEALTHBRIDGE_PAIR_CMD <key>" once the server is up.
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --no-build)  BUILD=0 ;;
        --release)   VARIANT=release ;;
        --debug)     VARIANT=debug ;;
        --all)       ALL=1 ;;
        --no-start)  START=0 ;;
        -h|--help)   usage; exit 0 ;;
        -*)          echo "unknown option: $1" >&2; exit 2 ;;
        *)           TARGETS+=("$1") ;;
    esac
    shift
done

say()  { printf '%s\n' "$*"; }
step() { printf '\n\033[1m%s\033[0m\n' "$*"; }
warn() { printf '\033[33m%s\033[0m\n' "$*" >&2; }
die()  { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

command -v adb >/dev/null     || die "adb is not installed."
command -v python3 >/dev/null || die "python3 is not installed."

serials_matching() {
    local needle="${1:-}"
    adb devices -l 2>/dev/null | awk 'NR>1 && $2 == "device"' | while read -r line; do
        [ -z "$needle" ] && { echo "${line%% *}"; continue; }
        printf '%s' "$line" | tr 'A-Z' 'a-z' | grep -qF "$(printf '%s' "$needle" | tr 'A-Z ' 'a-z_')" \
            && echo "${line%% *}"
    done
}

label_of() {
    local model
    model=$(adb -s "$1" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    printf '%s (%s)' "${model:-unknown}" "$1"
}

SERIALS=()
if [ ${#TARGETS[@]} -gt 0 ]; then
    for target in "${TARGETS[@]}"; do
        found=$(serials_matching "$target")
        [ -z "$found" ] && die "no connected phone matches \"$target\"."
        [ "$(echo "$found" | wc -l)" -gt 1 ] && die "\"$target\" is ambiguous:"$'\n'"$found"
        SERIALS+=("$found")
    done
else
    mapfile -t SERIALS < <(serials_matching '')
    [ ${#SERIALS[@]} -eq 0 ] && die "no phone connected to adb."
    if [ ${#SERIALS[@]} -gt 1 ] && [ $ALL -eq 0 ]; then
        say "More than one phone is connected:"
        for serial in "${SERIALS[@]}"; do say "  $(label_of "$serial")"; done
        die "pick one (./install.sh \"moto g24\") or take them all (--all)."
    fi
fi

APK="app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"

if [ $BUILD -eq 1 ]; then
    step "Building ($VARIANT)"
    task="assemble$(printf '%s' "${VARIANT:0:1}" | tr 'a-z' 'A-Z')${VARIANT:1}"
    ./gradlew "$task" -q || die "gradle stopped."
fi

[ -f "$APK" ] || die "missing $APK - drop --no-build and rebuild."
say "APK: $APK ($(du -h "$APK" | cut -f1))"

mapfile -t PERMS < <(grep -o 'android\.permission\.health\.[A-Z0-9_]*' \
    app/src/main/AndroidManifest.xml | sort -u)

[ ${#PERMS[@]} -eq 0 ] && die "no health permissions found in the manifest."
say "Permissions declared: ${#PERMS[@]}"

granted_count() {
    local serial="$1" permission n=0 dump
    dump=$(adb -s "$serial" shell dumpsys package "$PKG" 2>/dev/null)

    for permission in "${PERMS[@]}"; do
        printf '%s' "$dump" | grep -q "$permission: granted=true" && n=$((n + 1))
    done

    echo "$n"
}

grant_health() {
    local serial="$1" permission before after

    before=$(granted_count "$serial")

    if [ "$before" -eq "${#PERMS[@]}" ]; then
        say "  permissions: all ${#PERMS[@]} already granted"
        return 0
    fi

    for permission in "${PERMS[@]}"; do
        adb -s "$serial" shell pm grant "$PKG" "$permission" >/dev/null 2>&1
    done

    after=$(granted_count "$serial")
    say "  permissions: $after of ${#PERMS[@]} granted with pm grant"

    if [ "$after" -lt "${#PERMS[@]}" ]; then
        warn "  grant the rest from the app: open it and press \"Grant the permissions\"."
        return 1
    fi
}

exempt_doze() {
    local serial="$1"

    if adb -s "$serial" shell dumpsys deviceidle whitelist 2>/dev/null | grep -q "$PKG"; then
        say "  battery optimisation: already exempt"
        return 0
    fi

    adb -s "$serial" shell dumpsys deviceidle whitelist "+$PKG" >/dev/null 2>&1

    if adb -s "$serial" shell dumpsys deviceidle whitelist 2>/dev/null | grep -q "$PKG"; then
        say "  battery optimisation: exempt"
        return 0
    fi

    warn "  battery optimisation: could not exempt it. Open the app and press"
    warn "  \"Exclude from optimisation\", otherwise reads become very slow"
    warn "  after a few hours with the screen off."
    return 1
}

server_running() {
    adb -s "$1" shell dumpsys activity services "$PKG" 2>/dev/null \
        | grep -q "ControlService"
}

app_in_front() {
    adb -s "$1" shell dumpsys activity activities 2>/dev/null \
        | grep -q "topResumedActivity.*$PKG"
}

scroll_to_top() {
    local serial="$1" size width height x from to

    size=$(adb -s "$serial" shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | tail -1)
    width=${size%x*}
    height=${size#*x}
    [ -z "$width" ] && return 0

    x=$((width / 2))
    from=$((height / 4))
    to=$((height * 3 / 4))

    for _ in 1 2 3; do
        adb -s "$serial" shell input swipe "$x" "$from" "$x" "$to" 120 >/dev/null 2>&1
    done
    sleep 1
}

read_screen() {
    local serial="$1"

    adb -s "$serial" shell uiautomator dump "$UI_DUMP" >/dev/null 2>&1
    adb -s "$serial" shell cat "$UI_DUMP" 2>/dev/null | tr -d '\r' | python3 -c '
import re, sys, xml.etree.ElementTree as ET

START_LABELS = ("Start the server", "Avvia il server")

try:
    root = ET.fromstring(sys.stdin.read())
except (ET.ParseError, ValueError):
    print("ERROR could not read the screen")
    sys.exit(0)

nodes = [(n, (n.get("text") or "").strip()) for n in root.iter("node")]
texts = [t for _, t in nodes]

url = next((t for t in texts if re.match(r"https?://\S+\?t=", t)), "")
if url:
    print("URL " + url)
    print("KEY " + url.rsplit("?t=", 1)[-1])

address = next((t for t in texts if re.fullmatch(r"\d+\.\d+\.\d+\.\d+:\d+", t)), "")
if address:
    print("ADDRESS " + address)

for node, text in nodes:
    if text in START_LABELS:
        box = re.findall(r"-?\d+", node.get("bounds") or "")
        if len(box) == 4:
            print("TAP %d %d" % ((int(box[0]) + int(box[2])) // 2,
                                 (int(box[1]) + int(box[3])) // 2))
        break
'
    adb -s "$serial" shell rm -f "$UI_DUMP" >/dev/null 2>&1
}

field_of() {
    printf '%s\n' "$2" | sed -n "s/^$1 //p" | head -1
}

start_server() {
    local serial="$1" screen tap

    adb -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
    adb -s "$serial" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
    sleep 3

    if ! app_in_front "$serial"; then
        warn "  the app is not on screen: the phone is probably locked."
        warn "  unlock it and run this again, or open HealthBridge and press"
        warn "  \"Start the server\": nobody can type your PIN for you."
        return 1
    fi

    if ! server_running "$serial"; then
        scroll_to_top "$serial"
        screen=$(read_screen "$serial")
        tap=$(field_of TAP "$screen")

        if [ -z "$tap" ]; then
            warn "  $(field_of ERROR "$screen")"
            warn "  could not find the start button: start the server by hand."
            return 1
        fi

        adb -s "$serial" shell input tap $tap >/dev/null 2>&1
        sleep 4
    fi

    if ! server_running "$serial"; then
        warn "  the server did not start: open the app and press \"Start the server\"."
        return 1
    fi

    scroll_to_top "$serial"
    screen=$(read_screen "$serial")

    URL=$(field_of URL "$screen")
    KEY=$(field_of KEY "$screen")
    ADDRESS=$(field_of ADDRESS "$screen")

    say "  server: listening on ${ADDRESS:-unknown address}"
    adb -s "$serial" shell input keyevent KEYCODE_BACK >/dev/null 2>&1

    if [ -z "$KEY" ]; then
        warn "  could not read the key off the screen: take it from the app."
        return 1
    fi

    say "  key: $KEY"
}

pair_key() {
    local command="${HEALTHBRIDGE_PAIR_CMD:-}"

    [ -z "$command" ] && return 0

    if $command "$KEY" >/dev/null 2>&1; then
        say "  key stored on this PC"
    else
        warn "  the key is $KEY but storing it failed: run"
        warn "    $command $KEY"
        return 1
    fi
}

verify_read() {
    local answer

    command -v curl >/dev/null || return 0
    [ -z "$URL" ] && return 0

    answer=$(curl -fsS --max-time 90 "${URL%/*}/api/probe?t=$KEY" 2>&1)

    if printf '%s' "$answer" | grep -q '"state": *"ok"'; then
        local missing
        missing=$(printf '%s' "$answer" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except ValueError:
    print("unknown")
else:
    print(", ".join(data.get("missing") or []) or "none")
' 2>/dev/null)
        say "  read succeeded over the network - missing permissions: ${missing:-none}"
    else
        warn "  the test read did not go through. The phone is installed and"
        warn "  running; check that this PC is on the same network:"
        warn "    $URL"
    fi
}

FAILED=0
URL=""
KEY=""
ADDRESS=""

for serial in "${SERIALS[@]}"; do
    step "Installing on $(label_of "$serial")"

    if ! out=$(adb -s "$serial" install -r "$APK" 2>&1); then
        if printf '%s' "$out" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match"; then
            warn "  signed with a different key than the installed one: reinstalling"
            warn "  (this loses the key, the permissions and the quiet hours)"
            adb -s "$serial" uninstall "$PKG" >/dev/null 2>&1
            out=$(adb -s "$serial" install -r "$APK" 2>&1) || {
                warn "  $out"; FAILED=1; continue; }
        else
            warn "  $out"; FAILED=1; continue
        fi
    fi
    say "  installed"

    grant_health "$serial" || FAILED=1
    exempt_doze "$serial"  || FAILED=1

    if [ $START -eq 1 ]; then
        if start_server "$serial"; then
            pair_key || FAILED=1
            verify_read
        else
            FAILED=1
        fi
    else
        say "  server not started (--no-start)"
    fi
done

step "Done"
if [ $FAILED -eq 0 ] && [ $START -eq 0 ]; then
    say "Installed, but the server is stopped: open the app and press"
    say "\"Start the server\", or run this again without --no-start."
elif [ $FAILED -eq 0 ]; then
    say "HealthBridge is answering. Read the last hour of heart rate with:"
    say "  curl \"${URL%/*}/api/heart?t=$KEY&minutes=60\""
    say "Or open $URL in a browser."
else
    warn "Something was left behind: see the yellow lines above."
    exit 1
fi
