#!/data/data/com.termux/files/usr/bin/bash
#
# Run Faze in the background, restart it if it crashes, no Termux window needed.
#   scripts/faze_service.sh start | stop | restart | status | logs
# Env (for tests): FAZE_CMD, FAZE_STATE, FAZE_LOG, FAZE_RESTART_DELAY

STATE="${FAZE_STATE:-$HOME/.felix}"
PIDFILE="$STATE/faze.pid"
STOPFILE="$STATE/faze.stop"
LOG="${FAZE_LOG:-$HOME/felix-ai/faze.log}"
CMD="${FAZE_CMD:-$HOME/felix-ai/faze.sh}"
DELAY="${FAZE_RESTART_DELAY:-5}"
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

case "${1:-status}" in
    _supervise) supervise ;;
    start)
        if running; then echo "already running (pid $(cat "$PIDFILE"))"; exit 0; fi
        rm -f "$STOPFILE"
        command -v termux-wake-lock > /dev/null 2>&1 && termux-wake-lock
        nohup bash "$0" _supervise > /dev/null 2>&1 &
        echo $! > "$PIDFILE"
        echo "started (pid $!). Logs: $LOG"
        ;;
    stop)
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
        ;;
    restart) bash "$0" stop; sleep 1; bash "$0" start ;;
    status)
        if running; then
            echo "running (pid $(cat "$PIDFILE"))"
            if [ -f "$STATE/token" ]; then echo "Open: http://localhost:8787/#t=$(cat "$STATE/token")"; fi
            exit 0
        else
            echo "not running"
            exit 1
        fi
        ;;
    logs) tail -n 40 "$LOG" ;;
    *) echo "usage: $0 start|stop|restart|status|logs"; exit 2 ;;
esac
