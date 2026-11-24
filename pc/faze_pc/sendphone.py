"""Sends a file, some text or a link from this PC to the phone (phone_put). Files go in 256 KB pieces and land in the phone's Downloads/Faze."""
import base64
import os

CHUNK = 256 * 1024
MAX_SIZE = 1024 * 1024 * 1024  # 1 GB: more than that is a job for the phone's file browser


class PhoneSender:
    def __init__(self, request, pid, say):
        """request(pid, msg, timeout) -> reply; say(text, progress 0..1 or None, done, ok)."""
        self.request = request
        self.pid = pid
        self.say = say

    def send_text(self, text):
        text = (text or "").strip()
        if not text:
            return self.say("Nothing to send.", None, True, False)
        kind = "link" if text.startswith(("http://", "https://")) and " " not in text else "text"
        try:
            r = self.request(self.pid, {"t": "phone_put", "kind": kind, "text": text}, 15)
        except Exception as e:
            return self.say("The phone did not answer: %s" % e, None, True, False)
        if r.get("t") != "phone_put_ok":
            return self.say(r.get("message", "The phone refused it."), None, True, False)
        self.say("Sent the %s to the phone." % kind, 1.0, True, True)

    def send_file(self, path):
        path = os.path.expanduser(str(path or ""))
        if path.startswith("file://"):
            from urllib.parse import unquote
            path = unquote(path[7:])
        try:
            size = os.path.getsize(path)
            if not os.path.isfile(path):
                raise OSError("not a file")
        except OSError:
            return self.say("That is not a file I can read: %s" % path, None, True, False)
        if size > MAX_SIZE:
            return self.say("That file is over 1 GB. Use the phone's file browser for it.", None, True, False)
        name = os.path.basename(path)
        sent = 0
        try:
            with open(path, "rb") as f:
                while True:
                    chunk = f.read(CHUNK)
                    eof = sent + len(chunk) >= size
                    r = self.request(self.pid, {"t": "phone_put", "kind": "file", "name": name, "size": size, "offset": sent, "eof": eof, "data": base64.b64encode(chunk).decode()}, 30)
                    if r.get("t") != "phone_put_ok":
                        return self.say(r.get("message", "The phone refused %s." % name), None, True, False)
                    sent += len(chunk)
                    if eof:
                        break
                    self.say("Sending %s" % name, sent / float(size), False, True)
        except OSError as e:
            return self.say("Could not read %s: %s" % (name, e), None, True, False)
        except Exception as e:
            return self.say("The phone stopped answering: %s" % e, None, True, False)
        self.say("Sent %s to the phone (Downloads/Faze)." % name, 1.0, True, True)
