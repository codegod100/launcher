import QtQuick

// A rounded search box. Enter is "accepted"; the text is "text", and "shown" is what
// the box shows, including a word the keyboard is still composing (Android keyboards
// keep it out of "text" until it is committed).
Rectangle {
    id: root
    property alias text: input.text
    property alias placeholder: hint.text
    property alias input: input
    readonly property string shown: input.displayText
    property real fontSize: 16
    signal accepted()
    signal escaped()
    implicitHeight: 44
    radius: height / 2
    color: Qt.rgba(1, 1, 1, .1)
    border.color: Qt.rgba(1, 1, 1, .15)
    border.width: 1

    function focusNow() { input.forceActiveFocus(); Qt.inputMethod.show() }
    // Empty the box, including a word still being composed, and keep typing in it.
    // Android commits a composing word asynchronously, so it can land after the clear:
    // clear again once it has.
    function clearText() {
        Qt.inputMethod.commit();
        input.clear();
        input.forceActiveFocus();
        clearAgain.restart();
    }
    Timer { id: clearAgain; interval: 120; onTriggered: input.clear() }

    TextInput {
        id: input
        anchors.left: parent.left
        anchors.right: clear.left
        anchors.leftMargin: 20
        anchors.rightMargin: 0
        anchors.verticalCenter: parent.verticalCenter
        color: "white"
        selectionColor: Qt.rgba(1, 1, 1, .35)
        font.pixelSize: root.fontSize
        clip: true
        // No suggestions: Android keyboards then type straight into the box rather than
        // composing words, so the list filters on every key.
        inputMethodHints: Qt.ImhNoPredictiveText | Qt.ImhSensitiveData | Qt.ImhNoAutoUppercase
        onAccepted: root.accepted()
        Keys.onEscapePressed: root.escaped()
        Text {
            id: hint
            anchors.fill: parent
            verticalAlignment: Text.AlignVCenter
            color: Qt.rgba(1, 1, 1, .6)
            font.pixelSize: root.fontSize
            visible: input.displayText.length === 0
        }
    }
    // The clear button: a finger-sized target at the right end of the box.
    Item {
        id: clear
        objectName: "searchClear"
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: visible ? 44 : 0
        visible: input.displayText.length > 0
        z: 1
        Text {
            anchors.centerIn: parent
            text: "✕"
            color: Qt.rgba(1, 1, 1, .7)
            font.pixelSize: 14
        }
        MouseArea {
            anchors.fill: parent
            preventStealing: true
            onPressed: (m) => m.accepted = true
            onClicked: { root.clearText(); root.escaped() }
        }
    }
}
