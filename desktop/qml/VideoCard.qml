import QtQuick
import QtQuick.Controls

Item {
    id: card
    required property var video
    property color ink: "#eee9df"
    property color subtle: "#8f9b95"
    property color surface: "#202a26"
    property color accent: "#ffac65"
    property bool reducedMotion: false
    signal selected()
    implicitHeight: width * 1.43 + 64
    Rectangle {
        id: poster
        width: parent.width
        height: width * 1.43
        radius: 14
        color: surface
        clip: true
        scale: hit.containsMouse ? 1.025 : 1
        Behavior on scale { NumberAnimation { duration: card.reducedMotion ? 0 : 160; easing.type: Easing.OutCubic } }
        Image {
            anchors.fill: parent
            source: card.video.pic || ""
            sourceSize.width: Math.ceil(card.width * 1.5)
            sourceSize.height: Math.ceil(card.width * 2.15)
            fillMode: Image.PreserveAspectCrop
            asynchronous: true
            cache: true
        }
        Text { anchors.centerIn: parent; text: "松鼠TV"; color: card.subtle; visible: !card.video.pic; font.pixelSize: 18 }
        Rectangle {
            anchors.bottom: parent.bottom; width: parent.width; height: 55
            gradient: Gradient { GradientStop { position: 0; color: "transparent" } GradientStop { position: 1; color: "#bb000000" } }
        }
        Text { anchors { left: parent.left; right: parent.right; bottom: parent.bottom; margins: 10 }  text: card.video.remark || card.video.episodeName || ""; color: "white"; font.pixelSize: 11; elide: Text.ElideRight }
        Rectangle {
            anchors.centerIn: parent; width: 46; height: 46; radius: 23; color: card.accent
            opacity: hit.containsMouse ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: card.reducedMotion ? 0 : 130 } }
            Text { anchors.centerIn: parent; text: "▶"; color: "#20170e"; font.pixelSize: 20 }
        }
    }
    Text { anchors { top: poster.bottom; topMargin: 12; left: parent.left; right: parent.right }  text: card.video.name || ""; color: card.ink; font.pixelSize: 14; font.weight: Font.DemiBold; elide: Text.ElideRight }
    Text { anchors { top: poster.bottom; topMargin: 36; left: parent.left; right: parent.right }  text: [card.video.year, card.video.area, card.video.source].filter(Boolean).join(" · "); color: card.subtle; font.pixelSize: 11; elide: Text.ElideRight }
    MouseArea { id: hit; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: card.selected() }
}
