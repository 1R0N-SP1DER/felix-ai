# Seeing and controlling a phone through adb (no root, no app)

Faze can use Android's own debugging bridge as another way to reach a phone. It needs nothing installed on the phone and no root.

On the phone: Settings > Developer options > Wireless debugging (or USB debugging).
On this computer: install adb (sudo apt install adb), then
  adb pair HOST:PAIRPORT     (the pairing code is shown on the phone)
  adb connect HOST:PORT
Check with `adb devices`: the phone must say "device", not "unauthorized".

What Faze does with it: live H.264 video of the screen (adb exec-out screenrecord, restarted every three minutes), taps, long presses and swipes (adb shell input), and every key including Tab and Esc. Typing is plain ASCII only.

When it is used: config key phone_backend, "auto" (default) uses adb when no Faze phone is connected or the Faze phone could not show its screen; "adb" always; "link" never.
