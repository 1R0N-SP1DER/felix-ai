"""Folders this PC shares with a paired phone, read-only. The phone sees them in its Files picker as "Your PC".
Only the folders listed in the settings are reachable, and nothing outside them: every path is resolved (symlinks included) and must stay inside its folder."""
import base64
import os

CHUNK = 512 * 1024
MAX_ENTRIES = 2000


class FsError(Exception):
    pass


class Shares:
    def __init__(self, config):
        self.config = config

    def roots(self):
        """[(id, shown name, real path)] for the listed folders that exist."""
        out, seen = [], set()
        if self.config.data.get("share_all") is True:
            out.append(("All files", "All files on this PC", os.sep))
            seen.add("All files")
        for raw in str(self.config.data.get("share_folders", "")).split(","):
            raw = raw.strip()
            if not raw:
                continue
            path = os.path.realpath(os.path.expanduser(raw))
            if not os.path.isdir(path):
                continue
            name = os.path.basename(path.rstrip("/")) or path
            rid, n = name, 2
            while rid in seen:
                rid, n = "%s-%d" % (name, n), n + 1
            seen.add(rid)
            out.append((rid, name, path))
        return out

    def resolve(self, root_id, rel):
        for rid, _, base in self.roots():
            if rid == root_id:
                parts = [p for p in str(rel or "").split("/") if p not in ("", ".")]
                if ".." in parts or any("\x00" in p for p in parts):
                    raise FsError("that path is not allowed")
                full = os.path.realpath(os.path.join(base, *parts))
                if full != base and not full.startswith(base.rstrip(os.sep) + os.sep):
                    raise FsError("that path leaves the shared folder")
                return full
        raise FsError("that folder is not shared")

    def list(self, root_id, rel):
        path = self.resolve(root_id, rel)
        if not os.path.isdir(path):
            raise FsError("not a folder")
        entries = []
        try:
            names = sorted(os.listdir(path), key=lambda n: (not os.path.isdir(os.path.join(path, n)), n.lower()))
        except OSError as e:
            raise FsError(str(e))
        for name in names[:MAX_ENTRIES]:
            full = os.path.join(path, name)
            try:
                real = os.path.realpath(full)
                base = self.resolve(root_id, "")
                if real != base and not real.startswith(base.rstrip(os.sep) + os.sep):
                    continue  # a link pointing out of the shared folder is not shown
                st = os.stat(real)
            except (OSError, FsError):
                continue
            entries.append({"name": name, "dir": os.path.isdir(real), "size": 0 if os.path.isdir(real) else st.st_size, "mtime": int(st.st_mtime * 1000)})
        return entries

    def read(self, root_id, rel, offset, length):
        path = self.resolve(root_id, rel)
        if not os.path.isfile(path):
            raise FsError("not a file")
        offset = max(0, int(offset))
        length = max(1, min(int(length), CHUNK))
        try:
            size = os.path.getsize(path)
            with open(path, "rb") as f:
                f.seek(offset)
                data = f.read(length)
        except OSError as e:
            raise FsError(str(e))
        return {"data": base64.b64encode(data).decode("ascii"), "size": size, "eof": offset + len(data) >= size}
