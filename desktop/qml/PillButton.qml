import QtQuick
import QtQuick.Controls

Button {
    id: control
    property color ink: "#eee9df"
    property color fill: "#272930"
    property color highlight: "#ffac65"
    property bool primary: false
    property bool compact: false
    implicitHeight: compact ? 34 : 42
    implicitWidth: Math.max(compact ? 38 : 70, label.implicitWidth + 28)
    hoverEnabled: true
    contentItem: Text {
        id: label
        text: control.text
        color: control.enabled ? (control.primary ? "#20170e" : control.ink) : "#777d7b"
        font.pixelSize: 13
        font.weight: Font.Medium
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }
    background: Rectangle {
        radius: 10
        color: control.primary ? control.highlight : (control.down ? Qt.lighter(control.fill, 1.3) : control.hovered ? Qt.lighter(control.fill, 1.16) : control.fill)
        border.width: control.activeFocus ? 1 : 0
        border.color: control.highlight
        Behavior on color { ColorAnimation { duration: 100 } }
    }
}
