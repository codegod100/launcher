from lpython import i32, Const
from numpy import empty, int32
from ultra import (el, end, text, text_ref, num, cls, cls_if, attr, attr_ref, attr_num, key,
                   on_click, on_enter, on_escape, on_longpress, after, div,
                   s_new, s_dup, s_set, s_add, s_add_num, s_clear, s_free, s_copy, s_eq, s_has, s_len,
                   s_save_at, s_load_at, store_i32, load_i32, store_i32_at, load_i32_at,
                   http_get, j_ok, j_err, j_at, j_idx, j_len, j_int, j_str, j_free)
from ui import btn_open, input_open, alert

# An Android home screen. The home starts blank: you add apps and widgets to it
# with a long press. Swiping down opens the drawer with every app and a search.
# A long press on a home app or widget picks it up: drag it to move it, or let go
# without moving to get its menu (index.html does the dragging). Letting go of a
# widget that way shows resize handles on its right and bottom edges instead:
# dragging them snaps the widget to whole grid columns and rows of app tiles,
# within the sizes it allows, and dragging the widget itself moves it, staying
# in that mode once let go. Apps flow into the free cells beside widgets.
#
# The native shell (android/) serves this folder in a WebView, answers
# apps.json and widgets.json, hosts the widgets as native views over the
# slots this page lays out, and provides the imports below. In a desktop
# browser index.html supplies stand-ins.

@jscall
def launch(component: i32) -> None:
    """Start the activity named by a "pkg/cls" string handle."""
    pass

@jscall
def app_info(component: i32) -> None:
    pass

@jscall
def add_widget(provider: i32) -> None:
    """Bind (and configure) a widget; the shell answers with WIDGET_ADDED(id)."""
    pass

@jscall
def remove_widget(wid: i32) -> None:
    pass

@jscall
def open_settings() -> None:
    pass

@jscall
def pick_wallpaper() -> None:
    pass

@jscall
def open_recents() -> None:
    """Show the system's list of open windows (Recents)."""
    pass

@jscall
def check_updates() -> None:
    """Look for a newer launcher build and install it (the shell shows its own progress)."""
    pass

@jscall
def haptic() -> None:
    pass

# ---- Msg (index.html and MainActivity.java send RELOAD through WIDGET_MENU)
RELOAD: Const[i32] = 2
HOME: Const[i32] = 3
BACK: Const[i32] = 4
SWIPE_DOWN: Const[i32] = 5
SWIPE_UP: Const[i32] = 6
WIDGET_ADDED: Const[i32] = 7
WIDGET_MENU: Const[i32] = 8   # arg: widget id; a long press on a native widget lifts it
LOADED: Const[i32] = 10
WIDGETS_LOADED: Const[i32] = 11
QUERY: Const[i32] = 12
CLEAR: Const[i32] = 13
OPEN_DRAWER: Const[i32] = 14
CLOSE_DRAWER: Const[i32] = 15
OPEN_APP: Const[i32] = 16      # arg: row in the app list
OPEN_FIRST: Const[i32] = 17
OPEN_HOME: Const[i32] = 18     # arg: home slot
ADD_HOME: Const[i32] = 19      # arg: row in the app list (long press in the drawer)
TOGGLE_HOME: Const[i32] = 20   # arg: row in the app list (drawer in pick mode)
MENU: Const[i32] = 21          # long press on the empty home
CLOSE_SHEET: Const[i32] = 23
PICK_APPS: Const[i32] = 24
PICK_WIDGET: Const[i32] = 25
CHOOSE_WIDGET: Const[i32] = 26  # arg: row in the widget list
REMOVE: Const[i32] = 27        # arg: home slot
MOVE: Const[i32] = 28          # arg: home slot; moves it one earlier
INFO: Const[i32] = 29          # arg: home slot
SETTINGS: Const[i32] = 30
WALLPAPER: Const[i32] = 31
NOTICE_DONE: Const[i32] = 32
RECENTS: Const[i32] = 33
LIFT: Const[i32] = 34          # arg: home slot (long press picks it up)
DRAG_TO: Const[i32] = 35       # arg: home slot the lifted one is dragged over
DROP: Const[i32] = 36          # arg: 1 if it was dragged, 0 if just held (opens its menu)
WQUERY: Const[i32] = 37        # widget picker search
WCLEAR: Const[i32] = 38
CHOOSE_FIRST_WIDGET: Const[i32] = 39
RESIZE: Const[i32] = 40        # arg: columns * 4096 + height in px for the widget being resized
RESIZE_DONE: Const[i32] = 41
GRAB: Const[i32] = 42          # arg: the widget slot showing handles, picked up by its body
CHECK_UPDATES: Const[i32] = 43
WIDGET_COLS: Const[i32] = 44   # arg: columns for the widget just added (0: the whole row)

CAP: Const[i32] = 512
HOME_CAP: Const[i32] = 200
WCAP: Const[i32] = 256

APP: Const[i32] = 0
WIDGET: Const[i32] = 1

NONE: Const[i32] = 0
SHEET_MENU: Const[i32] = 1
SHEET_ITEM: Const[i32] = 2
SHEET_WIDGETS: Const[i32] = 3

# ---- Model
# Every module-level array is i32[512], even where HOME_CAP or WCAP is smaller:
# ultra's build hands LPython's array allocations to the globals in an order that
# doesn't match, so arrays of different sizes overlapped (rows past 256 of comps
# wrote over icons, and wlabels was 40 slots long). With one size, any pairing is safe.
# Installed apps, as parallel columns of string handles, sorted by label.
labels: i32[512] = empty(512, dtype=int32)
comps: i32[512] = empty(512, dtype=int32)
icons: i32[512] = empty(512, dtype=int32)
n: i32 = 0
# The home screen, in order. An app slot keeps its component name in hstr;
# a widget slot keeps the widget's label there, its host id in hwid, its
# height in CSS px in hh and its width in grid columns in hw (0: the whole row).
# hkey is a stable key for the DOM diff.
hkind: i32[512] = empty(512, dtype=int32)
hstr: i32[512] = empty(512, dtype=int32)
hwid: i32[512] = empty(512, dtype=int32)
hh: i32[512] = empty(512, dtype=int32)
hw: i32[512] = empty(512, dtype=int32)
hkey: i32[512] = empty(512, dtype=int32)
nh: i32 = 0
next_key: i32 = 1
# Widget providers, for the picker.
wlabels: i32[512] = empty(512, dtype=int32)
wprovs: i32[512] = empty(512, dtype=int32)
wicons: i32[512] = empty(512, dtype=int32)
wapps: i32[512] = empty(512, dtype=int32)    # label of the app the widget comes from
wheights: i32[512] = empty(512, dtype=int32)
nw: i32 = 0
wloading: i32 = 0
chosen: i32 = -1    # widget row being added

query: i32 = 0
wquery: i32 = 0     # widget picker search
err: i32 = 0
notice: i32 = 0
notice_gen: i32 = 0
scratch: i32 = 0
loading: i32 = 1
drawer: i32 = 0
picking: i32 = 0     # drawer is in "add apps" mode: taps toggle home slots
sheet: i32 = 0
item: i32 = -1       # home slot the item sheet is about
lifted: i32 = -1     # home slot picked up by a long press, being dragged
resizing: i32 = -1   # widget slot showing resize handles
regrab: i32 = 0      # the lifted slot was grabbed while showing handles: drop it back into them
pending: i32 = 0     # JSON handle of a request in flight


def init() -> None:
    global query, wquery, err, notice, scratch, nh, next_key, pending
    i: i32
    h: i32
    k: i32
    query = s_new()
    wquery = s_new()
    err = s_new()
    notice = s_new()
    scratch = s_new()
    count: i32 = load_i32("ultra.home.n", 0)
    for i in range(count):
        h = s_new()
        if s_load_at("ultra.home.str", i, h) == 1 and nh < HOME_CAP:
            k = load_i32_at("ultra.home.kind", i, APP)
            hkind[nh] = k
            hstr[nh] = h
            hwid[nh] = load_i32_at("ultra.home.wid", i, 0)
            hh[nh] = load_i32_at("ultra.home.h", i, 120)
            hw[nh] = load_i32_at("ultra.home.w", i, 0)
            hkey[nh] = next_key
            next_key += 1
            nh += 1
        else:
            s_free(h)
    pending = http_get("apps.json", LOADED)


def save_home() -> None:
    i: i32
    store_i32("ultra.home.n", nh)
    for i in range(nh):
        store_i32_at("ultra.home.kind", i, hkind[i])
        s_save_at("ultra.home.str", i, hstr[i])
        store_i32_at("ultra.home.wid", i, hwid[i])
        store_i32_at("ultra.home.h", i, hh[i])
        store_i32_at("ultra.home.w", i, hw[i])


def say(msg: str) -> None:
    global notice_gen
    s_set(notice, msg)
    notice_gen += 1
    after(1800, NOTICE_DONE, notice_gen)

# ---- apps

def clear_apps() -> None:
    global n
    i: i32
    for i in range(n):
        s_free(labels[i])
        s_free(comps[i])
        s_free(icons[i])
    n = 0


def load_apps(doc: i32) -> None:
    global n, loading
    arr: i32
    row: i32
    cnt: i32
    i: i32
    ok: i32
    loading = 0
    if j_ok(doc) == 0:
        j_err(doc, err)
        j_free(doc)
        return
    s_clear(err)
    clear_apps()
    arr = j_at(doc, "apps")
    cnt = j_len(doc, "apps")
    if cnt > CAP:
        cnt = CAP
    for i in range(cnt):
        row = j_idx(arr, i)
        labels[n] = s_new()
        comps[n] = s_new()
        icons[n] = s_new()
        ok = j_str(row, "label", labels[n])
        ok = j_str(row, "component", comps[n])
        ok = j_str(row, "icon", icons[n])
        j_free(row)
        n += 1
    j_free(arr)
    j_free(doc)


def load_widgets(doc: i32) -> None:
    global nw, wloading
    arr: i32
    row: i32
    cnt: i32
    i: i32
    ok: i32
    wloading = 0
    for i in range(nw):
        s_free(wlabels[i])
        s_free(wprovs[i])
        s_free(wicons[i])
        s_free(wapps[i])
    nw = 0
    if j_ok(doc) == 0:
        j_free(doc)
        return
    arr = j_at(doc, "widgets")
    cnt = j_len(doc, "widgets")
    if cnt > WCAP:
        cnt = WCAP
    for i in range(cnt):
        row = j_idx(arr, i)
        wlabels[nw] = s_new()
        wprovs[nw] = s_new()
        wicons[nw] = s_new()
        wapps[nw] = s_new()
        ok = j_str(row, "label", wlabels[nw])
        ok = j_str(row, "provider", wprovs[nw])
        ok = j_str(row, "icon", wicons[nw])
        ok = j_str(row, "app", wapps[nw])
        wheights[nw] = j_int(row, "h")
        j_free(row)
        nw += 1
    j_free(arr)
    j_free(doc)


def app_of(comp: i32) -> i32:
    """Row in the app list for a component handle, or -1 if it is not installed."""
    i: i32
    for i in range(n):
        if s_eq(comps[i], comp) == 1:
            return i
    return -1


def home_of(row: i32) -> i32:
    """Home slot holding app row, or -1."""
    i: i32
    for i in range(nh):
        if hkind[i] == APP and s_eq(hstr[i], comps[row]) == 1:
            return i
    return -1


def matches(i: i32) -> bool:
    return s_has(labels[i], query) == 1


def widget_matches(i: i32) -> bool:
    return s_has(wlabels[i], wquery) == 1 or s_has(wapps[i], wquery) == 1


def first_widget_match() -> i32:
    i: i32
    for i in range(nw):
        if widget_matches(i):
            return i
    return -1


def first_match() -> i32:
    i: i32
    for i in range(n):
        if matches(i):
            return i
    return -1

# ---- home slots

def push_slot(kind: i32, s: i32, wid: i32, h: i32) -> None:
    global nh, next_key
    hkind[nh] = kind
    hstr[nh] = s
    hwid[nh] = wid
    hh[nh] = h
    hw[nh] = 0
    hkey[nh] = next_key
    next_key += 1
    nh += 1
    save_home()


def add_app(row: i32) -> None:
    if home_of(row) >= 0:
        say("Already on the home screen")
    elif nh >= HOME_CAP:
        say("The home screen is full")
    else:
        push_slot(APP, s_dup(comps[row]), 0, 0)
        say("Added to home")


def remove_slot(at: i32) -> None:
    global nh
    j: i32
    if hkind[at] == WIDGET:
        remove_widget(hwid[at])
    s_free(hstr[at])
    for j in range(at, nh - 1):
        hkind[j] = hkind[j + 1]
        hstr[j] = hstr[j + 1]
        hwid[j] = hwid[j + 1]
        hh[j] = hh[j + 1]
        hw[j] = hw[j + 1]
        hkey[j] = hkey[j + 1]
    nh -= 1
    save_home()


def swap_slots(a: i32, b: i32) -> None:
    t: i32
    t = hkind[a]
    hkind[a] = hkind[b]
    hkind[b] = t
    t = hstr[a]
    hstr[a] = hstr[b]
    hstr[b] = t
    t = hwid[a]
    hwid[a] = hwid[b]
    hwid[b] = t
    t = hh[a]
    hh[a] = hh[b]
    hh[b] = t
    t = hw[a]
    hw[a] = hw[b]
    hw[b] = t
    t = hkey[a]
    hkey[a] = hkey[b]
    hkey[b] = t
    save_home()


def move_slot(a: i32, b: i32) -> None:
    """Take slot a out and put it back at b, shifting the slots between."""
    i: i32 = a
    while i < b:
        swap_slots(i, i + 1)
        i += 1
    while i > b:
        swap_slots(i, i - 1)
        i -= 1


def slot_of_widget(wid: i32) -> i32:
    i: i32
    for i in range(nh):
        if hkind[i] == WIDGET and hwid[i] == wid:
            return i
    return -1


def close_all() -> None:
    global drawer, picking, sheet, item, lifted, resizing, regrab
    s_clear(query)
    drawer = 0
    picking = 0
    sheet = NONE
    item = -1
    lifted = -1
    resizing = -1
    regrab = 0

# ---- Update
def update(msg: i32, arg: i32) -> None:
    global loading, drawer, picking, sheet, item, pending, wloading, chosen, lifted, resizing, regrab
    i: i32
    if msg == LOADED:
        load_apps(arg)
    elif msg == WIDGETS_LOADED:
        load_widgets(arg)
    elif msg == QUERY:
        s_copy(query, arg)
    elif msg == CLEAR:
        s_clear(query)
    elif msg == WQUERY:
        s_copy(wquery, arg)
    elif msg == WCLEAR:
        s_clear(wquery)
    elif msg == OPEN_DRAWER or msg == SWIPE_DOWN:
        if sheet == NONE:
            drawer = 1
    elif msg == CLOSE_DRAWER or msg == SWIPE_UP:
        if drawer == 1 and sheet == NONE:
            drawer = 0
            picking = 0
            s_clear(query)
    elif msg == OPEN_APP:
        launch(comps[arg])
        close_all()
    elif msg == OPEN_FIRST:
        i = first_match()
        if i >= 0 and s_len(query) > 0:
            launch(comps[i])
            close_all()
    elif msg == OPEN_HOME:
        resizing = -1
        launch(hstr[arg])
    elif msg == ADD_HOME:
        haptic()
        add_app(arg)
    elif msg == TOGGLE_HOME:
        i = home_of(arg)
        if i >= 0:
            remove_slot(i)
        elif nh < HOME_CAP:
            push_slot(APP, s_dup(comps[arg]), 0, 0)
    elif msg == MENU:
        resizing = -1
        haptic()
        sheet = SHEET_MENU
    elif msg == LIFT:
        if drawer == 0 and sheet == NONE and arg != lifted:
            haptic()
            resizing = -1
            regrab = 0
            lifted = arg
    elif msg == WIDGET_MENU:
        i = slot_of_widget(arg)
        if i >= 0 and drawer == 0 and sheet == NONE:
            resizing = -1
            regrab = 0
            lifted = i
    elif msg == GRAB:
        if arg >= 0 and arg == resizing and drawer == 0 and sheet == NONE:
            resizing = -1
            regrab = 1
            lifted = arg
    elif msg == DRAG_TO:
        if lifted >= 0 and arg >= 0 and arg < nh and arg != lifted:
            move_slot(lifted, arg)
            lifted = arg
    elif msg == DROP:
        if lifted >= 0 and (arg == 0 or regrab == 1):
            if hkind[lifted] == WIDGET:
                resizing = lifted
            else:
                item = lifted
                sheet = SHEET_ITEM
        lifted = -1
        regrab = 0
    elif msg == RESIZE:
        if resizing >= 0:
            hw[resizing] = arg // 4096
            hh[resizing] = arg % 4096
            save_home()
    elif msg == RESIZE_DONE:
        resizing = -1
    elif msg == CLOSE_SHEET:
        sheet = NONE
        item = -1
    elif msg == PICK_APPS:
        sheet = NONE
        drawer = 1
        picking = 1
    elif msg == PICK_WIDGET:
        sheet = SHEET_WIDGETS
        s_clear(wquery)
        wloading = 1
        pending = http_get("widgets.json", WIDGETS_LOADED)
    elif msg == CHOOSE_WIDGET or msg == CHOOSE_FIRST_WIDGET:
        i = arg
        if msg == CHOOSE_FIRST_WIDGET:
            i = first_widget_match()
        if i >= 0:
            if nh < HOME_CAP:
                chosen = i
                add_widget(wprovs[i])
            else:
                say("The home screen is full")
            sheet = NONE
    elif msg == WIDGET_ADDED:
        if chosen >= 0 and nh < HOME_CAP:
            push_slot(WIDGET, s_dup(wlabels[chosen]), arg, wheights[chosen])
            chosen = -1
    elif msg == WIDGET_COLS:
        if nh > 0 and hkind[nh - 1] == WIDGET:
            hw[nh - 1] = arg
            save_home()
    elif msg == REMOVE:
        resizing = -1
        remove_slot(arg)
        sheet = NONE
        item = -1
    elif msg == MOVE:
        if arg > 0:
            swap_slots(arg, arg - 1)
        sheet = NONE
        item = -1
    elif msg == INFO:
        app_info(hstr[arg])
        sheet = NONE
    elif msg == SETTINGS:
        open_settings()
        sheet = NONE
    elif msg == WALLPAPER:
        pick_wallpaper()
        sheet = NONE
    elif msg == RECENTS:
        open_recents()
    elif msg == CHECK_UPDATES:
        check_updates()
        sheet = NONE
    elif msg == NOTICE_DONE:
        if arg == notice_gen:
            s_clear(notice)
    elif msg == RELOAD:
        pending = http_get("apps.json", LOADED)
    elif msg == HOME:
        close_all()
    elif msg == BACK:
        if lifted >= 0:
            lifted = -1
            regrab = 0
        elif resizing >= 0:
            resizing = -1
        elif sheet != NONE:
            sheet = NONE
            item = -1
        elif drawer == 1:
            drawer = 0
            picking = 0
            s_clear(query)

# ---- View
def view_icon(h: i32) -> None:
    el("img")
    cls("icon")
    attr("alt", "")
    attr("draggable", "false")
    attr_ref("data-icon", h)   # index.html turns this into a src
    end()


def view_handle(edge: str) -> None:
    """A resize handle just outside the slot (the native widget covers the inside)."""
    el("span")
    cls("resize-handle")
    attr("data-edge", edge)
    attr("aria-hidden", "true")
    end()


def view_resize_bar() -> None:
    el("div")
    cls("resize-bar")
    attr("role", "toolbar")
    el("span")
    cls("resize-hint")
    text("Drag to move, or drag the handles to resize")
    end()
    btn_open("ghost", REMOVE, resizing)
    cls("sm")
    text("Remove")
    end()
    btn_open("primary", RESIZE_DONE, 0)
    cls("sm")
    text("Done")
    end()
    end()


def widget_rows(h: i32) -> i32:
    """Rows of app tiles nearest to a widget height in CSS px, at least one."""
    k: i32 = (h + 8 + 34) // 68
    if k < 1:
        k = 1
    return k


def view_home_slot(i: i32) -> None:
    row: i32
    if hkind[i] == WIDGET:
        el("li")
        key(hkey[i])
        cls("widget-slot")
        cls_if("lifted", i == lifted)
        cls_if("resizing", i == resizing)
        attr_num("data-slot", i)
        # Its height spans whole rows of app tiles (60px rows, 8px gaps; style.css).
        s_set(scratch, "grid-row:span ")
        s_add_num(scratch, widget_rows(hh[i]))
        if hw[i] > 0:
            s_add(scratch, ";grid-column:span ")
            s_add_num(scratch, hw[i])
        attr_ref("style", scratch)
        # The native widget view is laid over slots marked live, so only mark
        # them while nothing covers the home screen. While one is resized the
        # others step aside, so nothing native covers its handles.
        if drawer == 0 and sheet == NONE and (resizing < 0 or i == resizing):
            attr_num("data-wid", hwid[i])
        on_longpress(LIFT, i)
        el("span")
        cls("widget-label")
        text_ref(hstr[i])
        end()
        if i == resizing:
            view_handle("r")
            view_handle("b")
            view_handle("rb")
        end()
        return
    row = app_of(hstr[i])
    if row < 0:
        return
    el("li")
    key(hkey[i])
    cls("tile")
    cls_if("lifted", i == lifted)
    attr_num("data-slot", i)
    on_click(OPEN_HOME, i)
    on_longpress(LIFT, i)
    view_icon(icons[row])
    el("span")
    cls("name")
    text_ref(labels[row])
    end()
    end()


def view_panel() -> None:
    """The panel at the top of home: shortcuts to system surfaces."""
    el("section")
    cls("panel")
    attr("aria-label", "Shortcuts")
    el("button")
    cls("panel-btn")
    on_click(RECENTS, 0)
    el("span")
    cls("windows-glyph")
    attr("aria-hidden", "true")
    end()
    el("span")
    text("Windows")
    end()
    end()
    end()


def view_home() -> None:
    i: i32
    div("home")
    on_longpress(MENU, 0)
    if resizing >= 0:
        on_click(RESIZE_DONE, 0)   # a tap beside the widget finishes
    view_panel()

    el("ul")
    cls("slots")
    cls_if("dragging", lifted >= 0)
    for i in range(nh):
        view_home_slot(i)
    end()

    if nh == 0:
        el("p")
        cls("hint")
        text("Swipe down for all apps. Long-press to add apps and widgets.")
        end()
    end()


def view_drawer() -> None:
    i: i32
    shown: i32 = 0
    div("drawer")
    cls_if("picking", picking == 1)
    div("drawer-head")
    input_open("Search apps", query, QUERY)
    attr("type", "search")
    attr("enterkeyhint", "go")
    on_enter(OPEN_FIRST, 0)
    on_escape(CLEAR, 0)
    end()
    btn_open("ghost", CLOSE_DRAWER, 0)
    cls("sm")
    if picking == 1:
        text("Done")
    else:
        text("Close")
    end()
    end()
    if picking == 1:
        el("p")
        cls("drawer-note")
        text("Tap apps to add them to the home screen or take them off.")
        end()
    if s_len(err) > 0:
        alert("Could not list apps", "", "destructive")

    el("ul")
    cls("apps")
    for i in range(n):
        if matches(i):
            shown += 1
            el("li")
            key(i + 1)
            cls("tile")
            if picking == 1:
                cls_if("on", home_of(i) >= 0)
                on_click(TOGGLE_HOME, i)
            else:
                on_click(OPEN_APP, i)
                on_longpress(ADD_HOME, i)
            view_icon(icons[i])
            el("span")
            cls("name")
            text_ref(labels[i])
            end()
            end()
    end()
    if loading == 1:
        el("p")
        cls("empty")
        text("Loading apps…")
        end()
    elif shown == 0:
        el("p")
        cls("empty")
        text("No apps match.")
        end()
    end()


def sheet_btn(s: str, msg: i32, arg: i32) -> None:
    el("button")
    cls("sheet-item")
    on_click(msg, arg)
    text(s)
    end()


def view_sheet() -> None:
    i: i32
    row: i32
    shown: i32 = 0
    el("div")
    cls("sheet-overlay")
    on_click(CLOSE_SHEET, 0)
    end()
    el("div")
    cls("sheet")
    attr("role", "dialog")
    attr("aria-modal", "true")
    on_escape(CLOSE_SHEET, 0)
    if sheet == SHEET_MENU:
        el("h2")
        cls("sheet-title")
        text("Home screen")
        end()
        sheet_btn("Add apps", PICK_APPS, 0)
        sheet_btn("Add widget", PICK_WIDGET, 0)
        sheet_btn("Wallpaper", WALLPAPER, 0)
        sheet_btn("Settings", SETTINGS, 0)
        sheet_btn("Check for updates", CHECK_UPDATES, 0)
    elif sheet == SHEET_ITEM and item >= 0:
        el("h2")
        cls("sheet-title")
        if hkind[item] == WIDGET:
            text_ref(hstr[item])
        else:
            row = app_of(hstr[item])
            if row >= 0:
                text_ref(labels[row])
        end()
        if item > 0:
            sheet_btn("Move earlier", MOVE, item)
        if item < nh - 1:
            sheet_btn("Move later", MOVE, item + 1)
        if hkind[item] == APP:
            sheet_btn("App info", INFO, item)
            sheet_btn("Remove from home", REMOVE, item)
        else:
            sheet_btn("Remove widget", REMOVE, item)
    elif sheet == SHEET_WIDGETS:
        el("h2")
        cls("sheet-title")
        text("Add a widget")
        end()
        if wloading == 1:
            el("p")
            cls("empty")
            text("Loading widgets…")
            end()
        elif nw == 0:
            el("p")
            cls("empty")
            text("No widgets installed.")
            end()
        else:
            input_open("Search widgets", wquery, WQUERY)
            cls("widget-search")
            attr("type", "search")
            attr("enterkeyhint", "go")
            on_enter(CHOOSE_FIRST_WIDGET, 0)
            on_escape(WCLEAR, 0)
            end()
        el("ul")
        cls("widget-list")
        for i in range(nw):
            if widget_matches(i):
                shown += 1
                el("li")
                key(i + 1)
                on_click(CHOOSE_WIDGET, i)
                view_icon(wicons[i])
                el("span")
                cls("widget-name")
                text_ref(wlabels[i])
                if s_len(wapps[i]) > 0 and s_eq(wapps[i], wlabels[i]) == 0:
                    el("small")
                    text_ref(wapps[i])
                    end()
                end()
                end()
        end()
        if wloading == 0 and nw > 0 and shown == 0:
            el("p")
            cls("empty")
            text("No widgets match.")
            end()
    end()


def view() -> None:
    view_home()
    if drawer == 1:
        view_drawer()
    if sheet != NONE:
        view_sheet()
    elif resizing >= 0 and drawer == 0:
        view_resize_bar()
    if s_len(notice) > 0:
        el("div")
        cls("notice")
        attr("role", "status")
        text_ref(notice)
        end()
