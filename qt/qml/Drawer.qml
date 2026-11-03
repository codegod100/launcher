import QtQuick

// Every app, with a search. It slides down over the home screen; swiping up past the
// end of the list (or Close, or back) puts it away. In pick mode a tap adds an app to
// the home screen or takes it off, and the keyboard stays down.
Item {
    id: root
    property bool open: false
    property bool picking: false
    property var apps: []          // [{label, component, system}]
    property var homeSet: ({})     // component -> true: on the home screen
    property bool loading: true
    property bool warm: true       // false: hold off making icons until it opens
    property var iconGen: ({})     // package -> n, see Main.gen
    property real insetTop: 0
    property real insetBottom: 0
    property alias query: search.text
    readonly property real edge: y + height   // the drawer's bottom edge, for covering widgets
    readonly property var shown: {
        const q = search.shown.toLowerCase();
        const out = [];
        for (let i = 0; i < apps.length; i++)
            if (!q || apps[i].label.toLowerCase().indexOf(q) >= 0) out.push(i);
        return out;
    }
    signal openApp(int row)
    signal toggleApp(int row)
    signal appMenu(int row)
    signal closeRequested()

    width: parent.width
    height: parent.height
    y: open ? 0 : -height
    visible: y > -height
    Behavior on y { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }

    function openFirst() {
        if (shown.length > 0 && search.shown.length > 0) openApp(shown[0]);
    }

    onOpenChanged: {
        if (open && !picking) search.focusNow();
        if (!open) {
            search.input.focus = false;
            Qt.inputMethod.hide();
            search.text = "";
            grid.positionViewAtBeginning();
        }
    }

    Rectangle {
        anchors.fill: parent
        color: Qt.rgba(10 / 255, 10 / 255, 14 / 255, .9)
    }
    MouseArea { anchors.fill: parent } // nothing under the drawer takes its touches

    Column {
        id: head
        x: Math.max(16, (root.width - 560) / 2 + 16)
        width: Math.min(root.width, 560) - 32
        y: root.insetTop + 16
        spacing: 16
        Row {
            width: parent.width
            spacing: 8
            SearchField {
                id: search
                width: parent.width - close.width - 8
                placeholder: "Search apps"
                onAccepted: root.openFirst()
                onEscaped: text = ""
            }
            PillButton {
                id: close
                anchors.verticalCenter: search.verticalCenter
                text: root.picking ? "Done" : "Close"
                onClicked: root.closeRequested()
            }
        }
        Text {
            visible: root.picking
            width: parent.width
            text: "Tap apps to add them to the home screen or take them off."
            color: "white"
            opacity: .8
            font.pixelSize: 14
            wrapMode: Text.Wrap
            horizontalAlignment: Text.AlignHCenter
        }
    }

    GridView {
        id: grid
        objectName: "drawerGrid"
        x: head.x
        width: head.width
        anchors.top: head.bottom
        anchors.topMargin: 16
        anchors.bottom: parent.bottom
        readonly property int cols: root.width >= 480 ? 5 : 4
        cellWidth: width / cols
        cellHeight: 104
        clip: true
        bottomMargin: root.insetBottom + 24
        boundsBehavior: Flickable.DragOverBounds
        model: root.warm || root.visible ? root.shown : []
        // Swiping up once the list is at its end puts the drawer away.
        onDragEnded: if (verticalOvershoot > 48) root.closeRequested()
        delegate: Item {
            id: cell
            required property int index
            required property var modelData
            readonly property var app: root.apps[modelData] || ({})
            width: grid.cellWidth
            height: grid.cellHeight
            Tile {
                width: parent.width
                height: 84
                key: "a:" + (cell.app.component || "")
                label: cell.app.label || ""
                generation: root.iconGen[(cell.app.component || "").split("/")[0]] || 0
                iconSize: 52
                fontSize: 12
                spacing: 6
                checkable: root.picking
                checked: root.picking && root.homeSet[cell.app.component] === true
                pressed: area.pressed
            }
            MouseArea {
                id: area
                width: parent.width
                height: 84
                pressAndHoldInterval: 450
                onClicked: root.picking ? root.toggleApp(cell.modelData) : root.openApp(cell.modelData)
                onPressAndHold: if (!root.picking) root.appMenu(cell.modelData)
            }
        }
    }

    Text {
        anchors.centerIn: grid
        visible: root.loading || root.shown.length === 0
        text: root.loading ? "Loading apps…" : "No apps match."
        color: "white"
        opacity: .85
        font.pixelSize: 15
    }
}
