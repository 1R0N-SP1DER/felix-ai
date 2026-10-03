#!/data/data/com.termux/files/usr/bin/bash
#
# felix-ai start script
# Launches llama-server and the Needle tool-router server in the
# background, waits until both are actually ready, then starts the
# orchestrator chat loop.

set -e

LLAMA_DIR="$HOME/llama.cpp"
MODEL_PATH="$HOME/models/Qwen3.5-0.8B-Q4_K_M.gguf"
CORE_DIR="$HOME/felix-ai/core"
LLAMA_PORT=8080
LLAMA_LOG="$HOME/felix-ai/llama-server.log"

NEEDLE_DIR="$HOME/felix-ai/core/android-arm64"
NEEDLE_PORT=8765
NEEDLE_LOG="$HOME/felix-ai/needle-server.log"

echo "=== felix-ai startup ==="

if curl -sf "http://127.0.0.1:${LLAMA_PORT}/health" > /dev/null 2>&1; then
    echo "[1/4] llama-server already running on port ${LLAMA_PORT}, skipping launch."
else
    echo "[1/4] Starting llama-server in background (log: $LLAMA_LOG)..."
    cd "$LLAMA_DIR"
    nohup ./build/bin/llama-server \
        -m "$MODEL_PATH" \
        --port "$LLAMA_PORT" \
        -c 4096 -t 4 \
        --host 127.0.0.1 \
        > "$LLAMA_LOG" 2>&1 &

    echo "    Waiting for llama-server to be ready..."
    ATTEMPTS=0
    MAX_ATTEMPTS=90
    until curl -sf "http://127.0.0.1:${LLAMA_PORT}/health" > /dev/null 2>&1; do
        ATTEMPTS=$((ATTEMPTS + 1))
        if [ "$ATTEMPTS" -ge "$MAX_ATTEMPTS" ]; then
            echo "    ERROR: llama-server didn't become ready after ${MAX_ATTEMPTS}s."
            echo "    Check the log: $LLAMA_LOG"
            exit 1
        fi
        sleep 1
    done
    echo "    llama-server is ready (took ~${ATTEMPTS}s)."
fi

echo "[warmup] Pre-heating llama-server cache (about a minute on a cold start)..."
PYTHONPATH="$HOME/felix-ai/core" python3 "$HOME/felix-ai/core/warmup.py" || echo "    warmup skipped"

if curl -s "http://127.0.0.1:${NEEDLE_PORT}/complete" -X POST -H "Content-Type: application/json" -d '{"input":"ping"}' > /dev/null 2>&1; then
    echo "[2/4] Needle server already running on port ${NEEDLE_PORT}, skipping launch."
else
    echo "[2/4] Starting Needle server in background (log: $NEEDLE_LOG)..."
    cd "$NEEDLE_DIR"
    nohup ./needle --tools tools.json --serve --port "$NEEDLE_PORT" \
        > "$NEEDLE_LOG" 2>&1 &

    echo "    Waiting for Needle server to be ready..."
    ATTEMPTS=0
    MAX_ATTEMPTS=20
    until curl -s "http://127.0.0.1:${NEEDLE_PORT}/complete" -X POST -H "Content-Type: application/json" -d '{"input":"ping"}' > /dev/null 2>&1; do
        ATTEMPTS=$((ATTEMPTS + 1))
        if [ "$ATTEMPTS" -ge "$MAX_ATTEMPTS" ]; then
            echo "    ERROR: Needle server didn't become ready after ${MAX_ATTEMPTS}s."
            echo "    Check the log: $NEEDLE_LOG"
            exit 1
        fi
        sleep 0.5
    done
    echo "    Needle server is ready (took ~${ATTEMPTS} half-seconds)."
fi

echo "[3/4] Launching Faze..."
echo "[4/4] Ready."
echo ""

cd "$CORE_DIR"
python orchestrator.py
