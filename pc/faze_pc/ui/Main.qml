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
    property var found: []
    property string pairMsg: ""

    Component.onCompleted: if (bridge !== null && bridge.demoPopup === "devices") devicesPopup.open()
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
                        Text { id: chip; anchors.centerIn: parent; text: "●  " + win.routeText; color: win.dim; font.pixelSize: 12 }
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
                            placeholderText: "Message FAZE"
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
                            Text { text: modelData.state === "connected" ? "Connected · " + modelData.address : (modelData.error !== "" ? modelData.error : "Offline"); color: win.muted; font.pixelSize: 11; elide: Text.ElideRight; Layout.fillWidth: true }
                        }
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

    Popup {
        id: settings
        modal: true
        anchors.centerIn: Overlay.overlay
        width: Math.min(460, win.width - 40)
        height: Math.min(580, win.height - 40)
        padding: 18
        background: Rectangle { radius: 20; color: win.surface; border.width: 1; border.color: win.line }
        contentItem: ScrollView {
            clip: true
            ColumnLayout {
                width: settings.width - 40
                spacing: 10
                Text { text: "Settings"; color: win.text; font.pixelSize: 18; font.bold: true }
                Text { text: "Model"; color: win.dim }
                ComboBox {
                    Layout.fillWidth: true
                    model: ["auto", "groq", "gemini", "openrouter", "nvidia", "ollama", "custom"]
                    currentIndex: Math.max(0, model.indexOf(win.cfg.provider || "auto"))
                    onActivated: bridge.setSetting("provider", currentText)
                }
                Repeater {
                    model: [["groq", "Groq key"], ["gemini", "Gemini key"], ["openrouter", "OpenRouter key"], ["nvidia", "NVIDIA key"], ["tavily", "Search key (Tavily)"]]
                    delegate: ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 4
                        Text { text: modelData[1] + ((win.cfg.keys && win.cfg.keys[modelData[0]]) ? "  (set)" : ""); color: win.dim; font.pixelSize: 12 }
                        TextField {
                            Layout.fillWidth: true
                            echoMode: TextInput.Password
                            placeholderText: "paste to replace"
                            onEditingFinished: { if (text !== "") { bridge.setSetting("keys." + modelData[0], text); text = "" } }
                        }
                    }
                }
                Text { text: "Ollama chat model"; color: win.dim; font.pixelSize: 12 }
                TextField { Layout.fillWidth: true; text: win.cfg.ollama_chat_model || ""; onEditingFinished: bridge.setSetting("ollama_chat_model", text) }
                Text { text: "Ollama code and deep model"; color: win.dim; font.pixelSize: 12 }
                TextField { Layout.fillWidth: true; text: win.cfg.ollama_deep_model || ""; onEditingFinished: bridge.setSetting("ollama_deep_model", text) }
                Text { text: "Your name"; color: win.dim; font.pixelSize: 12 }
                TextField { Layout.fillWidth: true; text: win.cfg.user_name || ""; onEditingFinished: bridge.setSetting("user_name", text) }
                RowLayout {
                    Layout.fillWidth: true
                    Button { text: "Devices"; onClicked: { settings.close(); bridge.refreshDevices(); devicesPopup.open() } }
                    Button { text: "Activity log"; onClicked: { settings.close(); bridge.loadActivity() } }
                    Item { Layout.fillWidth: true }
                    Button { text: "Close"; onClicked: settings.close() }
                }
            }
        }
    }
}
