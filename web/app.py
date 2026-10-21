from lpython import i32, Const
from numpy import empty, int32
from ultra import (el, end, text, text_ref, cls, cls_if, attr, attr_ref, key,
                   on_click, on_enter, on_escape, after, div,
                   s_new, s_dup, s_clear, s_free, s_copy, s_eq, s_has, s_len,
                   s_save_at, s_load_at, store_i32, load_i32,
                   http_get, j_ok, j_err, j_at, j_idx, j_len, j_str, j_free)
from ui import btn_open, input_open, alert

# An Android home screen. The native shell (android/) serves this folder in a
# WebView, answers apps.json with the installed launchable activities, and
# provides the imports below. In a desktop browser index.html supplies stand-ins.

@jscall
def launch(component: i32) -> None:
    """Start the activity named by a "pkg/cls" string handle."""
    pass

@jscall
def app_info(component: i32) -> None:
    pass

@jscall
def open_settings() -> None:
    pass

@jscall
def pick_wallpaper() -> None:
    pass

@jscall
def clock_time(dst: i32) -> None:
    """Write the local time ("15:04") into dst."""
    pass

@jscall
def clock_date(dst: i32) -> None:
    """Write the local date ("Tuesday, October 21") into dst."""
    pass

# ---- Msg (index.html and MainActivity.java send RELOAD, HOME, BACK and TICK)
LOADED: Const[i32] = 1
QUERY: Const[i32] = 2
CLEAR: Const[i32] = 3
OPEN: Const[i32] = 4
OPEN_FIRST: Const[i32] = 5
EDIT: Const[i32] = 6
PIN: Const[i32] = 7
INFO: Const[i32] = 8
SETTINGS: Const[i32] = 9
WALLPAPER: Const[i32] = 10
TICK: Const[i32] = 11
RELOAD: Const[i32] = 12
HOME: Const[i32] = 13
BACK: Const[i32] = 14

CAP: Const[i32] = 512
DOCK: Const[i32] = 5

# ---- Model
# One row per launchable activity, as parallel columns of string handles.
labels: i32[512] = empty(512, dtype=int32)
comps: i32[512] = empty(512, dtype=int32)
icons: i32[512] = empty(512, dtype=int32)
n: i32 = 0
# Dock: pinned activities, kept by component name so they survive reloads.
pins: i32[5] = empty(5, dtype=int32)
npin: i32 = 0
query: i32 = 0
now: i32 = 0
today: i32 = 0
err: i32 = 0
loading: i32 = 1
editing: i32 = 0
pending: i32 = 0     # JSON handle of the apps.json request in flight


def init() -> None:
    global query, now, today, err, npin, pending
    i: i32
    h: i32
    query = s_new()
    now = s_new()
    today = s_new()
    err = s_new()
    count: i32 = load_i32("ultra.launcher.npin", 0)
    for i in range(count):
        h = s_new()
        if s_load_at("ultra.launcher.pin", i, h) == 1 and npin < DOCK:
            pins[npin] = h
            npin += 1
        else:
            s_free(h)
    clock_time(now)
    clock_date(today)
    after(5000, TICK, 0)
    pending = http_get("apps.json", LOADED)


def save_pins() -> None:
    i: i32
    store_i32("ultra.launcher.npin", npin)
    for i in range(npin):
        s_save_at("ultra.launcher.pin", i, pins[i])


def clear_apps() -> None:
    global n
    i: i32
    for i in range(n):
        s_free(labels[i])
        s_free(comps[i])
        s_free(icons[i])
    n = 0


def load(doc: i32) -> None:
    global n, loading
    arr: i32
    item: i32
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
        item = j_idx(arr, i)
        labels[n] = s_new()
        comps[n] = s_new()
        icons[n] = s_new()
        ok = j_str(item, "label", labels[n])
        ok = j_str(item, "component", comps[n])
        ok = j_str(item, "icon", icons[n])
        j_free(item)
        n += 1
    j_free(arr)
    j_free(doc)


def pin_of(i: i32) -> i32:
    """Dock slot holding app i, or -1."""
    p: i32
    for p in range(npin):
        if s_eq(pins[p], comps[i]) == 1:
            return p
    return -1


def app_of(p: i32) -> i32:
    """App row for dock slot p, or -1 if it is not installed (any more)."""
    i: i32
    for i in range(n):
        if s_eq(pins[p], comps[i]) == 1:
            return i
    return -1


def toggle_pin(i: i32) -> None:
    global npin
    p: i32 = pin_of(i)
    j: i32
    if p >= 0:
        s_free(pins[p])
        for j in range(p, npin - 1):
            pins[j] = pins[j + 1]
        npin -= 1
    elif npin < DOCK:
        pins[npin] = s_dup(comps[i])
        npin += 1
    save_pins()


def matches(i: i32) -> bool:
    return s_has(labels[i], query) == 1


def first_match() -> i32:
    i: i32
    for i in range(n):
        if matches(i):
            return i
    return -1


def reset() -> None:
    global editing
    s_clear(query)
    editing = 0


# ---- Update
def update(msg: i32, arg: i32) -> None:
    global loading, editing, pending
    i: i32
    if msg == LOADED:
        load(arg)
    elif msg == QUERY:
        s_copy(query, arg)
    elif msg == CLEAR:
        s_clear(query)
    elif msg == OPEN:
        if editing == 1:
            toggle_pin(arg)
        else:
            launch(comps[arg])
            reset()
    elif msg == OPEN_FIRST:
        i = first_match()
        if i >= 0 and s_len(query) > 0:
            launch(comps[i])
            reset()
    elif msg == EDIT:
        editing = 1 - editing
    elif msg == PIN:
        toggle_pin(arg)
    elif msg == INFO:
        app_info(comps[arg])
    elif msg == SETTINGS:
        open_settings()
    elif msg == WALLPAPER:
        pick_wallpaper()
    elif msg == TICK:
        clock_time(now)
        clock_date(today)
        if arg == 0:
            after(5000, TICK, 0)
    elif msg == RELOAD:
        pending = http_get("apps.json", LOADED)
    elif msg == HOME:
        reset()
    elif msg == BACK:
        if editing == 1 or s_len(query) > 0:
            reset()


# ---- View
def view_icon(i: i32) -> None:
    el("img")
    cls("icon")
    attr("alt", "")
    attr("loading", "lazy")
    attr_ref("src", icons[i])
    end()


def view_tile(i: i32) -> None:
    el("li")
    key(i + 1)
    cls("tile")
    cls_if("pinned", editing == 1 and pin_of(i) >= 0)
    on_click(OPEN, i)
    view_icon(i)
    el("span")
    cls("name")
    text_ref(labels[i])
    end()
    if editing == 1:
        el("button")
        cls("info")
        attr("aria-label", "App info")
        on_click(INFO, i)
        text("i")
        end()
    end()


def view_dock() -> None:
    p: i32
    i: i32
    el("nav")
    cls("dock")
    for p in range(npin):
        i = app_of(p)
        if i >= 0:
            el("button")
            key(p + 1)
            cls("dock-app")
            attr_ref("aria-label", labels[i])
            on_click(OPEN, i)
            view_icon(i)
            end()
    end()


def small_btn(s: str, msg: i32) -> None:
    btn_open("ghost", msg, 0)
    cls("sm")
    text(s)
    end()


def view() -> None:
    i: i32
    shown: i32 = 0
    div("home")
    cls_if("editing", editing == 1)

    el("header")
    cls("clock")
    div("time")
    text_ref(now)
    end()
    div("date")
    text_ref(today)
    end()
    end()

    div("search")
    input_open("Search apps", query, QUERY)
    attr("type", "search")
    attr("enterkeyhint", "go")
    on_enter(OPEN_FIRST, 0)
    on_escape(CLEAR, 0)
    end()
    end()

    if s_len(err) > 0:
        alert("Could not list apps", "", "destructive")

    el("ul")
    cls("grid")
    for i in range(n):
        if matches(i):
            view_tile(i)
            shown += 1
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

    if editing == 1:
        el("p")
        cls("hint")
        text("Tap apps to pin them to the dock (up to 5).")
        end()

    div("bar")
    if s_len(query) == 0:
        view_dock()
    div("actions")
    btn_open("ghost", EDIT, 0)
    cls("sm")
    if editing == 1:
        text("Done")
    else:
        text("Edit dock")
    end()
    small_btn("Wallpaper", WALLPAPER)
    small_btn("Settings", SETTINGS)
    end()
    end()

    end()
