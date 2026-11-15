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
