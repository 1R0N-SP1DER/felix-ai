"""Copies the offline models from the phone onto this PC and sets them up in Ollama, so the same Qwen and LFM can be used here.
Steps for each .gguf the phone has: ask the phone to stage a copy (models_stage), read it in pieces through the file messages, remove the staged copy,
then `ollama create` from the file. Needs Ollama installed. Models Ollama already has under the same name are skipped."""
import base64
import hashlib
import http.client
import json
import os
import tempfile
import urllib.parse
import re
import shutil
import subprocess
import threading

from . import config as cfg

CHUNK = 512 * 1024
MIN_BYTES = 100 * 1024 * 1024  # smaller .gguf files are test vocabularies (llama.cpp ships dozens of ggml-vocab-*.gguf), not models you can chat with
CHATML = '''TEMPLATE """{{- range .Messages }}<|im_start|>{{ .Role }}
{{ .Content }}<|im_end|>
{{ end }}<|im_start|>assistant
"""
PARAMETER stop <|im_end|>
'''


def ollama_name(filename):
    """Qwen3.5-4B-Q4_K_M.gguf -> qwen3.5:4b-q4_k_m ; LFM2.5.gguf -> lfm2.5:latest."""
    stem = re.sub(r"\.gguf$", "", filename, flags=re.I).lower()
    stem = re.sub(r"[^a-z0-9._-]+", "-", stem).strip("-._") or "model"
    if "-" in stem:
        family, tag = stem.split("-", 1)
    else:
        family, tag = stem, "latest"
    return "%s:%s" % (family, tag)


def modelfile(path):
    return "FROM %s\n%s" % (path, CHATML)


def real_model(m):
    """Chat models only: not llama.cpp's vocabulary test files, and not anything too small to be a model."""
    name = str(m.get("name", "")).lower()
    return "vocab" not in name and int(m.get("size", 0) or 0) >= MIN_BYTES


def shared_dir():
    """A folder Ollama's own service user can read. Ollama opens the file itself, and a home folder (private on Ubuntu) would refuse it."""
    return os.path.join("/var/tmp", "faze-models-%d" % os.getuid())


TEMPLATE = "{{- range .Messages }}<|im_start|>{{ .Role }}\n{{ .Content }}<|im_end|>\n{{ end }}<|im_start|>assistant\n"


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(8 * 1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def api_create(config, name, path):
    """Sets the model up through Ollama's HTTP interface: Faze reads the file itself and sends it, so no file permission of Ollama's own matters.
    Returns (True, "") or (False, reason); (None, reason) when Ollama's API is not reachable at all."""
    u = urllib.parse.urlparse(config.data.get("ollama_url", "http://127.0.0.1:11434"))
    try:
        digest = sha256_of(path)
        conn = http.client.HTTPConnection(u.hostname, u.port or 11434, timeout=900)
        conn.request("HEAD", "/api/blobs/sha256:" + digest)
        have = conn.getresponse().status == 200
        conn.close()
        if not have:
            conn = http.client.HTTPConnection(u.hostname, u.port or 11434, timeout=3600)
            with open(path, "rb") as f:
                conn.request("POST", "/api/blobs/sha256:" + digest, body=f, headers={"Content-Length": str(os.path.getsize(path))})
                r = conn.getresponse()
                body = r.read().decode("utf-8", "replace")
            conn.close()
            if r.status not in (200, 201):
                return False, "the upload was refused (%d) %s" % (r.status, body[:200])
        last = ""
        for payload in (
            {"model": name, "files": {os.path.basename(path): "sha256:" + digest}, "template": TEMPLATE, "parameters": {"stop": ["<|im_end|>"]}, "stream": False},
            {"model": name, "name": name, "modelfile": "FROM @sha256:%s\n%s" % (digest, CHATML), "stream": False},
        ):
            conn = http.client.HTTPConnection(u.hostname, u.port or 11434, timeout=900)
            conn.request("POST", "/api/create", body=json.dumps(payload), headers={"Content-Type": "application/json"})
            r = conn.getresponse()
            body = r.read().decode("utf-8", "replace")
            conn.close()
            if r.status == 200 and '"error"' not in body:
                return True, ""
            last = body.strip().splitlines()[-1][:300] if body.strip() else "status %d" % r.status
        return False, last
    except (ConnectionError, OSError) as e:
        return None, str(e)


def create_in_ollama(config, name, path, runner):
    """(rc, message). First through the HTTP interface; if that cannot be used, the command line, with its small setup file kept in a private temporary folder."""
    ok, why = api_create(config, name, path)
    if ok:
        return 0, ""
    tmp = tempfile.mkdtemp(prefix="faze-")
    try:
        mf = os.path.join(tmp, "Modelfile")
        with open(mf, "w") as f:
            f.write(modelfile(path))
        rc, out = runner(["ollama", "create", name, "-f", mf])
        if rc != 0 and ok is False and why:
            out = why + " | " + out
        return rc, out
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def run(argv, timeout=900):
    try:
        r = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout + r.stderr).strip()
    except (OSError, subprocess.SubprocessError) as e:
        return 1, str(e)


class ModelCopy:
    def __init__(self, config, request, phone, say, installed, dest=None, which=shutil.which, runner=run, running=None):
        self.config, self.request, self.phone, self.say = config, request, phone, say
        self.installed = installed  # () -> set of Ollama model names already here
        self.dest = dest or shared_dir()
        self.running = running  # () -> whether Ollama answers; None skips the check
        self.which, self.run = which, runner
        self.cancelled = False
        self.created = []

    def go(self):
        if not self.which("ollama"):
            return self.fail("Ollama is not installed on this PC. Install it first:  curl -fsSL https://ollama.com/install.sh | sh")
        if self.running is not None and not self.running():
            return self.fail("Ollama is installed but not running. Start it with:  systemctl start ollama   (or run  ollama serve  in a terminal), then try again.")
        try:
            listing = self.request(self.phone, {"t": "models_list"}, 60)
        except Exception as e:
            return self.fail("Could not ask the phone: %s" % e)
        if listing.get("t") == "error":
            return self.fail(listing.get("message", "the phone said no"))
        models = [m for m in listing.get("models", []) if real_model(m)]
        if not models:
            return self.fail("The phone has no offline models it can share (it looks in Termux, Download and models).")
        os.makedirs(self.dest, exist_ok=True)
        os.chmod(self.dest, 0o755)  # readable by the Ollama service; the files are removed again once Ollama has its own copy
        have = self.installed()
        total = len(models)
        for i, m in enumerate(models):
            name = ollama_name(m["name"])
            if name in have:
                self.say("%s is already here, skipped." % name, i / total, False, True)
                continue
            if not self.one(m, name, i, total):
                return
        if not self.created:
            return self.finish("Everything from the phone is already on this PC.")
        self.use(self.created)
        self.finish("Done. New in Ollama: %s." % ", ".join(self.created))

    def one(self, m, name, i, total):
        part = lambda f: (i + f) / total
        self.say("Asking the phone to get %s ready (a few GB can take a minute)..." % m["name"], part(0), False, True)
        try:
            st = self.request(self.phone, {"t": "models_stage", "path": m["path"]}, 15 * 60 + 60)
        except Exception as e:
            return self.fail("The phone did not answer for %s: %s" % (m["name"], e))
        if st.get("t") != "models_stage_ok":
            return self.fail("%s: %s" % (m["name"], st.get("message", "the phone could not copy it")))
        target = os.path.join(self.dest, m["name"])
        ok = False
        try:
            ok = self.download(st, target + ".part", m["name"], i, total)
        finally:
            try:
                self.request(self.phone, {"t": "models_unstage", "name": st["path"]}, 30)
            except Exception:
                pass
        if not ok:
            return False
        os.replace(target + ".part", target)
        self.say("Setting up %s in Ollama..." % name, part(0.95), False, True)
        rc, out = create_in_ollama(self.config, name, target, self.run)
        if rc != 0:
            return self.fail("Ollama could not set up %s: %s" % (name, out.splitlines()[-1] if out else "no message"))
        os.unlink(target)  # Ollama keeps its own copy
        self.created.append(name)
        return True

    def download(self, st, path, label, i, total):
        size, off = int(st.get("size", 0)), 0
        with open(path, "wb") as f:
            while off < size or size == 0:
                if self.cancelled:
                    self.fail("Cancelled.")
                    return False
                r = None
                for _ in range(3):
                    try:
                        r = self.request(self.phone, {"t": "fs_read", "root": st["root"], "path": st["path"], "offset": off, "len": CHUNK}, 60)
                        if r.get("t") == "fs_read_ok":
                            break
                    except Exception:
                        r = None
                if not r or r.get("t") != "fs_read_ok":
                    self.fail("The copy of %s stopped at %d MB: %s" % (label, off // 1048576, (r or {}).get("message", "no answer from the phone")))
                    return False
                data = base64.b64decode(r.get("data", ""))
                f.write(data)
                off += len(data)
                self.say("Copying %s: %d of %d MB" % (label, off // 1048576, size // 1048576), (i + (off / size if size else 1)) / total * 0.95, False, True)
                if r.get("eof") or not data:
                    break
        if size and off != size:
            self.fail("%s came across incomplete (%d of %d bytes)." % (label, off, size))
            return False
        return True

    def use(self, names):
        """Small models answer casual chat, bigger ones code and hard requests, as on the phone."""
        for n in names:
            if n.startswith("lfm"):
                self.config.set("ollama_chat_model", n)
            elif n.startswith("qwen"):
                self.config.set("ollama_deep_model", n)

    def finish(self, text):
        self.say(text, 1.0, True, True)

    def fail(self, text):
        self.say(text, 0.0, True, False)
        return False


def drop_dir():
    """Where you drag model files: ~/Downloads/faze."""
    return os.path.join(os.path.expanduser("~"), "Downloads", "faze")


class DropFolder:
    """Watches the drop folder. A finished .gguf in it is moved to the folder Ollama can read, set up in Ollama, and then gone from the drop folder."""
    def __init__(self, config, say, installed, running=None, folder=None, dest=None, which=shutil.which, runner=run, clock=None):
        import time
        self.config, self.say, self.installed, self.running = config, say, installed, running
        self.folder, self.dest = folder or drop_dir(), dest or shared_dir()
        self.which, self.run = which, runner
        self.clock = clock or time.time
        self.sizes = {}
        self.busy = False

    def scan(self):
        """One look at the folder. Returns the Ollama names created this time."""
        if self.busy:
            return []
        try:
            files = [f for f in os.listdir(self.folder) if f.lower().endswith(".gguf")]
        except OSError:
            return []
        ready = []
        for f in files:
            path = os.path.join(self.folder, f)
            try:
                st = os.stat(path)
            except OSError:
                continue
            settled = self.sizes.get(f) == st.st_size and self.clock() - st.st_mtime > 3  # same size twice in a row: still being copied otherwise
            self.sizes[f] = st.st_size
            if settled and real_model({"name": f, "size": st.st_size}):
                ready.append(f)
        for gone in set(self.sizes) - set(files):
            self.sizes.pop(gone)
        if not ready:
            return []
        self.busy = True
        try:
            return self.import_all(ready)
        finally:
            self.busy = False

    def import_all(self, files):
        if not self.which("ollama"):
            self.say("Ollama is not installed on this PC, so the models in the drop folder wait. Install it:  curl -fsSL https://ollama.com/install.sh | sh", 0.0, True, False)
            return []
        if self.running is not None and not self.running():
            self.say("Ollama is not running, so the models in the drop folder wait. Start it with:  systemctl start ollama", 0.0, True, False)
            return []
        os.makedirs(self.dest, exist_ok=True)
        os.chmod(self.dest, 0o755)
        have, made = self.installed(), []
        for i, f in enumerate(files):
            name = ollama_name(f)
            src = os.path.join(self.folder, f)
            if name in have:
                self.say("%s is already in Ollama; removed the copy from the drop folder." % name, i / len(files), False, True)
                self.remove(src)
                continue
            self.say("Setting up %s in Ollama (this can take a minute)..." % name, i / len(files), False, True)
            target = src  # Faze reads the file itself and sends it to Ollama, so it can stay where it is until it is done
            rc, out = create_in_ollama(self.config, name, target, self.run)
            if rc != 0:
                self.say("Ollama could not set up %s: %s" % (name, out.splitlines()[-1] if out else "no message"), 0.0, True, False)
                return made
            self.remove(target)
            made.append(name)
        if made:
            ModelCopy.use(self, made)
            self.say("Done. New in Ollama: %s. The files are gone from the drop folder." % ", ".join(made), 1.0, True, True)
        else:
            self.say("Nothing new in the drop folder.", 1.0, True, True)
        return made

    @staticmethod
    def remove(path):
        try:
            os.unlink(path)
        except OSError:
            pass
