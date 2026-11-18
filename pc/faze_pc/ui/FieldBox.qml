import QtQuick
import QtQuick.Controls

// A small text box that saves when you leave it. [secret] hides what is typed (keys); [shown] is what is there now.
TextField {
    id: box
    property bool secret: false
    property string shown: ""
    signal saved(string value)
    implicitWidth: 200
    color: "#E9EEFB"
    placeholderTextColor: "#6D7A98"
    echoMode: secret ? TextInput.Password : TextInput.Normal
    text: secret ? "" : shown
    selectByMouse: true
    leftPadding: 12; rightPadding: 12
    background: Rectangle { radius: 12; color: "#08FFFFFF"; border.width: 1; border.color: box.activeFocus ? "#5CC8FF" : "#2182AFFF" }
    onEditingFinished: {
        if (secret) { if (text !== "") { saved(text); text = "" } }
        else if (text !== shown) saved(text)
    }
}
