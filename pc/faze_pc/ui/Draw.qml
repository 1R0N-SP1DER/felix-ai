import QtQuick
import QtQuick.Window

// A see-through, always-on-top window over the whole screen. Mouse clicks pass straight through it. The phone's doodles are drawn here.
Window {
    id: win
    visible: true
    color: "transparent"
    flags: Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint | Qt.WindowTransparentForInput | Qt.Tool | Qt.WindowDoesNotAcceptFocus
    x: Screen.virtualX; y: Screen.virtualY
    width: Screen.width; height: Screen.height
    property var strokes: []      // [{color, pts:[x,y,...]}] in 0..1
    property var live: null

    Connections {
        target: draw
        function onStep(op, color, x, y) {
            if (op === "clear") { win.strokes = []; win.live = null }
            else if (op === "start") win.live = { "color": color, "pts": [x, y] }
            else if (op === "move" && win.live) win.live.pts.push(x, y)
            else if (op === "end" && win.live) { win.strokes.push(win.live); win.live = null }
            canvas.requestPaint()
        }
    }
    Canvas {
        id: canvas
        anchors.fill: parent
        onPaint: {
            var c = getContext("2d")
            c.reset()
            c.lineCap = "round"; c.lineJoin = "round"; c.lineWidth = 6
            var all = win.strokes.slice()
            if (win.live) all.push(win.live)
            for (var i = 0; i < all.length; i++) {
                var p = all[i].pts
                c.strokeStyle = all[i].color
                c.beginPath()
                c.moveTo(p[0] * width, p[1] * height)
                if (p.length === 2) c.lineTo(p[0] * width + 0.1, p[1] * height)
                for (var k = 2; k < p.length; k += 2) c.lineTo(p[k] * width, p[k + 1] * height)
                c.stroke()
            }
        }
    }
}
