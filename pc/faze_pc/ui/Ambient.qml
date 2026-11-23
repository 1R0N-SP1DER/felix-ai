import QtQuick

// The page's ambient light, as on the phone and the web page: two pools of blue and a faint grid that fades out from the middle.
Item {
    id: root
    property color accent: "#5CC8FF"
    property color accent2: "#3D7BFF"
    Canvas {
        anchors.fill: parent
        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
        onPaint: {
            var ctx = getContext("2d")
            var w = width, h = height
            ctx.reset()
            ctx.fillStyle = "#04060B"
            ctx.fillRect(0, 0, w, h)
            function pool(cx, cy, r, c, a) {
                var g = ctx.createRadialGradient(cx, cy, 0, cx, cy, r)
                g.addColorStop(0, Qt.rgba(c.r, c.g, c.b, a))
                g.addColorStop(1, Qt.rgba(c.r, c.g, c.b, 0))
                ctx.fillStyle = g
                ctx.fillRect(0, 0, w, h)
            }
            pool(w * 0.30, h * 0.22, Math.max(w, h) * 0.42, root.accent2, 0.16)
            pool(w * 0.78, h * 0.78, Math.max(w, h) * 0.38, root.accent, 0.10)
            // grid: 56 px cells, lines fade with distance from a point a little above the middle
            var cx = w * 0.5, cy = h * 0.38, rx = w * 0.6, ry = h * 0.55
            function fade(x, y) { var d = Math.sqrt(Math.pow((x - cx) / rx, 2) + Math.pow((y - cy) / ry, 2)); return Math.max(0, 1 - d / 0.75) }
            for (var x = 0; x < w; x += 56) {
                for (var y = 0; y < h; y += 28) {
                    var a = fade(x, y + 14) * 0.065
                    if (a <= 0.004) continue
                    ctx.fillStyle = Qt.rgba(0.51, 0.69, 1, a)
                    ctx.fillRect(x, y, 1, 28)
                }
            }
            for (var y2 = 0; y2 < h; y2 += 56) {
                for (var x2 = 0; x2 < w; x2 += 28) {
                    var a2 = fade(x2 + 14, y2) * 0.065
                    if (a2 <= 0.004) continue
                    ctx.fillStyle = Qt.rgba(0.51, 0.69, 1, a2)
                    ctx.fillRect(x2, y2, 28, 1)
                }
            }
        }
    }
}
