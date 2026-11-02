import QtQuick
import Launcher.Host
import "Layout.js" as L

// An Android home screen. The home starts blank: you add apps and widgets to it with a
// long press. Swiping down opens the drawer with every app and a search.
//
// The home grid packs apps and widgets in order with no holes (Layout.js), and runs on
// to the bottom of the screen, so the empty cells there take apps too. A long press on
// a home app or widget picks it up: drag it over another to move it there in the order,
// or into an empty cell to leave it in that cell; or let go without moving to get its
// menu. Letting go of a widget that way shows resize handles on its right and bottom
// edges instead: dragging them snaps the widget to whole grid columns and rows, within
// the sizes it allows, and dragging the widget itself moves it, staying in that mode.
//
// Host (backend.cpp, over HomeActivity.java) lists apps and widget providers, launches
// things, and draws real widget views over the slots this file reports (placeWidgets).
Window {
    id: root
    objectName: "home"
    visible: true
    width: 412
    height: 892
    color: "transparent"
    title: "ultra Home"

    // ---- model
    property var apps: []            // [{label, component, system}], by label
    property var appRow: ({})        // component -> row in apps
    property bool loading: true
    property int generation: 0       // bumped when the app list changes
    property var home: []            // slots, see Layout.js
    property bool drawerOpen: false
    property bool picking: false     // the drawer adds apps to home / takes them off
    property string sheet: ""        // "", "menu", "item", "app", "widgets"
    property int item: -1            // home slot the item sheet is about
    property int appSheetRow: -1     // app row the app sheet is about
    property string gone: ""         // being uninstalled: its home slot goes once it leaves the list
    property int lifted: -1          // home slot picked up by a long press
    property int resizing: -1        // widget slot showing resize handles
    property bool regrab: false      // the lifted slot was grabbed from resizing: drop it back there
    property var providers: []       // widget providers, for the picker
    property bool wloading: false
    property var chosen: null        // provider being added
    property real scrollY: 0
    readonly property int homeCap: 200

    // ---- geometry (logical px)
    readonly property real insetTop: Math.max(0, Host.insetTop - (Host.android ? root.y : 0))
    readonly property real insetBottom: Host.insetBottom
    readonly property real contentW: Math.min(width, 560)
    readonly property real contentX: (width - contentW) / 2
    readonly property real gridX: contentX + 16
    readonly property real gridW: contentW - 32
    readonly property int cols: width >= 480 ? 6 : 5
    readonly property real colW: (gridW - (cols - 1) * L.CGAP) / cols
    readonly property real panelY: insetTop + 16
    readonly property real panelH: 54
    readonly property real gridTop: panelY + panelH + 16     // in home content coordinates
    readonly property real gridBottom: height - (insetBottom + 24)
    readonly property int nrows: home.length > 0
        ? Math.max(1, Math.floor((gridBottom - gridTop + L.RGAP) / (L.CELL + L.RGAP))) : 0
    readonly property var hidden: home.map(s => s.kind === "app" && appRow[s.str] === undefined)
    readonly property var packed: L.pack(home, cols, nrows, hidden)
    readonly property real gridH: Math.max(0, packed.rows * (L.CELL + L.RGAP) - L.RGAP)
    readonly property real contentH: gridTop + gridH + insetBottom + 24
    readonly property real maxScroll: Math.max(0, contentH - height)
    onMaxScrollChanged: if (scrollY > maxScroll) scrollY = maxScroll

    function boxOf(i) { return packed.cells[i] ? L.box(packed.cells[i], colW) : null }

    // ---- updates
    function commit() {
        home = home.slice();
        Host.saveHome(JSON.stringify(home.map(s => s.kind === "widget"
            ? {kind: "widget", str: s.str, wid: s.wid, w: s.w, rows: s.rows, x: s.x, y: s.y}
            : {kind: "app", str: s.str, x: s.x, y: s.y})));
    }

    function loadHome() {
        let saved = [];
        try { saved = JSON.parse(Host.loadHome() || "[]"); } catch (e) { saved = []; }
        const out = [];
        for (const s of saved) {
            if (!s || typeof s.str !== "string" || out.length >= homeCap) continue;
            out.push(s.kind === "widget"
                ? {kind: "widget", str: s.str, wid: s.wid | 0, w: s.w | 0, rows: Math.max(1, s.rows | 0),
                   x: s.x ?? -1, y: s.y ?? -1}
                : {kind: "app", str: s.str, x: s.x ?? -1, y: s.y ?? -1});
        }
        home = out;
    }

    // Bring over the WebView build's home screen (once, on Android). An empty Qt home
    // takes it as it was; otherwise whatever isn't here yet goes after it, unpinned.
    function importLegacy(json) {
        let kv;
        try { kv = JSON.parse(json); } catch (e) { return; }
        const old = L.legacy(kv || {});
        if (old.length === 0) return;
        const has = {};
        for (const s of home) has[s.kind === "widget" ? "w" + s.wid : "a" + s.str] = true;
        const fresh = home.length === 0;
        for (const s of old) {
            if (home.length >= homeCap) break;
            if (has[s.kind === "widget" ? "w" + s.wid : "a" + s.str]) continue;
            if (!fresh) { s.x = -1; s.y = -1; }
            home.push(s);
        }
        commit();
        say("Brought over your home screen");
    }

    function loadApps() {
        const list = Host.apps();
        const rows = {};
        for (let i = 0; i < list.length; i++) rows[list[i].component] = i;
        apps = list;
        appRow = rows;
        loading = false;
        dropGone();
    }

    function reload() {
        generation++;
        loadApps();
        if (sheet === "app") { sheet = ""; appSheetRow = -1; }
    }

    // Once an app uninstalled from the drawer is out of the list, take it off home.
    function dropGone() {
        if (!gone || appRow[gone] !== undefined) return;
        for (let i = 0; i < home.length; i++) {
            if (home[i].kind === "app" && home[i].str === gone) { removeSlot(i); break; }
        }
        gone = "";
    }

    property string notice: ""
    function say(msg) { notice = msg; noticeTimer.restart(); }
    Timer { id: noticeTimer; interval: 1800; onTriggered: root.notice = "" }

    function homeOf(component) {
        for (let i = 0; i < home.length; i++)
            if (home[i].kind === "app" && home[i].str === component) return i;
        return -1;
    }

    readonly property var homeSet: {
        const m = {};
        for (const s of home) if (s.kind === "app") m[s.str] = true;
        return m;
    }

    function addApp(row) {
        const c = apps[row].component;
        if (homeOf(c) >= 0) say("Already on the home screen");
        else if (home.length >= homeCap) say("The home screen is full");
        else { home.push({kind: "app", str: c, x: -1, y: -1}); commit(); say("Added to home"); }
    }

    function toggleApp(row) {
        const c = apps[row].component, i = homeOf(c);
        if (i >= 0) removeSlot(i);
        else if (home.length < homeCap) { home.push({kind: "app", str: c, x: -1, y: -1}); commit(); }
    }

    function removeSlot(i) {
        if (i < 0 || i >= home.length) return;
        if (home[i].kind === "widget") Host.removeWidget(home[i].wid);
        home.splice(i, 1);
        commit();
    }

    function swapSlots(a, b) {
        if (a < 0 || b < 0 || a >= home.length || b >= home.length) return;
        const t = home[a]; home[a] = home[b]; home[b] = t;
        commit();
    }

    function slotOfWidget(wid) {
        for (let i = 0; i < home.length; i++)
            if (home[i].kind === "widget" && home[i].wid === wid) return i;
        return -1;
    }

    function closeAll() {
        drawerOpen = false; picking = false; sheet = ""; item = -1; appSheetRow = -1;
        lifted = -1; resizing = -1; regrab = false;
    }

    function openDrawer() { if (sheet === "") drawerOpen = true; }
    function closeDrawer() { if (drawerOpen && sheet === "") { drawerOpen = false; picking = false; } }
    function closeSheet() { sheet = ""; item = -1; appSheetRow = -1; }

    function launchApp(row) { Host.launch(apps[row].component); closeAll(); }

    function back() {
        if (lifted >= 0) { lifted = -1; regrab = false; }
        else if (resizing >= 0) resizing = -1;
        else if (sheet !== "") closeSheet();
        else if (drawerOpen) { drawerOpen = false; picking = false; }
    }

    function goHome() { closeAll(); scrollY = 0; }

    // ---- picking up and moving home slots
    function lift(i) {
        if (drawerOpen || sheet !== "" || i === lifted) return;
        Host.haptic();
        resizing = -1; regrab = false; lifted = i;
    }
    function grab(i) {
        if (i < 0 || i !== resizing || drawerOpen || sheet !== "") return;
        resizing = -1; regrab = true; lifted = i;
    }
    property var carry: null   // {dx, dy, cell}: where the lifted slot was held, the cell sent last

    function toGrid(x, y) { return {x: x - gridX, y: y + scrollY - gridTop} }
    function inside(b, x, y) { return b && x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h }
    // The slot under grid point x, y (not the lifted one if skipLifted).
    function slotAtGrid(x, y, skipLifted) {
        for (let i = 0; i < home.length; i++) {
            if (skipLifted && i === lifted) continue;
            if (inside(boxOf(i), x, y)) return i;
        }
        return -1;
    }

    // The finger is at x, y (window px; x0, y0 where it went down). Over another app the
    // lifted app takes its place in the order (the grid stays packed); over an empty cell
    // the slot is left in that cell, top left by where it was held. A widget always goes
    // to the cell under it, so it can sit on any row, and apps flow around it.
    function dragTo(x, y, x0, y0) {
        if (lifted < 0) return;
        const p = toGrid(x, y), b = boxOf(lifted);
        if (!b) return;
        if (!carry) {
            const p0 = toGrid(x0, y0);
            carry = {dx: p0.x - b.x, dy: p0.y - b.y, cell: -1};
        }
        const widget = home[lifted].kind === "widget";
        if (!widget && inside(b, p.x, p.y)) return;
        const fc = L.cellAt(p.x - colW / 2, p.y - L.CELL / 2, colW);
        let to = slotAtGrid(p.x, p.y, true);
        if (to < 0) to = slotAtGrid(fc[0] * (colW + L.CGAP) + colW / 2, fc[1] * (L.CELL + L.RGAP) + L.CELL / 2, true);
        if (to >= 0 && !widget) {
            carry.cell = -1;
            home[lifted].x = -1; home[lifted].y = -1; // back into the flow, at that place
            L.move(home, lifted, to);
            lifted = to;
            commit();
            return;
        }
        if (p.y > packed.rows * (L.CELL + L.RGAP)) return; // below the grid
        const c = L.cellAt(p.x - carry.dx, p.y - carry.dy, colW);
        const cell = c[1] * 64 + Math.min(c[0], 63);
        if (cell !== carry.cell) {
            carry.cell = cell;
            L.pin(home, lifted, c[0], c[1], cols, nrows);
            commit();
        }
    }

    // Letting go: whatever a pinned slot now covers moves on to free cells. Let go
    // without moving, an app gets its menu and a widget its resize handles.
    function drop(moved) {
        carry = null;
        if (lifted < 0) return;
        if (home[lifted].x >= 0) { L.clearCells(home, lifted, cols); commit(); }
        if (!moved || regrab) {
            if (home[lifted].kind === "widget") resizing = lifted;
            else { item = lifted; sheet = "item"; }
        }
        lifted = -1;
        regrab = false;
    }

    // ---- widgets
    function pickWidget() {
        sheet = "widgets";
        widgetSearch.text = "";
        wloading = true;
        Qt.callLater(() => { providers = Host.widgetProviders(); wloading = false; });
    }
    readonly property var shownProviders: {
        const q = widgetSearch.shown.toLowerCase(), out = [];
        for (let i = 0; i < providers.length; i++) {
            const p = providers[i];
            if (!q || p.label.toLowerCase().indexOf(q) >= 0 || p.app.toLowerCase().indexOf(q) >= 0) out.push(i);
        }
        return out;
    }
    function chooseWidget(i) {
        if (i < 0) return;
        if (home.length < homeCap) { chosen = providers[i]; Host.addWidget(chosen.provider); }
        else say("The home screen is full");
        closeSheet();
    }
    function widgetAdded(id) {
        if (!chosen || home.length >= homeCap) return;
        const lim = Host.widgetLimits(id);
        home.push({kind: "widget", str: chosen.label, wid: id, w: L.widgetCols(lim.w || 0, colW, cols),
                   rows: L.widgetRows(chosen.h), x: -1, y: -1});
        chosen = null;
        commit();
    }

    // Resize the widget being edited to cols columns and rows rows.
    function resizeTo(c, r) {
        if (resizing < 0) return;
        const s = home[resizing];
        s.w = c >= cols ? 0 : c;
        s.rows = r;
        if (s.x >= 0) { L.pin(home, resizing, s.x, s.y, cols, nrows); L.clearCells(home, resizing, cols); }
        commit();
    }

    // ---- telling Host where the live widget slots are
    // A slot is live (its native view is shown over it) unless the drawer is open. While
    // one is resized the others step aside, so nothing covers its handles. Under the
    // open drawer the widgets stay put and the drawer's edge covers them; under a sheet
    // they stay put too, dimmed, and hidden below the sheet's edge (sheetCover).
    readonly property var liveSlots: {
        const out = [];
        if (drawerOpen) return out;
        for (let i = 0; i < home.length; i++) {
            const s = home[i], b = boxOf(i);
            if (s.kind !== "widget" || !b || (resizing >= 0 && i !== resizing)) continue;
            const g = homeContent.mapToGlobal(gridX + b.x, gridTop + b.y - scrollY);
            out.push({id: s.wid, x: g.x, y: g.y, w: b.w, h: b.h, editing: i === resizing});
        }
        return out;
    }
    property string placed: ""
    function place() {
        const j = JSON.stringify(liveSlots);
        if (j === placed || drawerOpen) return;
        placed = j;
        Host.placeWidgets(liveSlots);
    }
    onLiveSlotsChanged: Qt.callLater(place)

    // Keep the system's back swipe off the resize handles.
    readonly property var handleRects: {
        if (resizing < 0 || !handles.visible || handles.width + handles.x + handles.y + handles.height < 0) return [];
        return [rHandle, bHandle, rbHandle].map(h => {
            const g = h.mapToGlobal(0, 0);
            return {x: g.x, y: g.y, w: h.width, h: h.height};
        });
    }
    onHandleRectsChanged: Host.excludeGestures(handleRects)

    // Native widgets sit above Qt's surface, so the drawer can't cover them by itself.
    // While it slides the backend hides them above its bottom edge (a little ahead, by
    // its speed, since the native side trails a frame), and once it is gone shows them
    // again after a frame without it.
    property real coverSent: 0
    property real coverLast: 0
    property bool uncoverPending: false
    function cover() {
        let y = 0;
        if (drawer.visible) {
            const b = drawer.edge;
            y = b >= height - 1 ? -1 : Math.max(0, Math.ceil(b + 2 * Math.max(0, b - coverLast)));
            coverLast = b;
            if (y >= height) y = height - 1;
        } else coverLast = 0;
        if (y === 0) { if (coverSent !== 0) uncoverPending = true; return; }
        uncoverPending = false;
        const gy = y < 0 ? -1 : homeContent.mapToGlobal(0, y).y;
        if (gy !== coverSent) { coverSent = gy; Host.coverWidgets(gy); }
    }
    // Native widgets under a sheet: hidden below its top edge, the rest dimmed.
    readonly property var sheets: [menuSheet, itemSheet, appSheet, widgetSheet]
    readonly property real sheetEdge: {
        let y = 0;
        for (const s of sheets) if (s.visible) y = y > 0 ? Math.min(y, s.edge) : Math.max(1, s.edge);
        return y;
    }
    readonly property bool sheetDim: sheets.some(s => s.open)
    property string sheetSent: "0,false"
    function sheetCover() {
        const y = sheetEdge > 0 ? homeContent.mapToGlobal(0, sheetEdge).y : 0;
        const k = Math.round(y) + "," + sheetDim;
        if (k === sheetSent) return;
        sheetSent = k;
        Host.sheetWidgets(y, sheetDim);
    }
    onSheetEdgeChanged: sheetCover()
    onSheetDimChanged: sheetCover()

    onFrameSwapped: if (uncoverPending) { uncoverPending = false; coverSent = 0; Host.coverWidgets(0); }

    // ---- Host
    Connections {
        target: Host
        function onAppsChanged() { root.reload() }
        function onHomePressed() { root.goHome() }
        function onBackPressed() { root.back() }
        function onWidgetAdded(id) { root.widgetAdded(id) }
        function onWidgetMenu(id) {
            const i = root.slotOfWidget(id);
            if (i >= 0 && !root.drawerOpen && root.sheet === "") { root.resizing = -1; root.regrab = false; root.lifted = i; }
        }
        function onWidgetGrab(id) { root.grab(root.slotOfWidget(id)) }
        function onWidgetDrag(x, y) {
            const p = homeContent.mapFromGlobal(x, y);
            touch.dragged = true;
            root.dragTo(p.x, p.y, p.x, p.y);
        }
        function onWidgetDrop(moved) { root.drop(moved) }
        function onToast(text) { root.say(text) }
        function onLegacyHome(json) { root.importLegacy(json) }
    }

    Component.onCompleted: {
        loadHome();
        Host.ready();
        Qt.callLater(loadApps);
    }

    // ---- view
    Item {
        id: homeContent
        anchors.fill: parent

        // The desktop stand-in for a wallpaper.
        Rectangle {
            anchors.fill: parent
            visible: !Host.android
            gradient: Gradient {
                orientation: Gradient.Vertical
                GradientStop { position: 0; color: "#1e293b" }
                GradientStop { position: .55; color: "#4c1d95" }
                GradientStop { position: 1; color: "#0f172a" }
            }
        }

        Item {
            id: scroller
            // The home screen fades out as the drawer slides over it, so only the
            // wallpaper is left behind the app list.
            opacity: drawer.visible ? Math.max(0, 1 - drawer.edge / Math.max(1, root.height)) : 1
            width: parent.width
            height: root.contentH
            y: -root.scrollY

            // The panel at the top of home: shortcuts to system surfaces.
            Rectangle {
                id: panel
                width: Math.min(352, root.contentW - 32)
                height: root.panelH
                x: (root.width - width) / 2
                y: root.panelY
                radius: 20
                color: Qt.rgba(1, 1, 1, .12)
                border.color: Qt.rgba(1, 1, 1, .16)
                border.width: 1
                Rectangle {
                    id: windowsBtn
                    property bool pressed: false
                    anchors.fill: parent
                    anchors.margins: 7
                    radius: 14
                    color: pressed ? Qt.rgba(1, 1, 1, .22) : Qt.rgba(1, 1, 1, .1)
                    Row {
                        anchors.centerIn: parent
                        spacing: 10
                        Item { // the Windows glyph: two overlapping windows
                            width: 18; height: 18
                            anchors.verticalCenter: parent.verticalCenter
                            Rectangle { x: 0; y: 0; width: 13; height: 13; radius: 4; color: "transparent"
                                        border.color: "white"; border.width: 2; opacity: .6 }
                            Rectangle { x: 5; y: 5; width: 13; height: 13; radius: 4; color: Qt.rgba(1, 1, 1, .15)
                                        border.color: "white"; border.width: 2 }
                        }
                        Text {
                            text: "Windows"
                            color: "white"
                            font.pixelSize: 15
                            font.weight: Font.Medium
                            style: Text.Raised
                            styleColor: Qt.rgba(0, 0, 0, .35)
                            anchors.verticalCenter: parent.verticalCenter
                        }
                    }
                }
            }

            // The home grid.
            Item {
                id: grid
                objectName: "homeGrid"
                x: root.gridX
                y: root.gridTop
                width: root.gridW
                height: root.gridH

                Repeater {
                    model: root.home.length
                    delegate: Item {
                        id: slot
                        required property int index
                        readonly property var s: root.home[index] || ({})
                        readonly property var b: root.boxOf(index)
                        readonly property bool isWidget: s.kind === "widget"
                        readonly property bool isLifted: index === root.lifted
                        readonly property bool isResizing: index === root.resizing
                        readonly property bool live: Host.android && isWidget && !root.drawerOpen
                            && (root.resizing < 0 || isResizing)
                        readonly property int row: isWidget ? -1 : (root.appRow[s.str] ?? -1)
                        objectName: "slot" + index
                        visible: b !== null
                        x: b ? b.x : 0
                        y: b ? b.y : 0
                        width: b ? b.w : 0
                        height: b ? b.h : 0
                        z: isLifted || isResizing ? 2 : 0
                        scale: isLifted ? 1.12 : 1
                        opacity: root.lifted >= 0 && !isLifted ? .8 : 1
                        Behavior on scale { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }

                        Rectangle { // the frame: a picked-up slot, or a widget with no live view
                            anchors.fill: parent
                            radius: slot.isWidget ? 20 : 16
                            visible: slot.isLifted || (slot.isWidget && (!slot.live || slot.isResizing))
                            color: slot.isLifted ? Qt.rgba(1, 1, 1, .16) : Qt.rgba(1, 1, 1, .1)
                            border.color: slot.isWidget && !slot.isLifted ? Qt.rgba(1, 1, 1, .3) : "transparent"
                            border.width: 1
                        }
                        Rectangle { // the outline around a widget being resized
                            visible: slot.isResizing
                            anchors.fill: parent
                            anchors.margins: -5
                            radius: 24
                            color: "transparent"
                            border.color: Qt.rgba(1, 1, 1, .85)
                            border.width: 2
                        }
                        Text {
                            visible: slot.isWidget && !slot.live
                            anchors.centerIn: parent
                            width: parent.width - 16
                            text: slot.s.str || ""
                            color: "white"
                            opacity: .75
                            font.pixelSize: 14
                            horizontalAlignment: Text.AlignHCenter
                            elide: Text.ElideRight
                            style: Text.Raised
                            styleColor: Qt.rgba(0, 0, 0, .55)
                        }
                        Tile {
                            visible: !slot.isWidget
                            anchors.fill: parent
                            key: "a:" + (slot.s.str || "")
                            label: slot.row >= 0 ? root.apps[slot.row].label : ""
                            generation: root.generation
                            iconSize: 40
                            fontSize: 11
                            spacing: 4
                            pressed: touch.pressedSlot === slot.index && !slot.isLifted && touch.pressed && root.lifted < 0
                        }
                    }
                }
            }

            Text {
                visible: root.home.length === 0
                x: root.contentX + 32
                width: root.contentW - 64
                y: root.height * .3 + root.insetTop
                text: "Swipe down for all apps. Long-press to add apps and widgets."
                color: "white"
                opacity: .85
                font.pixelSize: 15
                lineHeight: 1.5
                wrapMode: Text.Wrap
                horizontalAlignment: Text.AlignHCenter
                style: Text.Raised
                styleColor: Qt.rgba(0, 0, 0, .55)
            }
        }

        // Every touch on the home screen: taps, long presses, dragging a lifted slot,
        // scrolling a grid taller than the screen, and the swipe down to the drawer.
        MouseArea {
            id: touch
            objectName: "homeTouch"
            anchors.fill: parent
            enabled: !root.drawerOpen && root.sheet === ""
            property var start: null      // {x, y, t, scroll, moved}
            property int pressedSlot: -1
            property bool longFired: false
            property bool dragged: false

            function onPanel(x, y) {
                const p = windowsBtn.mapFromItem(touch, x, y);
                return p.x >= 0 && p.y >= 0 && p.x <= windowsBtn.width && p.y <= windowsBtn.height;
            }

            onPressed: (m) => {
                const g = root.toGrid(m.x, m.y);
                start = {x: m.x, y: m.y, t: Date.now(), scroll: root.scrollY, moved: false};
                pressedSlot = root.slotAtGrid(g.x, g.y, false);
                longFired = false;
                dragged = false;
                windowsBtn.pressed = onPanel(m.x, m.y);
                if (root.resizing >= 0 && pressedSlot === root.resizing) {
                    // A widget showing handles is moved by its body, no long press needed.
                    root.grab(pressedSlot);
                    longFired = true;
                    return;
                }
                hold.restart();
            }
            onPositionChanged: (m) => {
                if (!start) return;
                if (!start.moved && Math.hypot(m.x - start.x, m.y - start.y) < 10) return;
                if (!start.moved) { start.moved = true; hold.stop(); windowsBtn.pressed = false; }
                if (root.lifted >= 0) { dragged = true; root.dragTo(m.x, m.y, start.x, start.y); return; }
                if (!longFired) root.scrollY = Math.max(0, Math.min(root.maxScroll, start.scroll - (m.y - start.y)));
            }
            onReleased: (m) => {
                hold.stop();
                windowsBtn.pressed = false;
                const s = start;
                start = null;
                if (!s) return;
                if (root.lifted >= 0) { root.drop(s.moved); return; }
                if (longFired) return;
                if (!s.moved) {
                    const i = pressedSlot;
                    if (onPanel(m.x, m.y)) { Host.openRecents(); root.resizing = -1; }
                    else if (i >= 0 && root.home[i].kind === "app") { root.resizing = -1; Host.launch(root.home[i].str); }
                    else if (root.resizing >= 0) root.resizing = -1; // a tap beside the widget finishes
                    return;
                }
                const dx = m.x - s.x, dy = m.y - s.y;
                if (Date.now() - s.t < 700 && Math.abs(dy) >= 60 && Math.abs(dx) <= Math.abs(dy)
                        && dy > 0 && s.scroll <= 0)
                    root.openDrawer();
            }
            onCanceled: {
                hold.stop();
                windowsBtn.pressed = false;
                if (root.lifted >= 0) root.drop(start ? start.moved : false);
                start = null;
            }
            Timer {
                id: hold
                interval: 450
                onTriggered: {
                    if (!touch.start || touch.start.moved) return;
                    touch.longFired = true;
                    windowsBtn.pressed = false;
                    if (touch.pressedSlot >= 0) root.lift(touch.pressedSlot);
                    else { root.resizing = -1; Host.haptic(); root.sheet = "menu"; }
                }
            }
        }

        // Resize handles just outside the widget's right and bottom edges, where its
        // native view doesn't cover them.
        Item {
            id: handles
            readonly property var b: root.resizing >= 0 ? root.boxOf(root.resizing) : null
            visible: b !== null && root.lifted < 0 && root.sheet === ""
            x: root.gridX + (b ? b.x : 0)
            y: root.gridTop - root.scrollY + (b ? b.y : 0)
            width: b ? b.w : 0
            height: b ? b.h : 0
            property var rs: null

            function begin(edge) {
                const i = root.resizing, c = root.packed.cells[i];
                // Sizes are measured from where its top left was when the drag began,
                // though the widget may move as it resizes and the grid repacks.
                rs = {edge: edge, lim: Host.widgetLimits(root.home[i].wid), cols: c.w, rows: c.h,
                      at: handles.mapToItem(homeContent, 0, 0)};
            }
            function track(area, m) {
                if (!rs) return;
                const g = area.mapToItem(homeContent, m.x, m.y), lim = rs.lim;
                const p = {x: g.x - rs.at.x, y: g.y - rs.at.y};
                let c = rs.cols, r = rs.rows;
                if (rs.edge !== "b" && lim.h !== false)
                    c = L.fit(p.x, 1, root.cols, root.colW, L.CGAP, lim.minW || 0, lim.maxW || 0);
                if (rs.edge !== "r" && lim.v !== false)
                    r = L.fit(p.y, 1, 20, L.CELL, L.RGAP, lim.minH || 0, lim.maxH || 0);
                if (c === rs.cols && r === rs.rows) return;
                rs.cols = c; rs.rows = r;
                Host.haptic();
                root.resizeTo(c, r);
            }

            component Handle: MouseArea {
                id: h
                property string edge
                preventStealing: true
                onPressed: handles.begin(edge)
                onPositionChanged: (m) => handles.track(h, m)
                onReleased: handles.rs = null
                onCanceled: handles.rs = null
            }
            Handle {
                id: rHandle
                objectName: "handleR"
                edge: "r"
                x: parent.width; y: 0; width: 16; height: parent.height
                Rectangle { x: 6; anchors.verticalCenter: parent.verticalCenter; width: 6; height: 40; radius: 3; color: "white" }
            }
            Handle {
                id: bHandle
                objectName: "handleB"
                edge: "b"
                x: 0; y: parent.height; width: parent.width; height: 16
                Rectangle { y: 5; anchors.horizontalCenter: parent.horizontalCenter; width: 40; height: 6; radius: 3; color: "white" }
            }
            Handle {
                id: rbHandle
                objectName: "handleRB"
                edge: "rb"
                x: parent.width - 28; y: parent.height; width: 44; height: 16
                Rectangle { x: 26; y: 1; width: 14; height: 14; radius: 4; color: "white" }
            }
        }

        // While a widget is resized: what to do, and Remove / Done.
        Rectangle {
            id: resizeBar
            visible: root.resizing >= 0 && root.sheet === "" && !root.drawerOpen
            anchors.horizontalCenter: parent.horizontalCenter
            y: root.height - root.insetBottom - 16 - height
            width: Math.min(root.width - 32, barRow.implicitWidth + 24)
            height: Math.max(48, barRow.implicitHeight + 12)
            radius: height / 2
            color: Qt.rgba(0, 0, 0, .8)
            MouseArea { anchors.fill: parent }
            Row {
                id: barRow
                anchors.verticalCenter: parent.verticalCenter
                x: 16
                spacing: 6
                Text {
                    width: Math.min(implicitWidth, root.width - 32 - 24 - 16 - removeBtn.width - doneBtn.width - 12)
                    anchors.verticalCenter: parent.verticalCenter
                    text: "Drag to move, or drag the handles to resize"
                    color: "white"
                    font.pixelSize: 13
                    wrapMode: Text.Wrap
                }
                PillButton {
                    id: removeBtn
                    text: "Remove"
                    fontSize: 13
                    anchors.verticalCenter: parent.verticalCenter
                    onClicked: { const i = root.resizing; root.resizing = -1; root.removeSlot(i); }
                }
                PillButton {
                    id: doneBtn
                    text: "Done"
                    primary: true
                    fontSize: 13
                    anchors.verticalCenter: parent.verticalCenter
                    onClicked: root.resizing = -1
                }
            }
        }

        Drawer {
            id: drawer
            objectName: "drawer"
            open: root.drawerOpen
            picking: root.picking
            apps: root.apps
            homeSet: root.homeSet
            loading: root.loading
            generation: root.generation
            insetTop: root.insetTop
            insetBottom: root.insetBottom
            onEdgeChanged: root.cover()
            onVisibleChanged: root.cover()
            onOpenApp: (row) => root.launchApp(row)
            onToggleApp: (row) => root.toggleApp(row)
            onAppMenu: (row) => {
                if (root.sheet !== "") return;
                Host.haptic();
                root.appSheetRow = row;
                root.sheet = "app";
            }
            onCloseRequested: root.closeDrawer()
        }

        // ---- sheets
        Sheet {
            id: menuSheet
            open: root.sheet === "menu"
            title: "Home screen"
            bottomInset: root.insetBottom
            onCloseRequested: root.closeSheet()
            SheetButton { text: "Add apps"; onClicked: { root.sheet = ""; root.picking = true; root.drawerOpen = true; } }
            SheetButton { text: "Add widget"; onClicked: root.pickWidget() }
            SheetButton { text: "Wallpaper"; onClicked: { Host.pickWallpaper(); root.closeSheet(); } }
            SheetButton { text: "Settings"; onClicked: { Host.openSettings(); root.closeSheet(); } }
            SheetButton { text: "Check for updates"; onClicked: { Host.checkUpdates(); root.closeSheet(); } }
        }

        Sheet {
            id: itemSheet
            readonly property var s: root.item >= 0 && root.item < root.home.length ? root.home[root.item] : null
            open: root.sheet === "item" && s !== null
            title: !s ? "" : s.kind === "widget" ? s.str
                 : (root.appRow[s.str] !== undefined ? root.apps[root.appRow[s.str]].label : "")
            bottomInset: root.insetBottom
            onCloseRequested: root.closeSheet()
            SheetButton {
                visible: root.item > 0
                text: "Move earlier"
                onClicked: { root.swapSlots(root.item, root.item - 1); root.closeSheet(); }
            }
            SheetButton {
                visible: root.item >= 0 && root.item < root.home.length - 1
                text: "Move later"
                onClicked: { root.swapSlots(root.item + 1, root.item); root.closeSheet(); }
            }
            SheetButton {
                visible: itemSheet.s !== null && itemSheet.s.kind === "app"
                text: "App info"
                onClicked: { Host.appInfo(itemSheet.s.str); root.closeSheet(); }
            }
            SheetButton {
                text: itemSheet.s !== null && itemSheet.s.kind === "widget" ? "Remove widget" : "Remove from home"
                onClicked: { const i = root.item; root.resizing = -1; root.closeSheet(); root.removeSlot(i); }
            }
        }

        Sheet {
            id: appSheet
            readonly property var app: root.appSheetRow >= 0 && root.appSheetRow < root.apps.length
                ? root.apps[root.appSheetRow] : null
            open: root.sheet === "app" && app !== null
            title: app ? app.label : ""
            bottomInset: root.insetBottom
            onCloseRequested: root.closeSheet()
            SheetButton {
                text: appSheet.app && root.homeSet[appSheet.app.component] ? "Remove from home" : "Add to home"
                onClicked: {
                    const c = appSheet.app.component, i = root.homeOf(c);
                    if (i >= 0) root.removeSlot(i); else root.addApp(root.appSheetRow);
                    root.closeSheet();
                }
            }
            SheetButton {
                text: "App info"
                onClicked: { Host.appInfo(appSheet.app.component); root.closeSheet(); }
            }
            SheetButton {
                visible: appSheet.app !== null && !appSheet.app.system
                text: "Uninstall"
                onClicked: { root.gone = appSheet.app.component; Host.uninstall(root.gone); root.closeSheet(); }
            }
        }

        Sheet {
            id: widgetSheet
            open: root.sheet === "widgets"
            title: "Add a widget"
            bottomInset: root.insetBottom
            onCloseRequested: root.closeSheet()
            onOpenChanged: if (!open) { widgetSearch.input.focus = false; Qt.inputMethod.hide(); }
            Text {
                visible: root.wloading || root.providers.length === 0 || root.shownProviders.length === 0
                width: parent.width
                topPadding: 12
                bottomPadding: 12
                horizontalAlignment: Text.AlignHCenter
                color: "#a1a1aa"
                font.pixelSize: 15
                text: root.wloading ? "Loading widgets…"
                    : root.providers.length === 0 ? "No widgets installed." : "No widgets match."
            }
            SearchField {
                id: widgetSearch
                visible: !root.wloading && root.providers.length > 0
                width: parent.width
                height: visible ? 44 : 0
                placeholder: "Search widgets"
                fontSize: 15
                color: "#27272a"
                onAccepted: root.chooseWidget(root.shownProviders.length ? root.shownProviders[0] : -1)
                onEscaped: text = ""
            }
            Item { width: 1; height: 8 }
            Repeater {
                model: root.wloading ? [] : root.shownProviders
                delegate: Rectangle {
                    id: wrow
                    required property var modelData
                    readonly property var p: root.providers[modelData]
                    width: parent ? parent.width : 0
                    height: 56
                    radius: 8
                    color: wArea.pressed ? "#2e2e36" : "transparent"
                    AppIcon {
                        x: 8
                        anchors.verticalCenter: parent.verticalCenter
                        size: 40
                        key: "w:" + wrow.p.provider
                        generation: root.generation
                    }
                    Column {
                        x: 60
                        width: parent.width - 68
                        anchors.verticalCenter: parent.verticalCenter
                        Text { width: parent.width; text: wrow.p.label; color: "#fafafa"; font.pixelSize: 15; elide: Text.ElideRight }
                        Text {
                            width: parent.width
                            visible: wrow.p.app.length > 0 && wrow.p.app !== wrow.p.label
                            text: wrow.p.app
                            color: "#a1a1aa"
                            font.pixelSize: 13
                            elide: Text.ElideRight
                        }
                    }
                    MouseArea { id: wArea; anchors.fill: parent; onClicked: root.chooseWidget(wrow.modelData) }
                }
            }
        }

        // A short notice at the bottom (e.g. "Added to home").
        Rectangle {
            visible: root.notice.length > 0
            anchors.horizontalCenter: parent.horizontalCenter
            y: (resizeBar.visible ? resizeBar.y - 8 : root.height - root.insetBottom - 32) - height
            width: Math.min(root.width - 32, noticeText.implicitWidth + 32)
            height: 36
            radius: 18
            color: Qt.rgba(0, 0, 0, .8)
            Text {
                id: noticeText
                anchors.centerIn: parent
                width: Math.min(implicitWidth, root.width - 64)
                elide: Text.ElideMiddle
                text: root.notice
                color: "white"
                font.pixelSize: 14
            }
        }
    }
}
