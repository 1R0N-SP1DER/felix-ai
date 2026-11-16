#!/usr/bin/env bash
# Installs Faze on this PC for the current user: a private Python environment, the `faze` command, a menu entry, and autostart of the background daemon at login.
# Run from the pc folder:  bash install.sh        Remove with:  bash install.sh --remove
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
home="${XDG_DATA_HOME:-$HOME/.local/share}/faze"
bin="$HOME/.local/bin"
apps="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
auto="${XDG_CONFIG_HOME:-$HOME/.config}/autostart"

if [ "${1:-}" = "--remove" ]; then
  pkill -f faze_pc.daemon 2>/dev/null || true
  rm -rf "$home" "$bin/faze" "$apps/faze.desktop" "$auto/faze-daemon.desktop"
  echo "Faze removed. Your chats and settings in ~/.config/faze were left alone."
  exit 0
fi

command -v python3 >/dev/null || { echo "python3 is needed"; exit 1; }
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)' || { echo "Python 3.11 or newer is needed"; exit 1; }
python3 -c 'import venv, ensurepip' 2>/dev/null || { echo "Install the venv package first: sudo apt install python3-venv"; exit 1; }

echo "Making a private Python environment in $home"
mkdir -p "$home" "$bin" "$apps" "$auto"
python3 -m venv "$home/venv"
"$home/venv/bin/pip" install --quiet --upgrade pip
"$home/venv/bin/pip" install --quiet "$here[ui]"

cat > "$bin/faze" <<LAUNCH
#!/usr/bin/env bash
exec "$home/venv/bin/faze" "\$@"
LAUNCH
chmod +x "$bin/faze"

cat > "$apps/faze.desktop" <<DESK
[Desktop Entry]
Type=Application
Name=Faze
Comment=Faze on your desktop
Exec=$bin/faze
Icon=utilities-terminal
Categories=Utility;
DESK

cat > "$auto/faze-daemon.desktop" <<AUTO
[Desktop Entry]
Type=Application
Name=Faze background
Comment=Keeps Faze connected to your phone
Exec=$home/venv/bin/python -m faze_pc.daemon
X-KDE-autostart-after=panel
AUTO

echo
echo "Installed. Start it now with:  $bin/faze"
case ":$PATH:" in *":$bin:"*) ;; *) echo "(Add $bin to your PATH to type just: faze)";; esac
echo "The background part starts by itself at your next login."
echo
echo "Optional tools for voice, remote control and the webcam:"
echo "  sudo apt install pulseaudio-utils alsa-utils ydotool imagemagick ffmpeg wl-clipboard"
