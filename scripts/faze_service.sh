#!/data/data/com.termux/files/usr/bin/bash
#
# Run Faze in the background, restart it if it crashes, no Termux window needed.
#   faze start                start the background app
#   faze start --voice        start it if needed, then listen: one spoken exchange, handled by the background service
#                             (no app opens; the /overlay page shows it, see Tasker setup)  (Tasker: long-press both volume keys)
#   faze start --live         the dedicated live voice mode: a spoken conversation with Gemini Live (needs: pip install websockets)
#   faze start --app          start it if needed, then open the web app in voice mode
#   faze start --cli          pause the background app and chat in this terminal; it comes back when you exit
#   faze voice | faze live | faze app | faze cli     same as start --voice / --live / --app / --cli
#   faze overlay [--live]     print the link the Tasker Scene WebView should load (--live: the link starts a live conversation)
#   faze voice-stop           stop the current voice exchange (Tasker overlay Stop button)
#   faze close                stop the exchange AND ask Tasker to close the overlay, even if the service is down
#   faze open                 open the web app, no voice mode (Tasker overlay Open Faze button)
#   faze stop | restart | status | logs
# Env (for tests): FAZE_CMD, FAZE_STATE, FAZE_LOG, FAZE_RESTART_DELAY, FAZE_PORT, FAZE_OPEN, FAZE_READY_TIMEOUT, FAZE_HTTP,
#   FAZE_SWEEP_PATTERN, FAZE_QUICK_EXIT, FAZE_LOOP_LIMIT, FAZE_LOOP_SLOW

# Tasker's "Run Shell" starts with Android's PATH and, with Use Root, root's HOME: neither is Termux's, which is
# why `faze` was "not found" (exit 127). Put Termux's paths back, and as root drop to Termux's own user so nothing
# root-owned ends up inside Termux's home.
TERMUX_HOME="${FAZE_TERMUX_HOME:-/data/data/com.termux/files/home}"
TERMUX_PREFIX="${FAZE_TERMUX_PREFIX:-/data/data/com.termux/files/usr}"
if [ -d "$TERMUX_PREFIX/bin" ]; then
    case ":$PATH:" in *":$TERMUX_PREFIX/bin:"*) ;; *) export PATH="$TERMUX_PREFIX/bin:$PATH" ;; esac
    export PREFIX="${PREFIX:-$TERMUX_PREFIX}"
fi
if [ ! -d "${HOME:-/nonexistent}/felix-ai" ] && [ -d "$TERMUX_HOME/felix-ai" ]; then export HOME="$TERMUX_HOME"; fi
if [ "${FAZE_UID:-$(id -u)}" = "0" ] && [ -d "$TERMUX_HOME" ] && [ -z "$FAZE_DROPPED" ]; then
    owner="${FAZE_TERMUX_OWNER:-$(stat -c %u "$TERMUX_HOME" 2> /dev/null)}"
    if [ -n "$owner" ] && [ "$owner" != "0" ]; then
        quoted=""
        for a in "$0" "$@"; do quoted="$quoted $(printf '%q' "$a")"; done
        exec "${FAZE_SU:-su}" "$owner" -c "export FAZE_DROPPED=1 HOME=$TERMUX_HOME PATH=$TERMUX_PREFIX/bin:\$PATH PREFIX=$TERMUX_PREFIX; bash$quoted"
    fi
fi

STATE="${FAZE_STATE:-$HOME/.felix}"
PIDFILE="$STATE/faze.pid"
STOPFILE="$STATE/faze.stop"
LOG="${FAZE_LOG:-$HOME/felix-ai/faze.log}"
CMD="${FAZE_CMD:-$HOME/felix-ai/faze.sh}"
DELAY="${FAZE_RESTART_DELAY:-5}"
PORT="${FAZE_PORT:-8787}"
mkdir -p "$STATE"

running() { [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE" 2> /dev/null)" 2> /dev/null; }

# "pid command" of whatever is listening on our port, or nothing.
port_owner() {
    python3 - "$PORT" << 'PY' 2> /dev/null
import os, sys
port = int(sys.argv[1])
inodes = set()
for path in ("/proc/net/tcp", "/proc/net/tcp6"):
    try:
        rows = open(path).read().splitlines()[1:]
    except OSError:
        continue
    for row in rows:
        f = row.split()
        if len(f) > 9 and f[3] == "0A" and int(f[1].rsplit(":", 1)[1], 16) == port:
            inodes.add("socket:[" + f[9] + "]")
for pid in filter(str.isdigit, os.listdir("/proc")):
    try:
        for fd in os.listdir("/proc/%s/fd" % pid):
            if os.readlink("/proc/%s/fd/%s" % (pid, fd)) in inodes:
                cmd = open("/proc/%s/cmdline" % pid).read().replace("\0", " ").strip()
                print(pid, cmd[:100])
                sys.exit(0)
    except OSError:
        pass
PY
}
holder_hint() { local owner; owner="$(port_owner)"; [ -n "$owner" ] && echo " (port $PORT is held by pid $owner)"; return 0; }

supervise() {
    local quick=0 delay="$DELAY" began limit="${FAZE_LOOP_LIMIT:-5}"
    while [ ! -f "$STOPFILE" ]; do
        began="$(date +%s)"
        "$CMD" >> "$LOG" 2>&1
        code=$?
        [ -f "$STOPFILE" ] && break
        if [ $(( $(date +%s) - began )) -lt "${FAZE_QUICK_EXIT:-10}" ]; then quick=$(( quick + 1 )); else quick=0; delay="$DELAY"; fi
        if [ "$quick" -ge "$limit" ]; then
            delay="${FAZE_LOOP_SLOW:-60}"
            [ "$quick" -eq "$limit" ] && echo "[service] faze keeps exiting right after it starts$(holder_hint); retrying every ${delay}s. Run: faze stop (it also clears stray copies)" >> "$LOG"
        fi
        echo "[service] faze exited (code $code); restarting in ${delay}s" >> "$LOG"
        sleep "$delay"
    done
    rm -f "$PIDFILE"
}
start_service() {
    if running; then echo "already running (pid $(cat "$PIDFILE"))"; return 0; fi
    rm -f "$STOPFILE"
    command -v termux-wake-lock > /dev/null 2>&1 && termux-wake-lock
    nohup bash "$0" _supervise > /dev/null 2>&1 &
    echo $! > "$PIDFILE"
    echo "started (pid $!). Logs: $LOG"
}

stop_service() {
    touch "$STOPFILE"
    local was=0
    if running; then
        pid="$(cat "$PIDFILE")"
        pkill -TERM -P "$pid" 2> /dev/null
        kill "$pid" 2> /dev/null
        was=1
    fi
    rm -f "$PIDFILE"
    # a copy started some other way (an old terminal session, a boot script) would hold the port and make every restart fail
    local sweep="${FAZE_SWEEP_PATTERN:-}"
    [ -z "$sweep" ] && [ -z "${FAZE_CMD:-}" ] && sweep='python.*orchestrator\.py'   # a test run (FAZE_CMD set) must never touch the real app
    if [ -n "$sweep" ] && pkill -f "$sweep" 2> /dev/null; then
        sleep 0.5
        echo "stopped (and cleared a stray copy)"
    elif [ "$was" = 1 ]; then echo "stopped"
    else echo "not running"; fi
}

# the web app answers on its port (bash's own /dev/tcp, so nothing extra is needed)
ready() { (exec 3<> "/dev/tcp/127.0.0.1/$PORT") 2> /dev/null; }

wait_ready() {
    local tries=$(( ${FAZE_READY_TIMEOUT:-40} * 2 ))
    while [ "$tries" -gt 0 ]; do
        ready && return 0
        sleep 0.5
        tries=$(( tries - 1 ))
    done
    return 1
}

open_url() {
    if [ -n "$FAZE_OPEN" ]; then $FAZE_OPEN "$1"
    elif command -v am > /dev/null 2>&1; then am start -a android.intent.action.VIEW -d "$1" > /dev/null 2>&1 || termux-open-url "$1"
    elif command -v xdg-open > /dev/null 2>&1; then xdg-open "$1" > /dev/null 2>&1
    else echo "open this yourself: $1"; fi
}

# POST a JSON body to the running service with the saved token. Prints the reply body.
api_post() {
    local token; token="$(cat "$STATE/token" 2> /dev/null)"
    [ -n "$token" ] || { echo "no token yet; see: faze logs" >&2; return 1; }
    if [ -n "$FAZE_HTTP" ]; then $FAZE_HTTP "$1" "$2" "$token"
    elif command -v curl > /dev/null 2>&1; then
        curl -fsS -m 10 -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" -d "$2" "http://localhost:$PORT$1"
    else
        python3 - "http://localhost:$PORT$1" "$2" "$token" << 'PY'
import sys, urllib.request
req = urllib.request.Request(sys.argv[1], sys.argv[2].encode(), {"Authorization": "Bearer " + sys.argv[3], "Content-Type": "application/json"})
print(urllib.request.urlopen(req, timeout=10).read().decode())
PY
    fi
}

usage() { echo "usage: $0 start [--voice|--live|--app|--cli] | voice | live | app | cli | overlay [--live] | voice-stop | close | open | stop | restart | status | logs"; }

start_command() {
    local mode=""
    for arg in "$@"; do
        case "$arg" in
            --voice) [ -n "$mode" ] && { usage; exit 2; }; mode=voice ;;
            --app) [ -n "$mode" ] && { usage; exit 2; }; mode=app ;;
            --live) [ -n "$mode" ] && { usage; exit 2; }; mode=live ;;
            --cli) [ -n "$mode" ] && { usage; exit 2; }; mode=cli ;;
            *) usage; exit 2 ;;
        esac
    done
    case "$mode" in
        "") start_service ;;
        voice)
            start_service
            if ! wait_ready; then echo "the service did not come up in time; see: faze logs"; exit 1; fi
            local reply
            if ! reply="$(api_post /api/voice/start '{"overlay": true}')"; then echo "could not start listening; see: faze logs"; exit 1; fi
            case "$reply" in
                *'"started": true'*) echo "listening" ;;
                *) echo "already listening" ;;
            esac
            ;;
        live)
            start_service
            if ! wait_ready; then echo "the service did not come up in time; see: faze logs"; exit 1; fi
            local reply
            if ! reply="$(api_post /api/voice/start '{"overlay": true, "live": true}')"; then echo "could not start the live conversation; see: faze logs"; exit 1; fi
            case "$reply" in
                *'"started": true'*) echo "live: listening" ;;
                *) echo "already listening" ;;
            esac
            ;;
        app)
            start_service
            if ! wait_ready; then echo "the app did not come up in time; see: faze logs"; exit 1; fi
            local link="http://localhost:$PORT/#voice"
            [ -f "$STATE/token" ] && link="http://localhost:$PORT/#t=$(cat "$STATE/token")&voice"
            open_url "$link"
            echo "opened in voice mode"
            ;;
        cli)
            local was=0
            if running; then was=1; stop_service > /dev/null; sleep 1; fi
            "$CMD" --cli
            local code=$?
            rm -f "$STOPFILE"
            [ "$was" -eq 1 ] && start_service > /dev/null
            exit "$code"
            ;;
    esac
}

case "${1:-status}" in
    _supervise) supervise ;;
    start) shift; start_command "$@" ;;
    voice) start_command --voice ;;
    app) start_command --app ;;
    live) start_command --live ;;
    overlay)
        if [ -f "$STATE/token" ]; then echo "http://localhost:$PORT/overlay#t=$(cat "$STATE/token")&auto$([ "$2" = "--live" ] && echo "&live")"
        else echo "no token yet: run faze start first"; exit 1; fi
        ;;
    cli) start_command --cli ;;
    close)
        api_post /api/voice/stop '{"close": true}' > /dev/null 2>&1 || true
        if am broadcast -a net.dinglisch.android.tasker.ACTION_TASK --es task_name "${FAZE_CLOSE_TASK:-Faze Overlay Close}" > /dev/null 2>&1; then echo "closed"
        else echo "could not reach Tasker (is Allow External Access on?)"; exit 1; fi
        ;;
    voice-stop)
        if api_post /api/voice/stop '{}' > /dev/null; then echo "stopped"; else echo "could not reach the service; see: faze logs"; exit 1; fi
        ;;
    open)
        link="http://localhost:$PORT/"
        [ -f "$STATE/token" ] && link="http://localhost:$PORT/#t=$(cat "$STATE/token")"
        open_url "$link"
        echo "opened"
        ;;
    stop) stop_service ;;
    restart) bash "$0" stop; sleep 1; bash "$0" start ;;
    status)
        if running; then
            echo "running (pid $(cat "$PIDFILE"))"
            if [ -f "$STATE/token" ]; then echo "Open: http://localhost:$PORT/#t=$(cat "$STATE/token")"; fi
            exit 0
        else
            if ready; then echo "not running as a service, but something is answering on port $PORT$(holder_hint). Run: faze stop"
            else echo "not running"; fi
            exit 1
        fi
        ;;
    logs) tail -n 40 "$LOG" ;;
    *) usage; exit 2 ;;
esac
