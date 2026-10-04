#!/data/data/com.termux/files/usr/bin/bash
#
# Faze launcher. Starts the Needle router (fast), then Faze itself.
# Faze boots and warms llama-server (Qwen) in the BACKGROUND, so you can
# chat immediately; see "boot" in /settings. Extra arguments go to
# orchestrator.py, e.g.  ./faze.sh --serve   (web app, see core/web_server.py)

set -e

CORE_DIR="$HOME/felix-ai/core"
NEEDLE_DIR="$CORE_DIR/android-arm64"
NEEDLE_PORT=8765
NEEDLE_LOG="$HOME/felix-ai/needle-server.log"

[ -f "$HOME/.felix-env" ] && . "$HOME/.felix-env"

needle_up() {
    curl -s "http://127.0.0.1:${NEEDLE_PORT}/complete" -X POST \
        -H "Content-Type: application/json" -d '{"input":"ping"}' > /dev/null 2>&1
}

if needle_up; then
    echo "[needle] already running on port ${NEEDLE_PORT}"
else
    echo "[needle] starting (log: $NEEDLE_LOG)..."
    ( cd "$NEEDLE_DIR" && nohup ./needle --tools tools.json --serve --port "$NEEDLE_PORT" \
        > "$NEEDLE_LOG" 2>&1 & )
    ATTEMPTS=0
    until needle_up; do
        ATTEMPTS=$((ATTEMPTS + 1))
        if [ "$ATTEMPTS" -ge 20 ]; then
            echo "[needle] ERROR: not ready after 10s. See $NEEDLE_LOG"
            exit 1
        fi
        sleep 0.5
    done
    echo "[needle] ready"
fi

cd "$CORE_DIR"
exec python orchestrator.py "$@"
