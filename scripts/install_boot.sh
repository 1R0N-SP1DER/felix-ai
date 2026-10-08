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
"$HOME/felix-ai/scripts/faze_service.sh" start
SCRIPT
chmod +x "$BOOT/faze"

chmod +x "$HOME/felix-ai/scripts/faze_service.sh"
mkdir -p "$HOME/.local/bin" 2> /dev/null
ln -sf "$HOME/felix-ai/scripts/faze_service.sh" "${PREFIX:-/usr}/bin/faze" 2> /dev/null && echo "[boot] command: faze start | stop | status | logs"
echo "[boot] installed $BOOT/faze"
echo "Next:"
echo "  1. Open the Termux:Boot app once."
echo "  2. Android Settings > Apps > Termux and Termux:Boot > Battery > Unrestricted."
echo "  3. Reboot. Faze's app will work without opening Termux."
echo "Logs: ~/felix-ai/faze.log"
