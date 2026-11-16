"""The phone-mirror window as its own program. It is not part of the Faze window: the daemon starts it (`faze --mirror`) when the phone asks,
and it only talks to the daemon over the same socket. Closing it stops the mirroring. PC mouse and keyboard are relayed to the phone."""
import base64
import os
import sys
import threading

from PySide6.QtCore import QObject, Property, QUrl, Signal, Slot
from PySide6.QtGui import QGuiApplication, QImage
from PySide6.QtQml import QQmlApplicationEngine

from .. import client as dclient
from .app import FrameProvider

HERE = os.path.dirname(__file__)


class MirrorBridge(QObject):
    frameChanged = Signal()
    problemChanged = Signal()

    def __init__(self):
        super().__init__()
        self.frames = FrameProvider()
        self._frameNo = 0
        self._problem = ""
        self._client = None
        self._lock = threading.Lock()

    @Property(int, notify=frameChanged)
    def frameNo(self):
        return self._frameNo

    @Property(str, notify=problemChanged)
    def problem(self):
        return self._problem

    def start(self):
        self._client = dclient.Client()
        threading.Thread(target=self._read, daemon=True).start()
        self._send({"op": "mirror_start"})

    def _send(self, obj):
        with self._lock:
            if self._client:
                try:
                    self._client.send(obj)
                except OSError:
                    pass

    def _read(self):
        for ev in self._client.events():
            t = ev.get("t")
            if t == "frame":
                img = QImage.fromData(base64.b64decode(ev["jpeg"]), "JPG")
                if not img.isNull():
                    self.frames.set(img)
                    self._frameNo += 1
                    if self._problem:
                        self._problem = ""
                        self.problemChanged.emit()
                    self.frameChanged.emit()
            elif t == "mirror_error":
                self._problem = ev["message"]
                self.problemChanged.emit()

    @Slot()
    def stop(self):
        self._send({"op": "mirror_stop"})

    @Slot(str, float, float, float, float)
    def input(self, kind, x, y, x2, y2):
        self._send({"op": "mirror_input", "kind": kind, "x": x, "y": y, "x1": x, "y1": y, "x2": x2, "y2": y2})

    @Slot(str)
    def key(self, key):
        self._send({"op": "mirror_input", "kind": "key", "key": key})

    @Slot(str)
    def text(self, text):
        self._send({"op": "mirror_input", "kind": "text", "text": text})


def run_mirror():
    os.environ.setdefault("QT_QUICK_CONTROLS_STYLE", "Basic")
    app = QGuiApplication(sys.argv)
    app.setApplicationName("Faze phone")
    bridge = MirrorBridge()
    engine = QQmlApplicationEngine()
    engine.rootContext().setContextProperty("mirror", bridge)
    engine.addImageProvider("phone", bridge.frames)
    engine.load(QUrl.fromLocalFile(os.path.join(HERE, "Mirror.qml")))
    if not engine.rootObjects():
        sys.stderr.write("the mirror window could not be loaded\n")
        return 1
    try:
        bridge.start()
    except OSError:
        sys.stderr.write("the Faze daemon is not running\n")
        return 1
    code = app.exec()
    bridge.stop()
    return code
