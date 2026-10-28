import QtQuick
import QtQuick.Controls
Button {
    id: button
    property bool playing: false
    property bool large: false
    implicitWidth: large ? 76 : 40
    implicitHeight: implicitWidth
    hoverEnabled: true
    focusPolicy: Qt.NoFocus
    Accessible.name: playing ? "暂停" : "播放"
    background: Rectangle { radius: width/2; color: button.large ? "#aa20232b" : button.hovered ? "#35ffffff" : "transparent"; border.width: button.large ? 1 : 0; border.color: "#35ffffff" }
    contentItem: Canvas {
        id: glyphCanvas
        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
        Connections { target: button; function onPlayingChanged() { glyphCanvas.requestPaint() } }
        onPaint: {
            let c = getContext("2d"); c.reset(); c.fillStyle = "#f7f8fb"
            let size = button.large ? 27 : 18, x = (width-size)/2, y = (height-size)/2
            if (button.playing) { c.fillRect(x+2,y,5,size); c.fillRect(x+size-7,y,5,size) }
            else { c.beginPath(); c.moveTo(x+4,y); c.lineTo(x+size,y+size/2); c.lineTo(x+4,y+size); c.closePath(); c.fill() }
        }
    }
}
