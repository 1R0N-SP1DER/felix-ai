import QtQuick

// The morphing core of the phone's home screen (and the web page): one abstract form that slowly turns into silhouettes echoing the model
// families behind it, breathes, glows, and gets orbiting rings while Faze works. Drawn with a Canvas at about 30 frames a second, only while shown.
Item {
    id: root
    property string mode: "idle"      // idle | listening | thinking | speaking
    property string model: "auto"     // "auto" cycles through every form, or a backend id such as "groq"
    property real level: 0
    property color accent: "#5CC8FF"
    property color accent2: "#3D7BFF"

    readonly property int n: 120
    readonly property var cycle: ["faze", "gpt", "claude", "gemini", "qwen", "kimi", "lfm"]
    readonly property var forms: ({ "auto": "faze", "faze": "faze", "qwen": "qwen", "ollama": "qwen", "lfm": "lfm", "gemini": "gemini", "groq": "kimi", "openrouter": "gpt", "nvidia": "claude" })

    property real clock: Math.random() * 100
    property real progress: 1
    property var fromR: shape("faze")
    property var toR: shape("faze")
    property int next: 0

    function f(name, t) {
        switch (name) {
        case "gpt": return 0.84 + 0.16 * Math.cos(6 * t) + 0.035 * Math.cos(12 * t + 0.5)
        case "claude": return 0.7 + 0.3 * Math.pow(Math.abs(Math.cos(6 * t)), 0.55)
        case "gemini": return 0.42 + 0.58 * Math.pow(Math.abs(Math.cos(2 * t)), 2.4)
        case "qwen": return 1 / Math.pow(Math.pow(Math.abs(Math.cos(t)), 4) + Math.pow(Math.abs(Math.sin(t)), 4), 0.25)
        case "kimi": return 0.66 + 0.26 * Math.cos(t - 0.7) + 0.1 * Math.cos(2 * (t - 0.7)) + 0.03 * Math.cos(5 * t)
        case "lfm": return 0.86 + 0.075 * Math.cos(8 * t) + 0.05 * Math.cos(3 * t + 1.1)
        default: return 1 + 0.025 * Math.cos(3 * t + 0.6)
        }
    }

    function shape(name) {
        var out = [], max = 0
        for (var i = 0; i < n; i++) { var v = f(name, i / n * Math.PI * 2); out.push(v); if (v > max) max = v }
        for (var j = 0; j < n; j++) out[j] /= max
        return out
    }

    function goTo(name) { fromR = current(); toR = shape(name); progress = 0 }

    function current() {
        var t = progress * progress * (3 - 2 * progress)
        var out = []
        for (var i = 0; i < n; i++) out.push(fromR[i] + (toR[i] - fromR[i]) * t)
        return out
    }

    onModelChanged: if (model !== "auto") goTo(forms[model] || "faze")

    Timer {
        interval: 33; repeat: true; running: root.visible && root.width > 0
        onTriggered: {
            root.clock += 0.033
            if (root.progress < 1) root.progress = Math.min(1, root.progress + 0.033 / 1.4)
            cv.requestPaint()
        }
    }
    Timer {
        interval: 5200; repeat: true; running: root.visible && root.model === "auto" && root.mode === "idle"
        onTriggered: { root.next = (root.next + 1) % root.cycle.length; root.goTo(root.cycle[root.next]) }
    }

    Canvas {
        id: cv
        anchors.fill: parent
        onPaint: {
            var c = getContext("2d")
            c.reset()
            var w = width, h = height, cx = w / 2, cy = h / 2, R = Math.min(w, h) / 2
            var energy = root.mode === "listening" ? 0.9 : root.mode === "thinking" ? 0.7 : root.mode === "speaking" ? 1 : 0.25
            var t = root.clock
            var lv = root.mode === "idle" ? 0 : Math.max(root.level, 0.18 + 0.12 * Math.sin(t * 5))
            var breathe = 1 + 0.03 * Math.sin(t * 1.4) + lv * 0.08
            var base = R * 0.6 * breathe

            // halo
            var halo = c.createRadialGradient(cx, cy, base * 0.5, cx, cy, R)
            halo.addColorStop(0, Qt.rgba(root.accent.r, root.accent.g, root.accent.b, 0.20 + 0.25 * energy))
            halo.addColorStop(1, Qt.rgba(root.accent.r, root.accent.g, root.accent.b, 0))
            c.fillStyle = halo
            c.beginPath(); c.arc(cx, cy, R, 0, Math.PI * 2); c.fill()

            // orbiting rings while working
            if (root.mode === "thinking" || root.mode === "speaking") {
                c.strokeStyle = Qt.rgba(root.accent.r, root.accent.g, root.accent.b, 0.45)
                c.lineWidth = 1.5
                for (var k = 0; k < 2; k++) {
                    c.save()
                    c.translate(cx, cy)
                    c.rotate(t * (k === 0 ? 0.9 : -0.6) + k)
                    c.beginPath(); c.ellipse(-base * 1.25, -base * (0.5 + 0.2 * k), base * 2.5, base * (1 + 0.4 * k)); c.stroke()
                    c.restore()
                }
            }

            // the form
            var r = root.current()
            var g = c.createLinearGradient(cx - base, cy - base, cx + base, cy + base)
            g.addColorStop(0, root.accent)
            g.addColorStop(1, root.accent2)
            c.fillStyle = g
            c.beginPath()
            for (var i = 0; i <= root.n; i++) {
                var a = (i % root.n) / root.n * Math.PI * 2
                var wob = 1 + 0.035 * energy * Math.sin(3 * a + t * 2.2) + 0.02 * energy * Math.sin(5 * a - t * 3.1)
                var rr = r[i % root.n] * base * wob
                var x = cx + Math.cos(a - Math.PI / 2) * rr, y = cy + Math.sin(a - Math.PI / 2) * rr
                if (i === 0) c.moveTo(x, y); else c.lineTo(x, y)
            }
            c.closePath()
            c.fill()
        }
    }
}
