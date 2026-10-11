#!/data/data/com.termux/files/usr/bin/bash
#
# Run Faze in the background, restart it if it crashes, no Termux window needed.
#   faze start                start the background app
#   faze start --voice        start it if needed, then open the app in voice mode  (Tasker: long-press both volume keys)
#   faze start --cli          pause the background app and chat in this terminal; it comes back when you exit
#   faze voice | faze cli     same as start --voice / start --cli
#   faze stop | restart | status | logs
# Env (for tests): FAZE_CMD, FAZE_STATE, FAZE_LOG, FAZE_RESTART_DELAY, FAZE_PORT, FAZE_OPEN, FAZE_READY_TIMEOUT

STATE="${FAZE_STATE:-$HOME/.felix}"
PIDFILE="$STATE/faze.pid"
STOPFILE="$STATE/faze.stop"
LOG="${FAZE_LOG:-$HOME/felix-ai/faze.log}"
CMD="${FAZE_CMD:-$HOME/felix-ai/faze.sh}"
DELAY="${FAZE_RESTART_DELAY:-5}"
PORT="${FAZE_PORT:-8787}"
mkdir -p "$STATE"

running() { [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE" 2> /dev/null)" 2> /dev/null; }

supervise() {
    while [ ! -f "$STOPFILE" ]; do
        "$CMD" >> "$LOG" 2>&1
        code=$?
        [ -f "$STOPFILE" ] && break
        echo "[service] faze exited (code $code); restarting in ${DELAY}s" >> "$LOG"
        sleep "$DELAY"
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
    if running; then
        pid="$(cat "$PIDFILE")"
        pkill -TERM -P "$pid" 2> /dev/null
        kill "$pid" 2> /dev/null
        echo "stopped"
    else
        echo "not running"
    fi
    rm -f "$PIDFILE"
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

usage() { echo "usage: $0 start [--voice|--cli] | voice | cli | stop | restart | status | logs"; }

start_command() {
    local mode=""
    for arg in "$@"; do
        case "$arg" in
            --voice) [ -n "$mode" ] && { usage; exit 2; }; mode=voice ;;
            --cli) [ -n "$mode" ] && { usage; exit 2; }; mode=cli ;;
            *) usage; exit 2 ;;
        esac
    done
    case "$mode" in
        "") start_service ;;
        voice)
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
    cli) start_command --cli ;;
    stop) stop_service ;;
    restart) bash "$0" stop; sleep 1; bash "$0" start ;;
    status)
        if running; then
            echo "running (pid $(cat "$PIDFILE"))"
            if [ -f "$STATE/token" ]; then echo "Open: http://localhost:$PORT/#t=$(cat "$STATE/token")"; fi
            exit 0
        else
            echo "not running"
            exit 1
        fi
        ;;
    logs) tail -n 40 "$LOG" ;;
    *) usage; exit 2 ;;
esac
