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

    function toggle() {
        if (visible) hide(); else { show(); raise(); requestActivate(); input.forceActiveFocus() }
    }

    ListModel { id: msgs }

    function addMsg(role, text) { msgs.append({ "role": role, "text": text }); list.positionViewAtEnd() }

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
        function onFailed(m) {
            if (msgs.count > 0 && msgs.get(msgs.count - 1).role === "assistant" && msgs.get(msgs.count - 1).text === "") msgs.remove(msgs.count - 1)
            win.addMsg("error", m)
        }
        function onChatsChanged(c) { win.chats = c }
        function onConfigChanged(c) { win.cfg = c }
        function onChatChanged(id) { win.currentChat = id; if (id === "") msgs.clear() }
        function onHistoryLoaded(h) { msgs.clear(); for (var i = 0; i < h.length; i++) msgs.append({ "role": h[i].role, "text": h[i].content }); list.positionViewAtEnd() }
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
                            width: Math.min(body.implicitWidth + 28, list.width * 0.82)
                            height: body.implicitHeight + 22
                            radius: 18
                            color: role === "user" ? "#2482AFFF" : (role === "error" ? "#22FF7A8A" : win.surface)
                            border.width: 1
                            border.color: role === "error" ? win.bad : win.line
                            Text {
                                id: body
                                anchors.centerIn: parent
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
                    model: [["groq", "Groq key"], ["gemini", "Gemini key"], ["openrouter", "OpenRouter key"], ["nvidia", "NVIDIA key"]]
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
                Button { text: "Close"; onClicked: settings.close(); Layout.alignment: Qt.AlignRight }
            }
        }
    }
}
