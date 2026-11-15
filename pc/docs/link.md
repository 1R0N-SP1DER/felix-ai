Faze Link v1 (phone and PC)

The phone is the hub and listens on TCP 8765. The PC connects. Any IP network works: Wi-Fi, Bluetooth tethering, USB tethering.
The phone also answers UDP broadcast "FAZE?" on port 8766 with {"t":"faze","id","name","port"}.

Frames: 4 bytes big-endian length, then the payload (max 1 MiB). Handshake messages are JSON. After the handshake every frame is AES-256-GCM.

Pairing (the phone is showing a 10-character code from the alphabet ABCDEFGHJKLMNPQRSTUVWXYZ23456789, open for 3 minutes, 5 tries):
  K = PBKDF2-HMAC-SHA256(code, salt "faze-pair-v1", 100000 rounds, 32 bytes)
  PC    -> {"t":"pair","v":1,"id":pcId,"name":..,"nonce":nc(16 bytes hex)}
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
