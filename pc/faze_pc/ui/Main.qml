import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ApplicationWindow {
    id: win
    readonly property bool floating: bridge !== null && bridge.floating
    visible: true
    width: floating ? 560 : 1000
    height: floating ? 660 : 700
    minimumWidth: floating ? 420 : 720
    minimumHeight: 460
    title: "Faze"
    color: floating ? "transparent" : "#04060B"
    flags: floating ? (Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint | Qt.Tool) : Qt.Window

    readonly property color bg: "#04060B"
    readonly property color surface: "#0E1421"
    readonly property color surface2: "#151D2F"
    readonly property color accent: "#5CC8FF"
    readonly property color ink: "#04101C"
    readonly property color text: "#E9EEFB"
    readonly property color dim: "#A5B1CA"
    readonly property color muted: "#6D7A98"
    readonly property color line: "#2182AFFF"
    readonly property color bad: "#FF7A8A"

    property string routeText: "Auto"
    property var chats: []
    property var cfg: ({})
    property string currentChat: ""
    property var devices: []
    property var levels: [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]
    property var found: []
    property string pairMsg: ""

    Component.onCompleted: { if (bridge !== null && bridge.demoPopup === "devices") devicesPopup.open(); if (bridge !== null && bridge.demoPopup === "mirror") mirrorPopup.open(); if (bridge !== null && bridge.demoPopup === "settings") settings.open(); if (bridge !== null && bridge.demoPopup === "models") modelSheet.open() }
    Timer { interval: 3000; repeat: true; running: devicesPopup.opened; onTriggered: bridge.refreshDevices() }

    function toggle() {
        if (visible) hide(); else { show(); raise(); requestActivate(); input.forceActiveFocus() }
    }

    ListModel { id: msgs }
    ListModel { id: activityModel }

    function addMsg(role, text, reqId) { msgs.append({ "role": role, "text": text, "reqId": reqId || "", "stage": "pending" }); list.positionViewAtEnd() }
    function settle(reqId, state) { for (var i = 0; i < msgs.count; i++) if (msgs.get(i).reqId === reqId) msgs.setProperty(i, "stage", state) }

    Connections {
        target: bridge
        function onUserMessage(t) { win.addMsg("user", t) }
        function onAssistantStart() { win.addMsg("assistant", "") }
        function onToken(t) {
            if (msgs.count > 0) { msgs.setProperty(msgs.count - 1, "text", msgs.get(msgs.count - 1).text + t); list.positionViewAtEnd() }
        }
        function onAssistantDone(stopped) {}
        function onRoute(r) { win.routeText = r }
        function onNote(m) { win.addMsg("note", m) }
        function onApproval(id, tool, summary) { win.addMsg("approve", summary, id) }
        function onToolUsed(summary, ok) { win.addMsg("note", (ok ? "✓ " : "✗ ") + summary) }
        function onActivityLoaded(rows) { activityModel.clear(); for (var i = 0; i < rows.length; i++) activityModel.append({ "kind": rows[i].kind, "text": rows[i].text }); activity.open() }
        function onFailed(m) {
            if (msgs.count > 0 && msgs.get(msgs.count - 1).role === "assistant" && msgs.get(msgs.count - 1).text === "") msgs.remove(msgs.count - 1)
            win.addMsg("error", m)
        }
        function onChatsChanged(c) { win.chats = c }
        function onHeard(t) { if (t.trim() !== "") { input.text = (input.text ? input.text + " " : "") + t; input.forceActiveFocus() } else bridge.sayNothing() }
        function onLevelChanged(v) { win.levels = win.levels.slice(1).concat([v]) }
        function onDevicesChanged(d) { win.devices = d; win.pairMsg = "" }
        function onPhonesFound(p) { win.found = p; if (p.length > 0) { addr.text = p[0].address; win.pairMsg = "Found " + p[0].name } else win.pairMsg = "No phone answered. Is Faze open on it with computers allowed?" }
        function onPairFailed(m) { win.pairMsg = m }
        function onConfigChanged(c) { win.cfg = c }
        function onChatChanged(id) { win.currentChat = id; if (id === "") msgs.clear() }
        function onHistoryLoaded(h) { msgs.clear(); for (var i = 0; i < h.length; i++) msgs.append({ "role": h[i].role, "text": h[i].content, "reqId": "", "stage": "" }); list.positionViewAtEnd() }
    }

    Shortcut { sequence: "Escape"; enabled: win.floating; onActivated: win.hide() }
    Shortcut { sequence: "Ctrl+N"; onActivated: bridge.newChat() }

    Rectangle {
        anchors.fill: parent
        radius: win.floating ? 22 : 0
        color: win.floating ? "#F004060B" : win.bg
        border.width: win.floating ? 1 : 0
        border.color: win.line
        clip: true

        RowLayout {
            anchors.fill: parent
            spacing: 0

            // Desktop: the phone's chat drawer, always open on the left.
            Rectangle {
                visible: !win.floating
                Layout.preferredWidth: 250
                Layout.fillHeight: true
                color: win.surface
                ColumnLayout {
                    anchors.fill: parent
                    anchors.margins: 14
                    spacing: 10
                    Text { text: "FAZE"; color: win.accent; font.pixelSize: 20; font.bold: true; font.letterSpacing: 3 }
                    Button {
                        Layout.fillWidth: true
                        text: "+  New chat"
                        onClicked: bridge.newChat()
                        contentItem: Text { text: parent.text; color: win.ink; font.bold: true; horizontalAlignment: Text.AlignHCenter }
                        background: Rectangle { radius: 14; color: win.accent }
                    }
                    ListView {
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        clip: true
                        spacing: 4
                        model: win.chats
                        delegate: Rectangle {
                            width: ListView.view.width
                            height: 40
                            radius: 12
                            color: modelData.id === win.currentChat ? "#2482AFFF" : (hover.hovered ? "#0FFFFFFF" : "transparent")
                            HoverHandler { id: hover }
                            Text { anchors.fill: parent; anchors.leftMargin: 12; anchors.rightMargin: 8; verticalAlignment: Text.AlignVCenter; text: modelData.title; color: win.text; elide: Text.ElideRight; font.pixelSize: 13 }
                            MouseArea { anchors.fill: parent; onClicked: bridge.openChat(modelData.id) }
                        }
                    }
                    Button {
                        Layout.fillWidth: true
                        text: "Devices   (" + win.devices.filter(function (d) { return d.state === "connected" }).length + " connected)"
                        onClicked: { bridge.refreshDevices(); devicesPopup.open() }
                        contentItem: Text { text: parent.text; color: win.dim; horizontalAlignment: Text.AlignHCenter }
                        background: Rectangle { radius: 14; color: "#08FFFFFF"; border.width: 1; border.color: win.line }
                    }
                    Button {
                        Layout.fillWidth: true
                        text: "Settings"
                        onClicked: settings.open()
                        contentItem: Text { text: parent.text; color: win.dim; horizontalAlignment: Text.AlignHCenter }
                        background: Rectangle { radius: 14; color: "#08FFFFFF"; border.width: 1; border.color: win.line }
                    }
                }
            }

            ColumnLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                spacing: 0

                // Top bar, as on the phone: the model chip on the left, a few actions on the right.
                RowLayout {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 36
                    Layout.margins: 14
                    spacing: 8
                    Rectangle {
                        radius: 14
                        color: "#08FFFFFF"
                        border.width: 1
                        border.color: win.line
                        implicitHeight: 32
                        implicitWidth: chip.implicitWidth + 24
                        Text { id: chip; anchors.centerIn: parent; text: "●  " + win.routeText + "  ▾"; color: win.dim; font.pixelSize: 12 }
                        MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: modelSheet.open() }
                    }
                    Item { Layout.fillWidth: true; Layout.preferredHeight: 32; DragHandler { enabled: win.floating; onActiveChanged: if (active) win.startSystemMove() } }
                    Button {
                        visible: win.floating
                        text: "+"
                        onClicked: bridge.newChat()
                        implicitWidth: 34; implicitHeight: 34
                        contentItem: Text { text: parent.text; color: win.dim; font.pixelSize: 18; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                        background: Rectangle { radius: 17; color: "#08FFFFFF"; border.width: 1; border.color: win.line }
                    }
                    Button {
                        visible: win.floating
                        text: "⚙"
                        onClicked: settings.open()
                        implicitWidth: 34; implicitHeight: 34
                        contentItem: Text { text: parent.text; color: win.dim; font.pixelSize: 16; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                        background: Rectangle { radius: 17; color: "#08FFFFFF"; border.width: 1; border.color: win.line }
                    }
                }

                ListView {
                    id: list
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    Layout.leftMargin: 18
                    Layout.rightMargin: 18
                    clip: true
                    spacing: 10
                    model: msgs
                    delegate: Item {
                        width: list.width
                        height: bubble.height
                        readonly property bool mine: role === "user"
                        Rectangle {
                            id: bubble
                            anchors.right: mine ? parent.right : undefined
                            anchors.left: mine ? undefined : parent.left
                            width: Math.min(Math.max(body.implicitWidth + 28, role === "approve" ? 230 : 0), list.width * 0.82)
                            height: body.implicitHeight + 22 + (role === "approve" ? 48 : 0)
                            radius: 18
                            color: role === "user" ? "#2482AFFF" : (role === "error" ? "#22FF7A8A" : (role === "approve" ? "#1AFFC857" : win.surface))
                            border.width: 1
                            border.color: role === "error" ? win.bad : win.line
                            Row {
                                visible: role === "approve"
                                anchors.left: parent.left; anchors.bottom: parent.bottom
                                anchors.leftMargin: 14; anchors.bottomMargin: 10
                                spacing: 8
                                Rectangle {
                                    visible: stage === "pending"
                                    width: 84; height: 30; radius: 15; color: win.accent
                                    Text { anchors.centerIn: parent; text: "Allow"; color: win.ink; font.bold: true }
                                    MouseArea { anchors.fill: parent; onClicked: { bridge.answer(reqId, true); win.settle(reqId, "allowed") } }
                                }
                                Rectangle {
                                    visible: stage === "pending"
                                    width: 84; height: 30; radius: 15; color: "#10FFFFFF"; border.width: 1; border.color: win.line
                                    Text { anchors.centerIn: parent; text: "Deny"; color: win.dim }
                                    MouseArea { anchors.fill: parent; onClicked: { bridge.answer(reqId, false); win.settle(reqId, "denied") } }
                                }
                                Text { visible: stage !== "pending"; text: stage === "allowed" ? "Allowed" : "Denied"; color: win.muted; font.pixelSize: 12; height: 30; verticalAlignment: Text.AlignVCenter }
                            }
                            Text {
                                id: body
                                anchors.top: parent.top
                                anchors.topMargin: 11
                                anchors.horizontalCenter: parent.horizontalCenter
                                width: Math.min(implicitWidth, list.width * 0.82 - 28)
                                text: model.text === "" ? "…" : model.text
                                color: role === "note" ? win.muted : (role === "error" ? win.bad : win.text)
                                wrapMode: Text.Wrap
                                font.pixelSize: 14
                                textFormat: Text.PlainText
                            }
                        }
                    }
                }

                // The input bar, as on the phone: a rounded field with the send button that turns into stop.
                Rectangle {
                    Layout.fillWidth: true
                    Layout.margins: 14
                    radius: 24
                    color: win.surface2
                    border.width: 1
                    border.color: input.activeFocus ? win.accent : win.line
                    implicitHeight: Math.max(52, Math.min(input.implicitHeight + 18, 160))
                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 18
                        anchors.rightMargin: 8
                        TextArea {
                            id: input
                            Layout.fillWidth: true
                            Layout.alignment: Qt.AlignVCenter
                            placeholderText: (bridge !== null && bridge.listening) ? "Listening… tap ■ to send" : "Message FAZE"
                            placeholderTextColor: win.muted
                            color: win.text
                            font.pixelSize: 14
                            wrapMode: TextEdit.Wrap
                            background: null
                            Keys.onPressed: function (e) {
                                if ((e.key === Qt.Key_Return || e.key === Qt.Key_Enter) && !(e.modifiers & Qt.ShiftModifier)) {
                                    e.accepted = true
                                    if (!bridge.busy) { bridge.send(text); text = "" }
                                }
                            }
                        }
                        Rectangle {
                            Layout.alignment: Qt.AlignVCenter
                            implicitWidth: 38; implicitHeight: 38; radius: 19
                            color: win.surface; border.width: 1; border.color: win.line
                            Row { anchors.centerIn: parent; spacing: 2
                                Repeater { model: [6, 12, 16, 10, 5]; Rectangle { width: 2.5; height: modelData; radius: 1; color: win.text; anchors.verticalCenter: parent.verticalCenter } } }
                            MouseArea { anchors.fill: parent; onClicked: bridge.startVoiceChat() }
                        }
                        Rectangle {
                            Layout.alignment: Qt.AlignVCenter
                            implicitWidth: 52; implicitHeight: 38; radius: 19
                            color: win.surface; border.width: 1; border.color: win.line
                            Text { anchors.centerIn: parent; text: "Live"; color: win.accent; font.pixelSize: 13; font.bold: true }
                            MouseArea { anchors.fill: parent; onClicked: bridge.startLive() }
                        }
                        Rectangle {
                            Layout.alignment: Qt.AlignVCenter
                            implicitWidth: 38; implicitHeight: 38; radius: 19
                            color: (bridge !== null && bridge.listening) ? win.bad : win.surface
                            border.width: 1; border.color: win.line
                            Text { anchors.centerIn: parent; text: (bridge !== null && bridge.listening) ? "■" : "🎙"; color: win.text; font.pixelSize: 15 }
                            MouseArea { anchors.fill: parent; onClicked: { if (bridge.listening) bridge.stopListening(); else bridge.startListening() } }
                        }
                        Rectangle {
                            Layout.alignment: Qt.AlignVCenter
                            implicitWidth: 38; implicitHeight: 38; radius: 19
                            color: win.accent
                            Text { anchors.centerIn: parent; text: (bridge !== null && bridge.busy) ? "■" : "↑"; color: win.ink; font.pixelSize: 17; font.bold: true }
                            MouseArea { anchors.fill: parent; onClicked: { if (bridge.busy) bridge.stop(); else { bridge.send(input.text); input.text = "" } } }
                        }
                    }
                }
            }
        }
    }

    Popup {
        id: voicePopup
        modal: true
        visible: bridge !== null && bridge.voiceState !== "off"
        anchors.centerIn: Overlay.overlay
        width: Math.min(560, win.width - 30)
        height: Math.min(420, win.height - 30)
        padding: 20
        closePolicy: Popup.NoAutoClose
        background: Rectangle { radius: 24; color: win.surface; border.width: 1; border.color: win.line }
        contentItem: ColumnLayout {
            spacing: 16
            Text { text: bridge === null ? "" : ((bridge.live ? "Live · " : "") + (bridge.voiceState === "listening" ? "Listening…" : bridge.voiceState === "thinking" ? "Thinking…" : "Speaking…")); color: win.text; font.pixelSize: 18; Layout.alignment: Qt.AlignHCenter }
            Row {
                Layout.alignment: Qt.AlignHCenter; Layout.fillHeight: true
                spacing: 4
                Repeater {
                    model: win.levels.length
                    Rectangle {
                        width: 8; radius: 4
                        height: 8 + Math.min(1, win.levels[index] * 1.6) * 110
                        anchors.verticalCenter: parent.verticalCenter
                        color: bridge !== null && bridge.voiceState === "speaking" ? win.accent : win.text
                        opacity: 0.35 + 0.65 * Math.min(1, win.levels[index] * 1.6)
                    }
                }
            }
            RowLayout {
                Layout.alignment: Qt.AlignHCenter; spacing: 18
                Rectangle { implicitWidth: 56; implicitHeight: 56; radius: 28; color: win.surface2; border.width: 1; border.color: win.line
                    Text { anchors.centerIn: parent; text: "■"; color: win.text; font.pixelSize: 18 }
                    MouseArea { anchors.fill: parent; onClicked: bridge.stop() } }
                Rectangle { implicitWidth: 56; implicitHeight: 56; radius: 28; color: win.bad
                    Text { anchors.centerIn: parent; text: "✕"; color: "#04060B"; font.pixelSize: 20; font.bold: true }
                    MouseArea { anchors.fill: parent; onClicked: bridge.stopVoiceChat() } }
            }
            Text { text: "Talk normally. It answers when you pause. ■ cuts the voice, ✕ ends."; color: win.muted; font.pixelSize: 11; Layout.alignment: Qt.AlignHCenter }
        }
    }

    Popup {
        id: mirrorPopup
        modal: true
        anchors.centerIn: Overlay.overlay
        width: Math.min(520, win.width - 30)
        height: Math.min(820, win.height - 30)
        padding: 14
        property string problem: ""
        onOpened: { problem = ""; bridge.startMirror() }
        onClosed: bridge.stopMirror()
        background: Rectangle { radius: 20; color: win.surface; border.width: 1; border.color: win.line }
        Connections { target: bridge; function onMirrorFailed(m) { mirrorPopup.problem = m } }
        contentItem: ColumnLayout {
            spacing: 8
            RowLayout {
                Layout.fillWidth: true
                Text { text: "Phone screen"; color: win.text; font.pixelSize: 16; font.bold: true; Layout.fillWidth: true }
                Button { text: "Back"; onClicked: bridge.mirrorKey("back") }
                Button { text: "Home"; onClicked: bridge.mirrorKey("home") }
                Button { text: "Close"; onClicked: mirrorPopup.close() }
            }
            Text { visible: mirrorPopup.problem !== ""; text: mirrorPopup.problem; color: win.bad; font.pixelSize: 12; wrapMode: Text.Wrap; Layout.fillWidth: true }
            Rectangle {
                Layout.fillWidth: true; Layout.fillHeight: true
                color: "#05080F"; radius: 12; clip: true
                Image {
                    id: screenImg
                    anchors.fill: parent
                    fillMode: Image.PreserveAspectFit
                    cache: false
                    source: bridge !== null && bridge.frameNo > 0 ? "image://phone/frame?" + bridge.frameNo : ""
                }
                Text { visible: bridge !== null && bridge.frameNo === 0 && mirrorPopup.problem === ""; anchors.centerIn: parent; text: "Waiting for the phone…"; color: win.dim }
                MouseArea {
                    id: pad
                    anchors.fill: parent
                    property real sx: 0; property real sy: 0; property var t0: 0
                    function fx(v) { var w = screenImg.paintedWidth, off = (width - w) / 2; return Math.max(0, Math.min(1, (v - off) / w)) }
                    function fy(v) { var h = screenImg.paintedHeight, off = (height - h) / 2; return Math.max(0, Math.min(1, (v - off) / h)) }
                    onPressed: function (m) { sx = m.x; sy = m.y; t0 = Date.now() }
                    onReleased: function (m) {
                        var moved = Math.abs(m.x - sx) + Math.abs(m.y - sy)
                        if (moved < 12) bridge.mirrorInput(Date.now() - t0 > 500 ? "long" : "tap", fx(sx), fy(sy), 0, 0)
                        else bridge.mirrorInput("swipe", fx(sx), fy(sy), fx(m.x), fy(m.y))
                    }
                    onWheel: function (w) { bridge.mirrorInput("swipe", 0.5, w.angleDelta.y > 0 ? 0.3 : 0.7, 0.5, w.angleDelta.y > 0 ? 0.7 : 0.3) }
                }
            }
            RowLayout {
                Layout.fillWidth: true
                TextField { id: mirrorText; Layout.fillWidth: true; placeholderText: "Type on the phone"; onAccepted: { bridge.mirrorText(text); text = "" } }
                Button { text: "Send"; onClicked: { bridge.mirrorText(mirrorText.text); mirrorText.text = "" } }
            }
            Text { text: "Click to tap, hold to long-press, drag to swipe, scroll to scroll. Turn on Allow screen view and control on the phone first."; color: win.muted; font.pixelSize: 11; wrapMode: Text.Wrap; Layout.fillWidth: true }
        }
    }

    Popup {
        id: devicesPopup
        modal: true
        anchors.centerIn: Overlay.overlay
        width: Math.min(480, win.width - 40)
        height: Math.min(560, win.height - 40)
        padding: 18
        background: Rectangle { radius: 20; color: win.surface; border.width: 1; border.color: win.line }
        contentItem: ColumnLayout {
            spacing: 10
            Text { text: "Devices"; color: win.text; font.pixelSize: 18; font.bold: true }
            Text { text: "This PC and your phone work as one Faze. The phone is the hub."; color: win.muted; font.pixelSize: 12; wrapMode: Text.Wrap; Layout.fillWidth: true }
            Repeater {
                model: win.devices
                delegate: Rectangle {
                    Layout.fillWidth: true
                    implicitHeight: row.implicitHeight + 20
                    radius: 14; color: win.surface2; border.width: 1; border.color: win.line
                    RowLayout {
                        id: row
                        anchors.fill: parent; anchors.margins: 12; spacing: 10
                        Rectangle { width: 10; height: 10; radius: 5; color: modelData.state === "connected" ? "#5EE6A8" : "#6D7A98" }
                        ColumnLayout {
                            Layout.fillWidth: true; spacing: 2
                            Text { text: modelData.name; color: win.text; font.pixelSize: 14 }
                            Text { text: modelData.state === "connected" ? "Connected · " + modelData.address + (modelData.synced ? " · " + modelData.synced : "") : (modelData.error !== "" ? modelData.error : "Offline"); color: win.muted; font.pixelSize: 11; elide: Text.ElideRight; Layout.fillWidth: true }
                        }
                        Button { text: "Screen"; visible: modelData.state === "connected"; onClicked: { devicesPopup.close(); bridge.openMirrorWindow() } }
                        Button { text: "Forget"; onClicked: bridge.forgetDevice(modelData.id) }
                    }
                }
            }
            Text { visible: win.devices.length === 0; text: "No phone paired yet."; color: win.dim; font.pixelSize: 13 }
            Rectangle { Layout.fillWidth: true; height: 1; color: win.line }
            Text { text: "Pair a phone"; color: win.text; font.pixelSize: 14; font.bold: true }
            Text { text: "On the phone: Settings > Devices > Pair a computer. It shows a code. Then find the phone here and type the code."; color: win.muted; font.pixelSize: 12; wrapMode: Text.Wrap; Layout.fillWidth: true }
            RowLayout {
                Layout.fillWidth: true
                TextField { id: addr; Layout.fillWidth: true; placeholderText: "Phone address, e.g. 192.168.43.1" }
                Button { text: "Find"; onClicked: bridge.findPhones() }
            }
            RowLayout {
                Layout.fillWidth: true
                TextField { id: code; Layout.fillWidth: true; placeholderText: "Code from the phone"; inputMethodHints: Qt.ImhUppercaseOnly }
                Button { text: "Pair"; onClicked: { win.pairMsg = "Pairing…"; bridge.pairPhone(addr.text, code.text); code.text = "" } }
            }
            Text { visible: win.pairMsg !== ""; text: win.pairMsg; color: win.dim; font.pixelSize: 12; wrapMode: Text.Wrap; Layout.fillWidth: true }
            Item { Layout.fillHeight: true }
            Button { text: "Close"; onClicked: devicesPopup.close(); Layout.alignment: Qt.AlignRight }
        }
    }

    Popup {
        id: activity
        modal: true
        anchors.centerIn: Overlay.overlay
        width: Math.min(560, win.width - 40)
        height: Math.min(520, win.height - 40)
        padding: 16
        background: Rectangle { radius: 20; color: win.surface; border.width: 1; border.color: win.line }
        contentItem: ColumnLayout {
            Text { text: "Activity log"; color: win.text; font.pixelSize: 18; font.bold: true }
            ListView {
                Layout.fillWidth: true; Layout.fillHeight: true; clip: true; spacing: 6
                model: activityModel
                delegate: Text { width: ListView.view.width; text: kind + "   " + model.text; color: win.dim; font.pixelSize: 12; wrapMode: Text.Wrap }
            }
            Button { text: "Close"; onClicked: activity.close(); Layout.alignment: Qt.AlignRight }
        }
    }

    // Settings: a full page of cards, like the phone's.
    Popup {
        id: settings
        modal: true
        x: 0; y: 0
        width: win.width
        height: win.height
        padding: 0
        background: Rectangle { color: win.bg }
        onOpened: bridge.loadModels()
        contentItem: ColumnLayout {
            spacing: 0
            RowLayout {
                Layout.fillWidth: true
                Layout.margins: 14
                Button {
                    text: "←"
                    onClicked: settings.close()
                    implicitWidth: 38; implicitHeight: 38
                    contentItem: Text { text: parent.text; color: win.text; font.pixelSize: 20; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                    background: Rectangle { radius: 19; color: "#08FFFFFF"; border.width: 1; border.color: win.line }
                }
                Text { text: "Settings"; color: win.text; font.pixelSize: 20; font.bold: true; Layout.leftMargin: 8 }
            }
            ScrollView {
                id: scroller
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                contentWidth: availableWidth
                ColumnLayout {
                    width: Math.min(660, scroller.availableWidth - 32)
                    x: Math.max(16, (scroller.availableWidth - width) / 2)
                    spacing: 4

                    SettingsCard {
                        title: "MODEL"
                        SettingRow {
                            label: "Model"; sub: win.routeText
                            FazeButton { text: "Choose"; onClicked: { settings.close(); modelSheet.open() } }
                        }
                        SettingRow {
                            label: "Thinking"; sub: "How hard it thinks on a request. Higher is slower."
                            Row { spacing: 6
                                Repeater {
                                    model: [["low", "Low"], ["medium", "Medium"], ["high", "High"]]
                                    delegate: FazeButton {
                                        text: modelData[1]; checkable: true; checked: (win.cfg.effort || "medium") === modelData[0]
                                        onClicked: bridge.setEffort(modelData[0])
                                    }
                                }
                            }
                        }
                        SettingRow { label: "Ollama address"; sub: "Where Ollama runs. Models on this PC are used when it is."
                            FieldBox { shown: win.cfg.ollama_url || ""; onSaved: function (v) { bridge.setSetting("ollama_url", v) } } }
                        SettingRow { label: "Offline chat model"; sub: "Small and quick, like LFM. Used in Auto for casual requests."
                            FieldBox { shown: win.cfg.ollama_chat_model || ""; onSaved: function (v) { bridge.setSetting("ollama_chat_model", v) } } }
                        SettingRow { label: "Offline code and deep model"; sub: "Bigger, like Qwen. Used in Auto for code and hard requests."
                            FieldBox { shown: win.cfg.ollama_deep_model || ""; onSaved: function (v) { bridge.setSetting("ollama_deep_model", v) } } }
                        SettingRow { label: "Let local models use tools"; sub: "Small models can get tool calls wrong. Off keeps them to plain answers."; last: true
                            FazeSwitch { checked: win.cfg.ollama_tools === true; onToggled: bridge.setSetting("ollama_tools", checked ? "true" : "false") } }
                    }

                    SettingsCard {
                        title: "VOICE"
                        SettingRow { label: "Speak replies aloud"
                            FazeSwitch { checked: win.cfg.speak === true; onToggled: bridge.setSetting("speak", checked ? "true" : "false") } }
                        SettingRow { label: "Voice"; sub: "A Gemini voice name, like Kore."
                            FieldBox { shown: win.cfg.voice || ""; onSaved: function (v) { bridge.setSetting("voice", v) } } }
                        SettingRow { label: "Let me interrupt in Live"; sub: "Off keeps the mic muted while it talks, so it cannot hear itself."; last: true
                            FazeSwitch { checked: win.cfg.live_barge_in === true; onToggled: bridge.setSetting("live_barge_in", checked ? "true" : "false") } }
                    }

                    SettingsCard {
                        title: "DEVICES"
                        SettingRow { label: "Share the clipboard"; sub: "Text you copy here goes to your phone and the other way, only while they are linked."
                            FazeSwitch { checked: win.cfg.clip_sync !== false; onToggled: bridge.setSetting("clip_sync", checked ? "true" : "false") } }
                        SettingRow { label: "Push the mouse against the left edge to show my phone"; sub: "Shows your phone's screen in its own window."
                            FazeSwitch { checked: win.cfg.edge_open === true; onToggled: bridge.setSetting("edge_open", checked ? "true" : "false") } }
                        SettingRow { label: "Let my phone see and control this PC"; sub: "Screen, mouse and keyboard, sound, microphone and webcam. Off until you switch it on."
                            FazeSwitch { checked: win.cfg.allow_remote === true; onToggled: bridge.setSetting("allow_remote", checked ? "true" : "false") } }
                        SettingRow { label: "Let my phone open all my files"; sub: "Read only. Off shares only the folders below."
                            FazeSwitch { checked: win.cfg.share_all === true; onToggled: bridge.setSetting("share_all", checked ? "true" : "false") } }
                        SettingRow { label: "Folders shared with my phone"; sub: "Comma separated, read only."
                            FieldBox { shown: win.cfg.share_folders || ""; onSaved: function (v) { bridge.setSetting("share_folders", v) } } }
                        SettingRow { label: "Devices"; sub: "Pair and manage your phone."; last: true
                            FazeButton { text: "Open"; onClicked: { settings.close(); bridge.refreshDevices(); devicesPopup.open() } } }
                    }

                    SettingsCard {
                        title: "FAZE"
                        Repeater {
                            model: [["groq", "Groq"], ["gemini", "Gemini"], ["openrouter", "OpenRouter"], ["nvidia", "NVIDIA"]]
                            delegate: ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 0
                                SettingRow { label: modelData[1] + " key"; sub: (win.cfg.keys && win.cfg.keys[modelData[0]]) ? "Saved. Paste a new one to replace it." : "Not set yet."
                                    FieldBox { secret: true; placeholderText: "paste a key"; onSaved: function (v) { bridge.setSetting("keys." + modelData[0], v) } } }
                                SettingRow { label: modelData[1] + " model"; sub: "Leave blank for the default."
                                    FieldBox { shown: (win.cfg.models && win.cfg.models[modelData[0]]) || ""; onSaved: function (v) { bridge.setSetting("models." + modelData[0], v) } } }
                            }
                        }
                        SettingRow { label: "Custom address"; sub: "Any OpenAI-style server, like https://host/v1"
                            FieldBox { shown: win.cfg.custom_url || ""; onSaved: function (v) { bridge.setSetting("custom_url", v) } } }
                        SettingRow { label: "Custom key"
                            FieldBox { secret: true; placeholderText: "paste a key"; onSaved: function (v) { bridge.setSetting("keys.custom", v) } } }
                        SettingRow { label: "Custom model"
                            FieldBox { shown: (win.cfg.models && win.cfg.models.custom) || ""; onSaved: function (v) { bridge.setSetting("models.custom", v) } } }
                        SettingRow { label: "Search key"; sub: (win.cfg.keys && win.cfg.keys.tavily) ? "Tavily, saved." : "Tavily, for web search. Not set yet."
                            FieldBox { secret: true; placeholderText: "paste a key"; onSaved: function (v) { bridge.setSetting("keys.tavily", v) } } }
                        SettingRow { label: "Your name"; last: true
                            FieldBox { shown: win.cfg.user_name || ""; onSaved: function (v) { bridge.setSetting("user_name", v) } } }
                    }

                    SettingsCard {
                        title: "DIAGNOSTICS"
                        SettingRow { label: "Activity log"; sub: "What Faze did on this PC, newest first."; last: true
                            FazeButton { text: "Open"; onClicked: { settings.close(); bridge.loadActivity() } } }
                    }
                    Item { Layout.preferredHeight: 24 }
                }
            }
        }
    }

    // The model picker, opened from the chip at the top: the same list as the phone's, plus the models installed on this PC.
    Popup {
        id: modelSheet
        modal: true
        anchors.centerIn: Overlay.overlay
        width: Math.min(460, win.width - 40)
        height: Math.min(620, win.height - 40)
        padding: 18
        property var sheet: ({ rows: [], local: [], running: false, efforts: [] })
        onOpened: bridge.loadModels()
        Connections { target: bridge; function onModelsLoaded(m) { modelSheet.sheet = m } }
        background: Rectangle { radius: 20; color: win.surface; border.width: 1; border.color: win.line }
        contentItem: ColumnLayout {
            spacing: 8
            Text { text: "Choose a model"; color: win.text; font.pixelSize: 18; font.bold: true; Layout.bottomMargin: 6 }
            ScrollView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                contentWidth: availableWidth
                ColumnLayout {
                    width: modelSheet.width - 40
                    spacing: 6
                    Repeater {
                        model: modelSheet.sheet.rows
                        delegate: ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 4
                            Rectangle {
                                Layout.fillWidth: true
                                implicitHeight: 58
                                radius: 14
                                color: modelData.chosen ? "#2482AFFF" : (rowHover.hovered ? "#0FFFFFFF" : "#08FFFFFF")
                                border.width: 1
                                border.color: modelData.chosen ? win.accent : win.line
                                HoverHandler { id: rowHover }
                                RowLayout {
                                    anchors.fill: parent; anchors.leftMargin: 14; anchors.rightMargin: 14; spacing: 10
                                    Rectangle { width: 9; height: 9; radius: 5; color: modelData.dot === "ok" ? "#6BE3A0" : (modelData.dot === "warn" ? "#FFC857" : (modelData.dot === "bad" ? win.bad : "#6D7A98")) }
                                    ColumnLayout {
                                        Layout.fillWidth: true; spacing: 1
                                        Text { text: modelData.name; color: win.text; font.pixelSize: 14; font.bold: modelData.chosen }
                                        Text { text: modelData.sub; color: win.muted; font.pixelSize: 11; elide: Text.ElideRight; Layout.fillWidth: true }
                                    }
                                }
                                MouseArea { anchors.fill: parent; onClicked: { bridge.pickModel(modelData.id, ""); if (modelData.id !== "ollama") modelSheet.close() } }
                            }
                            Repeater {
                                model: modelData.id === "ollama" ? modelSheet.sheet.local : []
                                delegate: Rectangle {
                                    Layout.fillWidth: true
                                    Layout.leftMargin: 22
                                    implicitHeight: 40
                                    radius: 12
                                    color: (win.cfg.provider === "ollama" && win.cfg.ollama_chat_model === modelData.name) ? "#2482AFFF" : "#06FFFFFF"
                                    border.width: 1; border.color: win.line
                                    RowLayout { anchors.fill: parent; anchors.leftMargin: 12; anchors.rightMargin: 12
                                        Text { text: modelData.name; color: win.text; font.pixelSize: 13; Layout.fillWidth: true }
                                        Text { text: modelData.gb + " GB"; color: win.muted; font.pixelSize: 11 } }
                                    MouseArea { anchors.fill: parent; onClicked: { bridge.pickModel("ollama", modelData.name); modelSheet.close() } }
                                }
                            }
                        }
                    }
                    Text { text: "Thinking"; color: win.dim; font.pixelSize: 12; Layout.topMargin: 8 }
                    Row {
                        spacing: 6
                        Repeater {
                            model: modelSheet.sheet.efforts
                            delegate: FazeButton { text: modelData.name; checkable: true; checked: modelData.chosen; onClicked: bridge.setEffort(modelData.id) }
                        }
                    }
                }
            }
            FazeButton { text: "Close"; onClicked: modelSheet.close(); Layout.alignment: Qt.AlignRight }
        }
    }
}
