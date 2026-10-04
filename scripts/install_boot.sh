#!/data/data/com.termux/files/usr/bin/bash
#
# Start Faze automatically when the phone boots, with no Termux window open.
# Needs the Termux:Boot app (install it from the same source as Termux, e.g.
# F-Droid) and one launch of it after installing.
# Usage:  scripts/install_boot.sh          (undo: rm ~/.termux/boot/faze)

set -e
BOOT="$HOME/.termux/boot"
mkdir -p "$BOOT"

cat > "$BOOT/faze" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
cd "$HOME/felix-ai"
nohup ./faze.sh --serve >> "$HOME/felix-ai/faze.log" 2>&1 &
SCRIPT
chmod +x "$BOOT/faze"

echo "[boot] installed $BOOT/faze"
echo "Next:"
echo "  1. Open the Termux:Boot app once."
echo "  2. Android Settings > Apps > Termux and Termux:Boot > Battery > Unrestricted."
echo "  3. Reboot. Faze's app will work without opening Termux."
echo "Logs: ~/felix-ai/faze.log"
