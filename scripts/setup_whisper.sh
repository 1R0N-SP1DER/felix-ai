#!/data/data/com.termux/files/usr/bin/bash
#
# Offline voice input: builds whisper.cpp and fetches a small English model.
# Usage:  scripts/setup_whisper.sh [tiny.en | base.en]
#   tiny.en  75 MB, fastest (default)       base.en  142 MB, more accurate, slower
# Takes a few minutes to compile. Needs about 400 MB of free storage.

set -e
MODEL="${1:-tiny.en}"
case "$MODEL" in
    tiny.en|base.en) ;;
    *) echo "usage: $0 [tiny.en|base.en]"; exit 1 ;;
esac

pkg install -y git cmake clang make curl > /dev/null

cd "$HOME"
if [ -d whisper.cpp/.git ]; then
    echo "[whisper] source already present"
else
    echo "[whisper] cloning..."
    git clone --depth 1 https://github.com/ggml-org/whisper.cpp
fi

cd whisper.cpp
echo "[whisper] building (a few minutes)..."
cmake -B build > /dev/null
cmake --build build -j4 --config Release

BIN="$HOME/whisper.cpp/build/bin/whisper-cli"
[ -x "$BIN" ] || { echo "[whisper] ERROR: $BIN was not built. Look for build errors above."; exit 1; }

echo "[whisper] downloading model $MODEL..."
sh ./models/download-ggml-model.sh "$MODEL"
mkdir -p "$HOME/models"
cp "models/ggml-$MODEL.bin" "$HOME/models/ggml-$MODEL.bin"

if [ "$MODEL" != "tiny.en" ]; then
    ( cd "$HOME/felix-ai/core" && python3 -c "from config_loader import set_user_setting; set_user_setting('stt.model_path', '~/models/ggml-$MODEL.bin')" )
    echo "[whisper] stt.model_path set to ~/models/ggml-$MODEL.bin"
fi

echo "[whisper] testing on the bundled sample..."
"$BIN" -m "$HOME/models/ggml-$MODEL.bin" -f samples/jfk.wav -nt -np -t 4
echo "[whisper] done. Expect a line about 'ask not what your country can do for you' above."
