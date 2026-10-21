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
def haptic() -> None:
    pass

@jscall
def clock_time(dst: i32) -> None:
    """Write the local time ("15:04") into dst."""
    pass

@jscall
def clock_date(dst: i32) -> None:
    """Write the local date ("Tuesday, October 21") into dst."""
    pass

# ---- Msg (index.html and MainActivity.java send TICK through WIDGET_MENU)
TICK: Const[i32] = 1
RELOAD: Const[i32] = 2
HOME: Const[i32] = 3
BACK: Const[i32] = 4
SWIPE_DOWN: Const[i32] = 5
SWIPE_UP: Const[i32] = 6
WIDGET_ADDED: Const[i32] = 7
WIDGET_MENU: Const[i32] = 8
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
ITEM_MENU: Const[i32] = 22     # arg: home slot
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

CAP: Const[i32] = 512
HOME_CAP: Const[i32] = 40
WCAP: Const[i32] = 256

APP: Const[i32] = 0
WIDGET: Const[i32] = 1

NONE: Const[i32] = 0
SHEET_MENU: Const[i32] = 1
SHEET_ITEM: Const[i32] = 2
SHEET_WIDGETS: Const[i32] = 3

# ---- Model
# Installed apps, as parallel columns of string handles, sorted by label.
labels: i32[512] = empty(512, dtype=int32)
comps: i32[512] = empty(512, dtype=int32)
icons: i32[512] = empty(512, dtype=int32)
n: i32 = 0
# The home screen, in order. An app slot keeps its component name in hstr;
# a widget slot keeps the widget's label there, its host id in hwid and its
# height in CSS px in hh. hkey is a stable key for the DOM diff.
hkind: i32[40] = empty(40, dtype=int32)
hstr: i32[40] = empty(40, dtype=int32)
hwid: i32[40] = empty(40, dtype=int32)
hh: i32[40] = empty(40, dtype=int32)
hkey: i32[40] = empty(40, dtype=int32)
nh: i32 = 0
next_key: i32 = 1
# Widget providers, for the picker.
wlabels: i32[256] = empty(256, dtype=int32)
wprovs: i32[256] = empty(256, dtype=int32)
wicons: i32[256] = empty(256, dtype=int32)
wheights: i32[256] = empty(256, dtype=int32)
nw: i32 = 0
wloading: i32 = 0
chosen: i32 = -1    # widget row being added

query: i32 = 0
now: i32 = 0
today: i32 = 0
err: i32 = 0
notice: i32 = 0
notice_gen: i32 = 0
scratch: i32 = 0
loading: i32 = 1
drawer: i32 = 0
picking: i32 = 0     # drawer is in "add apps" mode: taps toggle home slots
sheet: i32 = 0
item: i32 = -1       # home slot the item sheet is about
pending: i32 = 0     # JSON handle of a request in flight


def init() -> None:
    global query, now, today, err, notice, scratch, nh, next_key, pending
    i: i32
    h: i32
    k: i32
    query = s_new()
    now = s_new()
    today = s_new()
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
            hkey[nh] = next_key
            next_key += 1
            nh += 1
        else:
            s_free(h)
    clock_time(now)
    clock_date(today)
    after(5000, TICK, 0)
    pending = http_get("apps.json", LOADED)


def save_home() -> None:
    i: i32
    store_i32("ultra.home.n", nh)
    for i in range(nh):
        store_i32_at("ultra.home.kind", i, hkind[i])
        s_save_at("ultra.home.str", i, hstr[i])
        store_i32_at("ultra.home.wid", i, hwid[i])
        store_i32_at("ultra.home.h", i, hh[i])


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
        ok = j_str(row, "label", wlabels[nw])
        ok = j_str(row, "provider", wprovs[nw])
        ok = j_str(row, "icon", wicons[nw])
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
    t = hkey[a]
    hkey[a] = hkey[b]
    hkey[b] = t
    save_home()


def slot_of_widget(wid: i32) -> i32:
    i: i32
    for i in range(nh):
        if hkind[i] == WIDGET and hwid[i] == wid:
            return i
    return -1


def close_all() -> None:
    global drawer, picking, sheet, item
    s_clear(query)
    drawer = 0
    picking = 0
    sheet = NONE
    item = -1

# ---- Update
def update(msg: i32, arg: i32) -> None:
    global loading, drawer, picking, sheet, item, pending, wloading, chosen
    i: i32
    if msg == LOADED:
        load_apps(arg)
    elif msg == WIDGETS_LOADED:
        load_widgets(arg)
    elif msg == QUERY:
        s_copy(query, arg)
    elif msg == CLEAR:
        s_clear(query)
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
        haptic()
        sheet = SHEET_MENU
    elif msg == ITEM_MENU:
        haptic()
        item = arg
        sheet = SHEET_ITEM
    elif msg == WIDGET_MENU:
        i = slot_of_widget(arg)
        if i >= 0 and drawer == 0:
            item = i
            sheet = SHEET_ITEM
    elif msg == CLOSE_SHEET:
        sheet = NONE
        item = -1
    elif msg == PICK_APPS:
        sheet = NONE
        drawer = 1
        picking = 1
    elif msg == PICK_WIDGET:
        sheet = SHEET_WIDGETS
        wloading = 1
        pending = http_get("widgets.json", WIDGETS_LOADED)
    elif msg == CHOOSE_WIDGET:
        if nh < HOME_CAP:
            chosen = arg
            add_widget(wprovs[arg])
        else:
            say("The home screen is full")
        sheet = NONE
    elif msg == WIDGET_ADDED:
        if chosen >= 0 and nh < HOME_CAP:
            push_slot(WIDGET, s_dup(wlabels[chosen]), arg, wheights[chosen])
            chosen = -1
    elif msg == REMOVE:
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
    elif msg == NOTICE_DONE:
        if arg == notice_gen:
            s_clear(notice)
    elif msg == TICK:
        clock_time(now)
        clock_date(today)
        if arg == 0:
            after(5000, TICK, 0)
    elif msg == RELOAD:
        pending = http_get("apps.json", LOADED)
    elif msg == HOME:
        close_all()
    elif msg == BACK:
        if sheet != NONE:
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
    attr_ref("src", h)
    end()


def view_home_slot(i: i32) -> None:
    row: i32
    if hkind[i] == WIDGET:
        el("li")
        key(hkey[i])
        cls("widget-slot")
        s_set(scratch, "height:")
        s_add_num(scratch, hh[i])
        s_add(scratch, "px")
        attr_ref("style", scratch)
        # The native widget view is laid over slots marked live, so only mark
        # them while nothing covers the home screen.
        if drawer == 0 and sheet == NONE:
            attr_num("data-wid", hwid[i])
        on_longpress(ITEM_MENU, i)
        el("span")
        cls("widget-label")
        text_ref(hstr[i])
        end()
        end()
        return
    row = app_of(hstr[i])
    if row < 0:
        return
    el("li")
    key(hkey[i])
    cls("tile")
    on_click(OPEN_HOME, i)
    on_longpress(ITEM_MENU, i)
    view_icon(icons[row])
    el("span")
    cls("name")
    text_ref(labels[row])
    end()
    end()


def view_home() -> None:
    i: i32
    div("home")
    on_longpress(MENU, 0)
    el("button")
    cls("pull")
    attr("aria-label", "All apps")
    on_click(OPEN_DRAWER, 0)
    end()

    el("header")
    cls("clock")
    div("time")
    text_ref(now)
    end()
    div("date")
    text_ref(today)
    end()
    end()

    el("ul")
    cls("slots")
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
        el("ul")
        cls("widget-list")
        for i in range(nw):
            el("li")
            key(i + 1)
            on_click(CHOOSE_WIDGET, i)
            view_icon(wicons[i])
            el("span")
            text_ref(wlabels[i])
            end()
            end()
        end()
    end()


def view() -> None:
    view_home()
    if drawer == 1:
        view_drawer()
    if sheet != NONE:
        view_sheet()
    if s_len(notice) > 0:
        el("div")
        cls("notice")
        attr("role", "status")
        text_ref(notice)
        end()
