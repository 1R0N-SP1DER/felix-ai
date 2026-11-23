"""The drawing overlay as its own program (`faze --draw`). Reads one JSON step per line on stdin; leaves when stdin closes."""
import json
import os
import sys
import threading

from PySide6.QtCore import QObject, QUrl, Signal
from PySide6.QtGui import QGuiApplication
from PySide6.QtQml import QQmlApplicationEngine

HERE = os.path.dirname(__file__)


class DrawBridge(QObject):
    step = Signal(str, str, float, float)
    gone = Signal()

    def read(self, stream):
        for line in stream:
            try:
                d = json.loads(line)
                self.step.emit(str(d.get("op", "")), str(d.get("color", "")), float(d.get("x", 0)), float(d.get("y", 0)))
            except (ValueError, TypeError, AttributeError):
                continue
        self.gone.emit()


def run_draw(stream=None):
    app = QGuiApplication(sys.argv)
    bridge = DrawBridge()
    engine = QQmlApplicationEngine()
    engine.rootContext().setContextProperty("draw", bridge)
    engine.load(QUrl.fromLocalFile(os.path.join(HERE, "Draw.qml")))
    if not engine.rootObjects():
        return 1
    bridge.gone.connect(app.quit)
    threading.Thread(target=bridge.read, args=(stream or sys.stdin,), daemon=True).start()
    return app.exec()
