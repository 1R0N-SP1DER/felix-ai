Faze Link v1 (phone and PC)

The phone is the hub and listens on TCP 8765. The PC connects. Any IP network works: Wi-Fi, Bluetooth tethering, USB tethering.
The phone also answers UDP broadcast "FAZE?" on port 8766 with {"t":"faze","id","name","port"}.

Frames: 4 bytes big-endian length, then the payload (max 1 MiB). Handshake messages are JSON. After the handshake every frame is AES-256-GCM.

Pairing (the phone is showing a 10-character code from the alphabet ABCDEFGHJKLMNPQRSTUVWXYZ23456789, open for 3 minutes, 5 tries):
  K = PBKDF2-HMAC-SHA256(code, salt "faze-pair-v1", 100000 rounds, 32 bytes)
  PC    -> {"t":"pair","v":1,"id":pcId,"name":..,"os":"linux","nonce":nc(16 bytes hex)}
  phone -> {"t":"pair_ok","nonce":np,"proof":HMAC(K, "phone"|nc|np)}
  PC    -> {"t":"pair_confirm","proof":HMAC(K, "pc"|np|nc)}
  phone -> {"t":"secret","id":phoneId,"name":..,"ct":AES-GCM(K, nonce "SECRET"+6 zero bytes, S)}   S = 32 random bytes, kept by both sides
Connecting (paired):
  PC    -> {"t":"hello","v":1,"id":pcId,"nonce":nc}
  phone -> {"t":"hello_ok","nonce":np,"proof":HMAC(S, "phone"|nc|np)}
  PC    -> {"t":"auth","proof":HMAC(S, "pc"|np|nc)}
  both derive session key = HKDF-SHA256(S, salt nc|np, info "faze-session-v1", 32 bytes)
  phone -> (encrypted) {"t":"ready","id","name"}
Encrypted frames: nonce = 4-byte direction tag ("PCPC" for PC to phone, "PHON" for phone to PC) + 8-byte big-endian counter starting at 0.
A frame whose counter is not the next one fails to decrypt and the connection is dropped.
Messages after ready: {"t":"ping"} answered by {"t":"pong",...}. More come in later patches.

Sync (PC asks, phone answers; the phone is the source of truth)
  {"t":"sync_list","since":ms}   -> {"t":"sync_list_ok","now":ms,"facts":[{"text","category"}],"chats":[{"id","title","updated"}]}  (chats changed since, by the phone's clock)
  {"t":"sync_get","id"}          -> {"t":"sync_chat","chat":{"id","title","updated","messages":[{"role","text"}]}}
  {"t":"sync_put_chat","chat":{"id","messages":[..]}} -> {"t":"ok","added":n}
  {"t":"sync_put_facts","facts":["text",..]}          -> {"t":"ok","added":n}
Chats merge message by message (same role and text is the same message); notes by their text. Nothing is deleted by a sync. Only text travels.
The PC syncs when it connects and every 30 seconds while connected.

Two-way requests
  Either side may ask. A message with "rid" (a number the asker chose) is answered with the same number in "re". Messages with neither are one-way.
  Phone asks PC:  {"t":"pc_chat","messages":[openai-style],"deep":bool,"rid":n} -> {"t":"pc_chat_ok","content","model","re":n} or {"t":"error","message","re":n}
  The PC answers with its Ollama model (chat model, or the deep one when "deep"). Used by the phone's Auto mode.

Handing over tasks
  Phone asks PC:  {"t":"pc_task","task":text,"rid":n} -> {"t":"pc_task_ok","text":final answer,"re":n} or {"t":"error","message","re":n}
    The PC runs it as a new chat ("(From your phone) ..."). Approval cards show in the PC windows. One chat at a time: a busy PC answers with an error.
  PC asks phone:  {"t":"phone_task","task":text,"rid":n} -> {"t":"phone_task_ok","note","re":n} or {"t":"error","message","re":n}
    The phone starts a new chat with the task and answers at once; the result is in that chat, which syncs back. The phone's own approvals apply.

PC models
  Phone asks PC: {"t":"pc_models","rid":n} -> {"t":"pc_models_ok","models":["name",..],"re":n} (Ollama's installed models) or {"t":"error","message","re":n}
  pc_chat may carry "model": that Ollama model is used instead of the chat/deep default. The phone's model sheet shows these under "Your PC" while a PC is connected.

Shared folders (read only; phone asks, PC answers)
  {"t":"fs_roots"}                            -> {"t":"fs_roots_ok","roots":[{"id","name"}]}      the folders listed in the PC settings that exist
  {"t":"fs_list","root","path"}               -> {"t":"fs_list_ok","entries":[{"name","dir","size","mtime"}]}   folders first, up to 2000, links that leave the folder are hidden
  {"t":"fs_read","root","path","offset","len"} -> {"t":"fs_read_ok","data":base64,"size","eof"}   up to 512 KiB a time
A path is resolved with symlinks and must stay inside its folder. The phone shows these in its Files picker under "Your PC".

Screen view and control (phone asks nothing; PC asks, only when "Allow screen view and control" is on, never while the phone is locked)
  {"t":"screen_get"} -> {"t":"screen_ok","jpeg":base64}   a root screenshot, a few a second
  {"t":"screen_tap","x","y"} / screen_long / {"t":"screen_swipe","x1","y1","x2","y2"}   points are fractions 0..1 of the screen
  {"t":"screen_key","key"} / {"t":"screen_text","text"}   -> {"t":"screen_done","note"} or {"t":"error","message"}

Task manager and devices (behind "Let my phone see and control this PC"):
  Phone asks PC: {"t":"pc_procs","rid":n} -> {"t":"pc_procs_ok","procs":[{"pid","name","cpu","mem"}],"re":n} (busiest first; cpu is since the last ask)
  Phone asks PC: {"t":"pc_kill","pid":n,"force":bool,"rid":n} -> {"t":"ok"} or {"t":"error","message"} (SIGTERM, or SIGKILL when force; never pid 1 or Faze itself)
  Phone asks PC: {"t":"pc_devices","rid":n} -> {"t":"pc_devices_ok","devices":[{"kind":"usb|input|battery","name"}],"re":n}

### Phone doodles on the PC (kind=draw)

`pc_input` with `kind: "draw"` and `op` one of `start` (with `color` as RRGGBB and `x`, `y` as 0..1 of the screen), `move` (x, y), `end` or `clear`. The PC relays these to a click-through, always-on-top overlay (`faze --draw`) that is started on the first stroke and closes with the daemon. Strokes stay until `clear`.

### Fast video (H.264)

`pc_stream_start` may carry `"codec":"h264"`. The reply then has `"codec":"h264"`, and frames arrive as `{"t":"pc_frame","codec":"h264","data":<base64 Annex-B access unit>,"key":bool,"n":n}` instead of `jpeg`. Every unit must be fed to the decoder in order; keyframes repeat SPS/PPS and come about once a second. If the PC cannot encode (no ffmpeg libx264 / no GStreamer encoder), it sends `pc_stream_error` and the phone goes back to JPEG. Without `codec`, nothing changes: one JPEG per frame, newest only.

### Phone sound on the PC

PC asks phone: `{"t":"phone_sound_get"}` -> `{"t":"phone_sound_ok","rate":44100,"channels":2,"data":<base64 s16le>}` (what the phone is playing, since the last call; may be empty), or `{"t":"error","message"}` when sharing is off or the phone has not been allowed to capture its sound. `{"t":"phone_sound_stop"}` -> `{"t":"ok"}`. The PC plays it with pacat on the default output. Needs the phone's "Let computers use my camera and microphone" switch and a one-time Allow phone sound.

### The phone asks the PC to use its devices (pc_use_phone)

Phone to PC: `{"t":"pc_use_phone","what":"mic"|"cam"|"sound"|"status","on":bool}` -> `{"t":"pc_use_phone_ok","mic":bool,"cam":bool,"sound":bool}` (where things stand about 1 s later). Needs "Let my phone see and control this PC" on the PC, and "Let computers use my camera and microphone" on the phone. It is the same as pressing Phone microphone / camera / sound in the PC's Devices window.
