import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs
import QtQuick.Window
import SquirrelTV.Player 1.0

ApplicationWindow {
    id: win
    width: 1320; height: 850; minimumWidth: 960; minimumHeight: 640
    visible: true
    title: "松鼠TV"
    color: bg
    font.family: "Microsoft YaHei UI"
    palette.window: panel
    palette.windowText: ink
    palette.base: raised
    palette.text: ink
    palette.button: raised
    palette.buttonText: ink
    palette.highlight: accent
    palette.highlightedText: "#20170e"
    property int theme: Number(Backend.setting("theme", 0))
    property bool reduced: Boolean(Backend.setting("reducedMotion", false))
    property real holdSpeed: Number(Backend.setting("holdSpeed", 3))
    property bool hardware: Boolean(Backend.setting("hardware", true))
    property string page: "home"
    property bool theater: false
    property bool full: visibility === Window.FullScreen
    property bool controlsShown: true
    property bool showHelp: false
    property int sourceIndex: Number(Backend.setting("source", 0))
    property string category: ""
    property string keyword: ""
    property int selectedLine: 0
    property int selectedEpisode: 0
    property color bg: ["#101a17", "#101117", "#f3eee4", "#081610"][theme]
    property color panel: ["#18241f", "#1b1d28", "#fffcf6", "#0d2117"][theme]
    property color raised: ["#23332b", "#282b3b", "#e9dfd0", "#153522"][theme]
    property color ink: ["#f0f0e7", "#ececfa", "#3e342a", "#b2ffb3"][theme]
    property color muted: ["#92a297", "#989ab0", "#8e7e6c", "#62a575"][theme]
    property color accent: ["#ffb36b", "#a7a6ff", "#ae633d", "#80ef95"][theme]
    property var nav: [{key:"home",icon:"⌂",name:"发现影片"},{key:"player",icon:"▷",name:"正在播放"},{key:"downloads",icon:"↓",name:"离线下载"},{key:"favorites",icon:"♡",name:"我的收藏"},{key:"history",icon:"◷",name:"观看历史"},{key:"marks",icon:"◇",name:"喜欢的片刻"}]
    function time(value) { let s = Math.max(0, Math.floor(value || 0)); return (s >= 3600 ? Math.floor(s/3600) + ":" : "") + String(Math.floor(s/60)%60).padStart(2,"0") + ":" + String(s%60).padStart(2,"0") }
    function toggleFull() { player.endHold(); if (full) showNormal(); else showFullScreen() }
    function go(key) { if (page === "player" && key !== "player") { player.endHold(); player.pause(); savePosition() }  page = key; controlsShown = true }
    function savePosition() { if (player.loaded) Backend.remember(player.position, player.duration) }
    function browse(n) { Backend.browse(sourceIndex, keyword, category, n || 1) }
    function details(v) { selectedLine = 0; selectedEpisode = 0; Backend.inspect(v); detailDialog.open() }
    function nextEpisode() {
        let lines = Backend.current.lines || []; let line = Number(Backend.current.line || 0); let ep = Number(Backend.current.episode || 0) + 1
        if (lines[line] && ep < lines[line].episodes.length) { savePosition(); Backend.inspect(Backend.current); Backend.playEpisode(line, ep, 0) }
    }
    onActiveChanged: if (!active) { player.endHold(); holdTimer.stop() }
    onClosing: { player.endHold(); savePosition() }
    Component.onCompleted: {
        if (sourceIndex >= Backend.sources.length) sourceIndex = 0
        player.setHardware(hardware)
        if (!Qt.application.arguments.includes("--self-test")) browse(1)
    }
    Connections {
        target: Backend
        function onPlayRequested(url, start) { page = "player"; detailDialog.close(); controlsShown = true; player.open(url, start) }
        function onStatusChanged() { toastTimer.restart() }
    }
    Timer { id: toastTimer; interval: 5000 }
    Timer { interval: 5000; running: player.loaded; repeat: true; onTriggered: win.savePosition() }
    Timer { id: hideTimer; interval: 2400; onTriggered: if (!player.paused && !seekBar.pressed && !player.holding && !playerMenu.opened) controlsShown = false }
    Timer { id: holdTimer; interval: 180; onTriggered: player.beginHold(win.holdSpeed) }
    Shortcut { sequence: "Space"; enabled: win.page === "player" && !urlDialog.opened && !detailDialog.opened; onActivated: player.togglePause() }
    Shortcut { sequence: "Left"; enabled: win.page === "player"; onActivated: player.skip(-5) }
    Shortcut { sequence: "Right"; enabled: win.page === "player"; onActivated: player.skip(5) }
    Shortcut { sequence: "Up"; enabled: win.page === "player"; onActivated: player.setVolume(player.volume + 5) }
    Shortcut { sequence: "Down"; enabled: win.page === "player"; onActivated: player.setVolume(player.volume - 5) }
    Shortcut { sequence: "F"; enabled: win.page === "player"; onActivated: win.toggleFull() }
    Shortcut { sequence: "Escape"; onActivated: { player.endHold(); if (win.full) win.showNormal(); else if (win.theater) win.theater = false } }
    Shortcut { sequence: "Ctrl+O"; onActivated: fileDialog.open() }
    Shortcut { sequence: "Ctrl+L"; onActivated: urlDialog.open() }

    RowLayout {
        anchors.fill: parent; spacing: 0
        Rectangle {
            Layout.preferredWidth: 210
            Layout.fillHeight: true; visible: !win.full && !(win.theater && win.page === "player"); color: win.panel
            ColumnLayout {
                anchors { fill: parent; margins: 20 }  spacing: 8
                RowLayout {
                    Layout.topMargin: 12; Layout.bottomMargin: 34; spacing: 12
                    Image { source: "qrc:/design/app-icon/sstv.png"; Layout.preferredWidth: 42; Layout.preferredHeight: 42; sourceSize.width: 84; sourceSize.height: 84 }
                    Column { spacing: 2; Text { text: "松鼠TV"; color: win.ink; font.pixelSize: 20; font.bold: true }  Text { text: "让好故事，留久一点"; color: win.muted; font.pixelSize: 10 } }
                }
                Text { text: "你的放映室"; color: win.muted; font.pixelSize: 11; Layout.leftMargin: 12; Layout.bottomMargin: 6 }
                Repeater {
                    model: win.nav
                    delegate: Rectangle {
                        required property var modelData
                        Layout.fillWidth: true; Layout.preferredHeight: 46; radius: 10
                        color: win.page === modelData.key ? win.raised : navHit.containsMouse ? Qt.lighter(win.panel, 1.2) : "transparent"
                        Row { anchors { left: parent.left; leftMargin: 14; verticalCenter: parent.verticalCenter }  spacing: 14
                            Text { text: modelData.icon; color: win.page === modelData.key ? win.accent : win.muted; font.pixelSize: 20; width: 20 }
                            Text { text: modelData.name; color: win.page === modelData.key ? win.ink : win.muted; font.pixelSize: 13; anchors.verticalCenter: parent.verticalCenter }
                        }
                        Rectangle { width: 3; height: 18; radius: 2; color: win.accent; anchors { left: parent.left; verticalCenter: parent.verticalCenter }  visible: win.page === modelData.key }
                        MouseArea { id: navHit; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: win.go(modelData.key) }
                    }
                }
                Item { Layout.fillHeight: true }
                Rectangle { Layout.fillWidth: true; height: 1; color: win.raised; Layout.bottomMargin: 8 }
                PillButton { text: "＋  打开本地视频"; Layout.fillWidth: true; ink: win.ink; fill: win.raised; onClicked: fileDialog.open() }
                PillButton { text: "⚙  偏好与片源"; Layout.fillWidth: true; ink: win.muted; fill: "transparent"; onClicked: settingsDrawer.open() }
                Text { text: "WINDOWS  /  0.1 PREVIEW"; color: win.muted; font.pixelSize: 9; Layout.alignment: Qt.AlignHCenter; Layout.topMargin: 8 }
            }
        }
        ColumnLayout {
            Layout.fillWidth: true; Layout.fillHeight: true; spacing: 0
            Item {
                Layout.fillWidth: true; Layout.preferredHeight: 84; visible: !win.full
                RowLayout {
                    anchors { fill: parent; leftMargin: 32; rightMargin: 32 }  spacing: 12
                    Text { text: win.nav.find(n => n.key === win.page)?.name || "松鼠TV"; color: win.ink; font.pixelSize: 20; font.weight: Font.DemiBold }
                    Item { Layout.fillWidth: true }
                    TextField {
                        id: search; visible: win.page === "home"; Layout.preferredWidth: Math.min(300, win.width * .24); placeholderText: "搜索影片、剧集…"; color: win.ink; placeholderTextColor: win.muted
                        selectByMouse: true; leftPadding: 16; implicitHeight: 40
                        background: Rectangle { color: win.panel; radius: 12; border.color: search.activeFocus ? win.accent : win.raised }
                        onAccepted: { win.keyword = text; win.category = ""; win.browse(1) }
                    }
                    PillButton { text: "打开链接"; ink: win.ink; fill: win.raised; onClicked: urlDialog.open() }
                    PillButton { text: "◐"; compact: true; ink: win.accent; fill: win.panel; onClicked: { win.theme = (win.theme + 1) % 4; Backend.setSetting("theme", win.theme) } }
                }
            }
            Item {
                id: content
                Layout.fillWidth: true; Layout.fillHeight: true
                ColumnLayout {
                    anchors { fill: parent; leftMargin: 32; rightMargin: 32; bottomMargin: 20 }  spacing: 20
                    visible: win.page === "home"
                    Rectangle {
                        Layout.fillWidth: true; Layout.preferredHeight: 168; radius: 22; color: win.panel; clip: true
                        Rectangle { width: 220; height: 220; radius: 110; x: parent.width - 260; y: -80; color: win.accent; opacity: .07 }
                        Column {
                            anchors { left: parent.left; leftMargin: 26; verticalCenter: parent.verticalCenter }  spacing: 10
                            Text { text: "SQUIRREL CINEMA"; color: win.accent; font.pixelSize: 10; font.letterSpacing: 3 }
                            Text { text: win.keyword ? "找一部，刚好想看的" : "把时间留给好故事"; color: win.ink; font.pixelSize: 29; font.weight: Font.DemiBold }
                            Text { text: "发现、收藏、离线。你的私人放映室。"; color: win.muted; font.pixelSize: 12 }
                        }
                        Image { source: "qrc:/design/app-icon/sstv.png"; width: 122; height: 122; anchors { right: parent.right; rightMargin: 30; verticalCenter: parent.verticalCenter }  sourceSize.width: 244; sourceSize.height: 244; opacity: .95 }
                    }
                    RowLayout {
                        Layout.fillWidth: true; spacing: 12
                        Text { text: win.keyword ? "搜索结果 · " + win.keyword : "片库发现"; color: win.ink; font.pixelSize: 18; font.weight: Font.DemiBold }
                        Item { Layout.fillWidth: true }
                        ComboBox { id: sourceCombo; model: Backend.sources; textRole: "name"; currentIndex: win.sourceIndex; Layout.preferredWidth: 178; onActivated: { win.sourceIndex = currentIndex; Backend.setSetting("source", currentIndex); win.category = ""; win.browse(1) } }
                        PillButton { text: "刷新"; compact: true; ink: win.ink; fill: win.raised; onClicked: win.browse(1) }
                    }
                    Flickable {
                        Layout.fillWidth: true; Layout.preferredHeight: 36; contentWidth: categoriesRow.width; contentHeight: height; clip: true; flickableDirection: Flickable.HorizontalFlick
                        Row { id: categoriesRow; spacing: 8
                            PillButton { text: "全部"; compact: true; primary: win.category === ""; highlight: win.accent; ink: win.ink; fill: win.panel; onClicked: { win.category = ""; win.keyword = ""; search.text = ""; win.browse(1) } }
                            Repeater { model: Backend.categories; delegate: PillButton { required property var modelData; text: modelData.name; compact: true; primary: win.category === modelData.id; highlight: win.accent; ink: win.ink; fill: win.panel; onClicked: { win.category = modelData.id; win.keyword = ""; search.text = ""; win.browse(1) } } }
                        }
                    }
                    GridView {
                        id: catalog; Layout.fillWidth: true; Layout.fillHeight: true; clip: true; model: Backend.videos; reuseItems: true; cacheBuffer: 300
                        property int columns: Math.max(3, Math.floor(width / 170))
                        cellWidth: width / columns; cellHeight: (cellWidth - 18) * 1.43 + 70
                        ScrollBar.vertical: ScrollBar {}
                        delegate: VideoCard { required property var modelData; video: modelData; width: catalog.cellWidth - 18; ink: win.ink; subtle: win.muted; surface: win.panel; accent: win.accent; reducedMotion: win.reduced; onSelected: win.details(modelData) }
                        BusyIndicator { anchors.centerIn: parent; running: Backend.busy; visible: running }
                        Text { anchors.centerIn: parent; width: parent.width * .8; horizontalAlignment: Text.AlignHCenter; wrapMode: Text.WordWrap; text: Backend.status || "选择片源，开始发现好故事"; color: win.muted; visible: !Backend.busy && Backend.videos.length === 0 }
                    }
                    RowLayout { Layout.alignment: Qt.AlignHCenter; spacing: 16
                        PillButton { text: "上一页"; compact: true; enabled: Backend.page > 1 && !Backend.busy; ink: win.ink; fill: win.panel; onClicked: win.browse(Backend.page - 1) }
                        Text { text: Backend.page + " / " + Backend.pages; color: win.muted; font.pixelSize: 12 }
                        PillButton { text: "下一页"; compact: true; enabled: Backend.page < Backend.pages && !Backend.busy; ink: win.ink; fill: win.panel; onClicked: win.browse(Backend.page + 1) }
                    }
                }
                // The video stays in one scene-graph item during theater/fullscreen changes.
                ColumnLayout {
                    anchors { fill: parent; leftMargin: win.full ? 0 : 24; rightMargin: win.full ? 0 : 24; bottomMargin: win.full ? 0 : 20 }  spacing: 14
                    visible: win.page === "player"
                    Rectangle {
                        id: stage; objectName: "videoStage"; Layout.fillWidth: true; Layout.fillHeight: true; color: "#050706"; clip: true
                        VideoPlayer { id: player; objectName: "videoPlayer"; anchors.fill: parent; onFinished: win.nextEpisode() }
                        Column { anchors.centerIn: parent; spacing: 18; visible: !player.loaded && !player.buffering
                            Image { source: "qrc:/design/app-icon/sstv.png"; width: 90; height: 90; anchors.horizontalCenter: parent.horizontalCenter; sourceSize.width: 180; sourceSize.height: 180 }
                            Text { text: Backend.current.name || "今天，想看点什么？"; color: "#d5ded5"; font.pixelSize: 22; anchors.horizontalCenter: parent.horizontalCenter }
                            Text { text: "打开本地文件，或从片库选择影片"; color: "#819387"; font.pixelSize: 12; anchors.horizontalCenter: parent.horizontalCenter }
                        }
                        MouseArea {
                            id: videoHit; anchors.fill: parent; acceptedButtons: Qt.LeftButton | Qt.RightButton; hoverEnabled: true
                            property bool doublePress: false
                            onPositionChanged: { win.controlsShown = true; hideTimer.restart() }
                            onPressed: mouse => { doublePress = false; if (mouse.button === Qt.RightButton) holdTimer.restart() }
                            onReleased: mouse => {
                                if (mouse.button === Qt.RightButton) {
                                    let held = player.holding; holdTimer.stop(); player.endHold()
                                    if (!held) playerMenu.popup()
                                }
                            }
                            onCanceled: { holdTimer.stop(); player.endHold() }
                            onDoubleClicked: mouse => { if (mouse.button === Qt.LeftButton) { doublePress = true; clickTimer.stop(); win.toggleFull() } }
                            onClicked: mouse => { if (mouse.button === Qt.LeftButton && !doublePress) clickTimer.restart() }
                            onWheel: wheel => { player.setVolume(player.volume + (wheel.angleDelta.y > 0 ? 5 : -5)); win.controlsShown = true; hideTimer.restart() }
                            Timer { id: clickTimer; interval: Qt.styleHints.mouseDoubleClickInterval; onTriggered: if (player.loaded) player.togglePause() }
                        }
                        Rectangle {
                            anchors { top: parent.top; horizontalCenter: parent.horizontalCenter; topMargin: 34 }  width: 190; height: 42; radius: 21; color: "#dcffb36b"; visible: player.holding
                            Text { anchors.centerIn: parent; text: win.holdSpeed + "× 倍速播放中"; color: "#20170e"; font.pixelSize: 14; font.bold: true }
                        }
                        BusyIndicator { anchors.centerIn: parent; running: player.buffering; visible: running }
                        Rectangle {
                            anchors.centerIn: parent; width: Math.min(parent.width - 60, 500); height: errorText.implicitHeight + 40; color: "#df28221e"; radius: 12; visible: player.error.length > 0
                            Text { id: errorText; anchors { fill: parent; margins: 20 }  text: "播放失败\n" + player.error + "\n可尝试切换线路或软解码"; color: "#ffcfb2"; wrapMode: Text.WordWrap }
                        }
                        Rectangle {
                            anchors { left: parent.left; right: parent.right; bottom: parent.bottom }  height: 150
                            opacity: win.controlsShown || player.paused || !player.loaded ? 1 : 0
                            visible: opacity > 0
                            Behavior on opacity { NumberAnimation { duration: win.reduced ? 0 : 160 } }
                            gradient: Gradient { GradientStop { position: 0; color: "transparent" } GradientStop { position: 1; color: "#ed080b09" } }
                            ColumnLayout {
                                anchors { fill: parent; leftMargin: 22; rightMargin: 22; bottomMargin: 14; topMargin: 40 }  spacing: 4
                                Text { text: (Backend.current.name || "松鼠TV") + "  " + (Backend.current.episodeName || ""); color: "#f0f0e7"; font.pixelSize: 13; elide: Text.ElideRight; Layout.fillWidth: true }
                                Slider { id: seekBar; Layout.fillWidth: true; from: 0; to: Math.max(1, player.duration); value: player.position; onMoved: player.seek(value); enabled: player.loaded }
                                RowLayout {
                                    Layout.fillWidth: true; spacing: 12
                                    PillButton { text: player.paused ? "▶" : "Ⅱ"; compact: true; ink: "#f0f0e7"; fill: "#25322b"; onClicked: player.togglePause() }
                                    PillButton { text: "下一集"; compact: true; ink: "#f0f0e7"; fill: "#25322b"; onClicked: win.nextEpisode() }
                                    Text { text: win.time(player.position) + " / " + win.time(player.duration); color: "#c0cbbf"; font.pixelSize: 11 }
                                    Item { Layout.fillWidth: true }
                                    ComboBox { model: ["0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×", "3×"]; currentIndex: 2; implicitWidth: 88; implicitHeight: 32; onActivated: player.setSpeed([.5,.75,1,1.25,1.5,2,3][currentIndex]) }
                                    Slider { from: 0; to: 100; value: player.volume; implicitWidth: 95; onMoved: player.setVolume(value) }
                                    PillButton { text: win.theater ? "退出剧场" : "剧场"; compact: true; ink: "#f0f0e7"; fill: "#25322b"; onClicked: win.theater = !win.theater }
                                    PillButton { text: win.full ? "退出全屏" : "全屏"; compact: true; ink: "#f0f0e7"; fill: "#25322b"; onClicked: win.toggleFull() }
                                }
                            }
                        }
                    }
                    RowLayout {
                        visible: !win.full; Layout.fillWidth: true
                        Text { text: player.decoder && player.decoder !== "no" ? "硬件解码 · " + player.decoder : "软件解码"; color: win.muted; font.pixelSize: 11 }
                        Text { text: "右键按住 " + win.holdSpeed + "× · 双击全屏 · 空格暂停"; color: win.muted; font.pixelSize: 11; Layout.leftMargin: 8 }
                        Item { Layout.fillWidth: true }
                        PillButton { text: "♡ 收藏"; compact: true; enabled: !!Backend.current.api; ink: win.ink; fill: win.panel; onClicked: Backend.favorite(Backend.current) }
                        PillButton { text: "◇ 标记此刻"; compact: true; enabled: player.loaded; ink: win.ink; fill: win.panel; onClicked: Backend.mark(player.position) }
                        PillButton { text: "选集 / 下载"; compact: true; enabled: !!Backend.current.api; ink: win.ink; fill: win.panel; onClicked: win.details(Backend.current) }
                    }
                }
                GridView {
                    id: library; anchors { fill: parent; margins: 32 }  visible: ["favorites","history","marks"].includes(win.page); clip: true; reuseItems: true
                    model: win.page === "favorites" ? Backend.favorites : win.page === "history" ? Backend.history : Backend.marks
                    cellWidth: width / Math.max(3, Math.floor(width/170)); cellHeight: (cellWidth - 18)*1.43 + 70
                    ScrollBar.vertical: ScrollBar {}
                    delegate: VideoCard { required property var modelData; video: modelData; width: library.cellWidth - 18; ink: win.ink; subtle: win.muted; surface: win.panel; accent: win.accent; reducedMotion: win.reduced; onSelected: { if (win.page === "favorites") win.details(modelData); else Backend.restore(modelData) } }
                    Text { anchors.centerIn: parent; visible: library.count === 0; text: win.page === "marks" ? "遇到喜欢的片刻，在播放器里记录下来" : win.page === "favorites" ? "把想看的故事，放进收藏" : "看过的故事，会留在这里"; color: win.muted; font.pixelSize: 15 }
                }
                ListView {
                    id: downloads; anchors { fill: parent; margins: 32 }  visible: win.page === "downloads"; model: Backend.downloads; spacing: 12; clip: true; ScrollBar.vertical: ScrollBar {}
                    delegate: Rectangle {
                        required property var modelData; required property int index
                        width: downloads.width; height: modelData.error ? 130 : 108; radius: 14; color: win.panel
                        ColumnLayout {
                            anchors { fill: parent; margins: 18 }  spacing: 8
                            RowLayout {
                                Layout.fillWidth: true
                                Text { text: modelData.name + "  " + modelData.episodeName; color: win.ink; font.pixelSize: 14; elide: Text.ElideRight; Layout.fillWidth: true }
                                Text { text: modelData.state; color: win.accent; font.pixelSize: 12 }
                                PillButton { text: modelData.state === "已完成" ? "播放" : modelData.state === "下载中" ? "暂停" : "继续"; compact: true; ink: win.ink; fill: win.raised; onClicked: { if (modelData.state === "已完成") Backend.playDownload(index); else if (modelData.state === "下载中") Backend.pauseDownload(index); else Backend.resumeDownload(index) } }
                                PillButton { text: "删除"; compact: true; ink: win.muted; fill: win.raised; onClicked: { deleteDialog.taskIndex = index; deleteDialog.open() } }
                            }
                            ProgressBar { Layout.fillWidth: true; value: modelData.progress || 0 }
                            Text { text: modelData.error || Math.round((modelData.progress || 0)*100) + "% · " + Math.round((modelData.bytes || 0)/1048576) + " MB"; color: win.muted; font.pixelSize: 11; elide: Text.ElideRight; Layout.fillWidth: true }
                        }
                    }
                    Text { anchors.centerIn: parent; text: "暂无下载\n在影片详情中选择剧集，离线收藏你的故事"; horizontalAlignment: Text.AlignHCenter; lineHeight: 1.8; color: win.muted; visible: downloads.count === 0 }
                }
            }
        }
    }
    Rectangle {
        anchors { horizontalCenter: parent.horizontalCenter; bottom: parent.bottom; bottomMargin: 20 }  width: Math.min(statusText.implicitWidth + 40, win.width - 80); height: statusText.implicitHeight + 24
        color: win.raised; radius: 12; visible: toastTimer.running && win.page !== "player" && Backend.status.length > 0
        Text { id: statusText; anchors.centerIn: parent; text: Backend.status; color: win.ink; font.pixelSize: 12 }
    }
    Menu {
        id: playerMenu
        MenuItem { text: player.paused ? "播放" : "暂停"; onTriggered: player.togglePause() }
        MenuItem { text: win.full ? "退出全屏" : "全屏"; onTriggered: win.toggleFull() }
        MenuItem { text: win.theater ? "退出剧场模式" : "剧场模式"; onTriggered: win.theater = !win.theater }
        MenuSeparator {}
        MenuItem { text: "硬件解码"; checkable: true; checked: win.hardware; onTriggered: { win.hardware = checked; player.setHardware(checked); Backend.setSetting("hardware", checked) } }
        Menu { title: "画面比例"; MenuItem { text: "原始比例"; onTriggered: player.setAspect("-1") }  MenuItem { text: "16:9"; onTriggered: player.setAspect("16:9") }  MenuItem { text: "4:3"; onTriggered: player.setAspect("4:3") } }
        MenuSeparator {}
        MenuItem { text: "打开本地文件…"; onTriggered: fileDialog.open() }
        MenuItem { text: "打开网络链接…"; onTriggered: urlDialog.open() }
    }
    Dialog {
        id: detailDialog; anchors.centerIn: parent; width: Math.min(850, win.width - 80); height: Math.min(640, win.height - 80); modal: true; title: Backend.detail.name || "影片详情"; standardButtons: Dialog.Close
        background: Rectangle { color: win.panel; radius: 18; border.color: win.raised }
        contentItem: ScrollView {
            clip: true
            ColumnLayout {
                width: detailDialog.availableWidth; spacing: 16
                RowLayout {
                    Layout.fillWidth: true; spacing: 20
                    Image { source: Backend.detail.pic || ""; Layout.preferredWidth: 110; Layout.preferredHeight: 154; fillMode: Image.PreserveAspectCrop; sourceSize.width: 220; sourceSize.height: 308; asynchronous: true }
                    ColumnLayout {
                        Layout.fillWidth: true; spacing: 12
                        Text { text: Backend.detail.name || ""; color: win.ink; font.pixelSize: 23; font.bold: true; wrapMode: Text.WordWrap; Layout.fillWidth: true }
                        Text { text: [Backend.detail.year, Backend.detail.area, Backend.detail.type, Backend.detail.source].filter(Boolean).join(" · "); color: win.muted; font.pixelSize: 12; wrapMode: Text.WordWrap; Layout.fillWidth: true }
                        RowLayout {
                            PillButton { text: "▶  播放所选"; primary: true; highlight: win.accent; enabled: (Backend.detail.lines || []).length > 0; onClicked: { win.savePosition(); Backend.playEpisode(win.selectedLine, win.selectedEpisode) } }
                            PillButton { text: "♡ 收藏 / 取消"; ink: win.ink; fill: win.raised; onClicked: Backend.favorite(Backend.detail) }
                            PillButton { text: "↓ 下载所选"; ink: win.ink; fill: win.raised; enabled: (Backend.detail.lines || []).length > 0; onClicked: Backend.downloadEpisode(win.selectedLine, win.selectedEpisode) }
                        }
                    }
                }
                Text { text: Backend.detail.content || ""; Layout.fillWidth: true; wrapMode: Text.WordWrap; color: win.muted; font.pixelSize: 12; lineHeight: 1.7 }
                ComboBox { model: Backend.detail.lines || []; textRole: "name"; currentIndex: win.selectedLine; onActivated: { win.selectedLine = currentIndex; win.selectedEpisode = 0 } }
                Flow {
                    Layout.fillWidth: true; spacing: 8
                    Repeater {
                        model: (Backend.detail.lines || [])[win.selectedLine]?.episodes || []
                        delegate: PillButton { required property var modelData; required property int index; text: modelData.name; primary: index === win.selectedEpisode; highlight: win.accent; ink: win.ink; fill: win.raised; onClicked: win.selectedEpisode = index; onDoubleClicked: Backend.playEpisode(win.selectedLine, index) }
                    }
                }
                Text { visible: (Backend.detail.lines || []).length === 0; text: "正在获取剧集信息，或该片源暂无播放线路"; color: win.muted; font.pixelSize: 12 }
            }
        }
    }
    Drawer {
        id: settingsDrawer; edge: Qt.RightEdge; width: 430; height: win.height
        background: Rectangle { color: win.panel }
        ScrollView {
            anchors { fill: parent; margins: 26 }  clip: true
            ColumnLayout {
                width: settingsDrawer.width - 52; spacing: 16
                Text { text: "偏好与片源"; color: win.ink; font.pixelSize: 24; font.bold: true }
                Text { text: "外观"; color: win.accent; font.pixelSize: 13; Layout.topMargin: 12 }
                ComboBox { Layout.fillWidth: true; model: ["林间影院", "极光漫游", "晴日手帖", "像素终端"]; currentIndex: win.theme; onActivated: { win.theme = currentIndex; Backend.setSetting("theme", currentIndex) } }
                Switch { text: "减少动画"; checked: win.reduced; onToggled: { win.reduced = checked; Backend.setSetting("reducedMotion", checked) } }
                Text { text: "播放"; color: win.accent; font.pixelSize: 13 }
                RowLayout { Text { text: "右键按住临时倍速"; color: win.ink; font.pixelSize: 13 }  Item { Layout.fillWidth: true }  ComboBox { model: ["2×", "2.5×", "3×", "4×"]; currentIndex: [2,2.5,3,4].indexOf(win.holdSpeed); onActivated: { win.holdSpeed = [2,2.5,3,4][currentIndex]; Backend.setSetting("holdSpeed", win.holdSpeed) } } }
                Switch { text: "优先硬件解码"; checked: win.hardware; onToggled: { win.hardware = checked; player.setHardware(checked); Backend.setSetting("hardware", checked) } }
                Text { text: "片源管理"; color: win.accent; font.pixelSize: 13 }
                TextField { id: sourceName; Layout.fillWidth: true; placeholderText: "片源名称" }
                TextField { id: sourceApi; Layout.fillWidth: true; placeholderText: "MacCMS JSON 接口地址"; selectByMouse: true }
                PillButton { text: "添加片源"; primary: true; highlight: win.accent; onClicked: { Backend.addSource(sourceName.text, sourceApi.text); sourceName.clear(); sourceApi.clear() } }
                Repeater {
                    model: Backend.sources
                    delegate: RowLayout {
                        required property var modelData; required property int index
                        Layout.fillWidth: true
                        Text { text: modelData.name; color: win.ink; font.pixelSize: 12; Layout.fillWidth: true; elide: Text.ElideRight }
                        PillButton { text: "移除"; compact: true; ink: win.muted; fill: win.raised; onClicked: Backend.removeSource(index) }
                    }
                }
                RowLayout {
                    PillButton { text: "导入片源"; ink: win.ink; fill: win.raised; onClicked: sourceFileDialog.open() }
                    PillButton { text: "导出资料"; ink: win.ink; fill: win.raised; onClicked: exportDialog.open() }
                }
                PillButton { text: "打开数据与下载目录"; Layout.fillWidth: true; ink: win.ink; fill: win.raised; onClicked: Backend.revealData() }
                Text { text: "松鼠TV Windows · 0.1 预览版\n内容来自用户配置的片源接口"; color: win.muted; font.pixelSize: 11; lineHeight: 1.8; Layout.bottomMargin: 20 }
            }
        }
    }
    Dialog {
        id: urlDialog; anchors.centerIn: parent; width: 560; modal: true; title: "打开网络视频"; standardButtons: Dialog.Open | Dialog.Cancel
        TextField { id: urlInput; width: parent.width; placeholderText: "粘贴 MP4、M3U8 或其他视频直链"; selectByMouse: true; onAccepted: { Backend.openUrl(text); urlDialog.close() } }
        onAccepted: Backend.openUrl(urlInput.text)
    }
    Dialog { id: deleteDialog; property int taskIndex: -1; anchors.centerIn: parent; modal: true; title: "删除下载及本地文件？"; standardButtons: Dialog.Yes | Dialog.No; onAccepted: Backend.removeDownload(taskIndex) }
    FileDialog { id: fileDialog; title: "打开视频"; nameFilters: ["视频文件 (*.mp4 *.mkv *.webm *.avi *.mov *.ts *.m3u8 *.flv)", "所有文件 (*)"]; onAccepted: Backend.openUrl(selectedFile.toString()) }
    FileDialog { id: sourceFileDialog; title: "导入片源 JSON"; nameFilters: ["JSON (*.json)"]; onAccepted: Backend.importSources(selectedFile.toString()) }
    FileDialog { id: exportDialog; title: "导出资料"; fileMode: FileDialog.SaveFile; defaultSuffix: "json"; nameFilters: ["JSON (*.json)"]; onAccepted: Backend.exportLibrary(selectedFile.toString()) }
}
