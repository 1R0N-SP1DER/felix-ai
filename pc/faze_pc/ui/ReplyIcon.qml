import QtQuick

// One small line icon on a 24 x 24 grid, like the phone's: copy, speaker, up, down, refresh.
Item {
    id: root
    property string kind: "copy"
    property color tint: "#A5B1CA"
    signal clicked()
    implicitWidth: 30
    implicitHeight: 28
    onTintChanged: cv.requestPaint()
    onKindChanged: cv.requestPaint()
    Rectangle { anchors.fill: parent; radius: 8; color: hover.hovered ? "#14FFFFFF" : "transparent" }
    Canvas {
        id: cv
        width: 17; height: 17
        anchors.centerIn: parent
        onPaint: {
            var c = getContext("2d")
            c.reset()
            c.scale(width / 24, height / 24)
            c.strokeStyle = root.tint
            c.lineWidth = 1.9
            c.lineCap = "round"
            c.lineJoin = "round"
            function rr(x, y, w, h, r) { c.beginPath(); c.moveTo(x + r, y); c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r); c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r); c.closePath(); c.stroke() }
            function poly(p, close) { c.beginPath(); c.moveTo(p[0], p[1]); for (var i = 2; i < p.length; i += 2) c.lineTo(p[i], p[i + 1]); if (close) c.closePath(); c.stroke() }
            if (root.kind === "copy") {
                rr(8.5, 8.5, 11.5, 11.5, 2.5)
                poly([15.5, 8.5, 15.5, 5.5, 13, 4, 6, 4, 4, 6, 4, 13, 5.5, 15, 8.5, 15], false)
            } else if (root.kind === "speaker") {
                poly([4, 9.5, 8, 9.5, 12.5, 5.5, 12.5, 18.5, 8, 14.5, 4, 14.5], true)
                c.beginPath(); c.arc(12.5, 12, 4, -0.87, 0.87); c.stroke()
                c.beginPath(); c.arc(12.5, 12, 7.5, -0.87, 0.87); c.stroke()
            } else if (root.kind === "up" || root.kind === "down") {
                var up = root.kind === "up"
                function y(v) { return up ? v : 24 - v }
                rr(3.5, up ? 11 : 3.5, 3.5, 9.5, 1.2)
                poly([7, y(11), 11, y(3.5), 13, y(4.5), 12, y(9.5), 18.5, y(9.5), 20.5, y(11.5), 19, y(19), 17.5, y(20.5), 7, y(20.5)], false)
            } else {
                c.beginPath(); c.arc(12, 12, 7.5, -1.05, 4.0); c.stroke()
                poly([15.5, 2.5, 19.5, 4.5, 17.5, 8.5], false)
            }
        }
    }
    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: root.clicked() }
}
