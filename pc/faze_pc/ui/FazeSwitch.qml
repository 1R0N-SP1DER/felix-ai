import QtQuick
import QtQuick.Controls

Switch {
    id: sw
    indicator: Rectangle {
        implicitWidth: 44; implicitHeight: 26; radius: 13
        x: sw.leftPadding; y: parent.height / 2 - height / 2
        color: sw.checked ? "#5CC8FF" : "#1B2438"
        border.width: 1; border.color: sw.checked ? "#5CC8FF" : "#2E3A58"
        Rectangle { width: 20; height: 20; radius: 10; y: 3; x: sw.checked ? parent.width - width - 3 : 3; color: sw.checked ? "#04101C" : "#A5B1CA"; Behavior on x { NumberAnimation { duration: 120 } } }
    }
}
