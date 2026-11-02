// End-to-end test of the QML launcher against the desktop backend's demo data
// (qt/demo): it drives the window with synthetic touches the way a finger would.
//   cmake -S qt -B build && cmake --build build && QT_QPA_PLATFORM=offscreen build/ultrahome_e2e
// ULTRA_SHOTS=dir also saves a screenshot at each step.

#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QtQml/qqmlextensionplugin.h>
#include <QQmlContext>
#include <QQmlExpression>
#include <QQuickItem>
#include <QQuickWindow>
#include <QSettings>
#include <QStandardPaths>
#include <QTest>

#include "backend.h"
#include "iconprovider.h"

namespace {
QQuickWindow *win = nullptr;
int failures = 0;
int shot = 0;

QVariant js(const QString &code) {
    QQmlExpression e(qmlContext(win), win, code);
    QVariant v = e.evaluate();
    if (e.hasError()) {
        qWarning().noquote() << "JS error in" << code << ":" << e.error().toString();
        ++failures;
    }
    if (v.canConvert<QJSValue>()) v = v.value<QJSValue>().toVariant();
    return v;
}

void check(bool ok, const QString &what) {
    if (!ok) {
        ++failures;
        qWarning().noquote() << "FAIL:" << what;
    } else {
        qInfo().noquote() << "ok  " << what;
    }
}

void eq(const QVariant &got, const QVariant &want, const QString &what) {
    const bool ok = got == want || got.toString() == want.toString();
    check(ok, what + (ok ? QString() : QStringLiteral(" (got %1, want %2)").arg(got.toString(), want.toString())));
}

void settle(int ms = 60) { QTest::qWait(ms); }

void snap(const char *name) {
    const QByteArray dir = qgetenv("ULTRA_SHOTS");
    if (dir.isEmpty()) return;
    win->grabWindow().save(QStringLiteral("%1/%2-%3.png").arg(QString::fromLocal8Bit(dir)).arg(++shot, 2, 10, QLatin1Char('0')).arg(name));
}

QPointF center(const QString &rectJs) {
    const QVariantMap r = js(rectJs).toMap();
    return {r.value("x").toReal() + r.value("w").toReal() / 2, r.value("y").toReal() + r.value("h").toReal() / 2};
}

// The window rect of home slot i.
QString slotRect(int i) {
    return QStringLiteral("(() => { const b = boxOf(%1); return {x: gridX + b.x, y: gridTop + b.y - scrollY, w: b.w, h: b.h}; })()").arg(i);
}

QPoint pt(QPointF p) { return p.toPoint(); }

void press(QPointF p) { QTest::mousePress(win, Qt::LeftButton, {}, pt(p)); }
void release(QPointF p) { QTest::mouseRelease(win, Qt::LeftButton, {}, pt(p)); }
void moveTo(QPointF from, QPointF to, int steps = 8) {
    for (int i = 1; i <= steps; ++i) {
        QTest::mouseMove(win, pt(from + (to - from) * (qreal(i) / steps)));
        QTest::qWait(10);
    }
}
void tap(QPointF p) { press(p); settle(30); release(p); settle(); }
void hold(QPointF p) { press(p); QTest::qWait(560); }
void swipe(QPointF from, QPointF to) { press(from); moveTo(from, to, 6); release(to); settle(300); }

QPointF itemCenter(const char *objectName) {
    auto *it = win->findChild<QQuickItem *>(QString::fromLatin1(objectName));
    if (!it) { qWarning() << "no item" << objectName; ++failures; return {}; }
    return it->mapToScene(QPointF(it->width() / 2, it->height() / 2));
}

QString homeLabels() {
    return js("home.map(s => s.kind === 'widget' ? '[' + s.str + ']' : apps[appRow[s.str]].label).join(',')").toString();
}

// Tap a sheet's button by its text.
void sheetButton(const QString &text) {
    QQuickItem *found = nullptr;
    const auto items = win->findChildren<QQuickItem *>();
    for (QQuickItem *it : items) {
        if (QString::fromLatin1(it->metaObject()->className()).startsWith("SheetButton") && it->isVisible()
            && it->property("text").toString() == text && it->property("enabled").toBool()) {
            // only buttons in an open sheet
            QQuickItem *p = it->parentItem();
            bool open = false;
            while (p) {
                if (p->property("open").isValid()) { open = p->property("open").toBool(); break; }
                p = p->parentItem();
            }
            if (open) { found = it; break; }
        }
    }
    if (!found) {
        qWarning().noquote() << "no sheet button" << text;
        for (QQuickItem *it : items)
            if (it->property("text").isValid() && it->isVisible())
                qWarning() << "  " << it->metaObject()->className() << it->property("text").toString();
        ++failures;
        return;
    }
    settle(250); // let the sheet rise
    tap(found->mapToScene(QPointF(found->width() / 2, found->height() / 2)));
}

QStringList sheetButtons() {
    QStringList out;
    const auto items = win->findChildren<QQuickItem *>();
    for (QQuickItem *it : items) {
        if (!QString::fromLatin1(it->metaObject()->className()).startsWith("SheetButton") || !it->isVisible()) continue;
        QQuickItem *p = it->parentItem();
        while (p && !p->property("open").isValid()) p = p->parentItem();
        if (p && p->property("open").toBool()) out << it->property("text").toString();
    }
    return out;
}
}  // namespace

Q_IMPORT_QML_PLUGIN(LauncherPlugin)

int main(int argc, char *argv[]) {
    QStandardPaths::setTestModeEnabled(true);
    QGuiApplication app(argc, argv);
    QGuiApplication::setOrganizationName(QStringLiteral("ultra-test"));
    QGuiApplication::setApplicationName(QStringLiteral("home-e2e"));
    QSettings().clear();

    Backend backend;
    QQmlApplicationEngine engine;
    engine.addImageProvider(QStringLiteral("icon"), new IconProvider);
    qmlRegisterSingletonInstance("Launcher.Host", 1, 0, "Host", &backend);
    engine.loadFromModule("Launcher", "Main");
    if (engine.rootObjects().isEmpty()) return 2;
    win = qobject_cast<QQuickWindow *>(engine.rootObjects().first());
    win->resize(412, 892);
    win->show();
    if (!QTest::qWaitForWindowExposed(win)) return 2;
    settle(300);
    snap("blank");

    const QPointF blank(206, 700);
    eq(js("home.length"), 0, "home starts blank");
    check(js("apps.length").toInt() > 5, "apps load from the demo list");

    // the home menu's update check goes to the host and closes the menu
    hold(blank); release(blank); settle(300);
    eq(js("sheet"), "menu", "a long press on the blank home opens its menu");
    snap("menu");
    sheetButton("Check for updates");
    eq(js("notice"), "Check for updates", "update check goes to the host");
    eq(js("sheet"), "", "the menu closes");

    // add apps through the drawer's pick mode
    hold(blank); release(blank); settle(300);
    sheetButton("Add apps");
    settle(300);
    check(js("drawerOpen && picking").toBool(), "Add apps opens the drawer to pick");
    for (const char *name : {"Chrome", "Gmail", "Phone"}) {
        js(QStringLiteral("drawer.query = '%1'").arg(name));
        settle(100);
        auto *grid = win->findChild<QQuickItem *>("drawerGrid");
        tap(grid->mapToScene(QPointF(grid->width() / 8, 42)));
        js("drawer.query = ''");
    }
    snap("picking");
    eq(js("home.length"), 3, "three apps picked");
    js("closeDrawer()");
    settle(300);
    eq(homeLabels(), "Chrome,Gmail,Phone", "picked apps are on home in order");
    snap("three-apps");

    // add a widget: long press on blank home, below the apps; search the picker
    hold(blank); release(blank); settle(300);
    sheetButton("Add widget");
    settle(200);
    eq(js("sheet"), "widgets", "the widget picker opens");
    js("widgetSearch.text = 'cal'");
    eq(js("shownProviders.map(i => providers[i].label).join(',')"), "Calendar", "search the picker by widget name");
    js("widgetSearch.text = 'weather'");
    eq(js("shownProviders.map(i => providers[i].label).join(',')"), "Weather", "or by its app");
    js("widgetSearch.text = 'zzz'");
    snap("picker-empty");
    js("widgetSearch.text = 'clock'");
    js("chooseWidget(shownProviders[0])");
    settle(100);
    eq(homeLabels(), "Chrome,Gmail,Phone,[Clock]", "the widget is added after the apps");
    eq(js("home[3].wid"), 100, "with the host's widget id");
    snap("widget");

    // hold a home app and drag it over another: reorder
    hold(center(slotRect(0)));
    eq(js("lifted"), 0, "a long press lifts the slot");
    moveTo(center(slotRect(0)), center(slotRect(1)));
    release(center(slotRect(1)));
    settle();
    eq(homeLabels(), "Gmail,Chrome,Phone,[Clock]", "dragging over the next app swaps them");
    eq(js("sheet"), "", "a drag doesn't open the menu");
    eq(js("lifted"), -1, "letting go drops it");

    // hold without moving: the menu, and Move later
    hold(center(slotRect(0))); release(center(slotRect(0))); settle(300);
    eq(js("sheet"), "item", "holding an app without moving opens its menu");
    snap("item-menu");
    sheetButton("Move later");
    eq(homeLabels(), "Chrome,Gmail,Phone,[Clock]", "Move later");

    // the grid runs down the screen; an app dropped in an empty cell there stays in it
    check(js("nrows").toInt() >= 8, QStringLiteral("rows fill the screen (%1)").arg(js("nrows").toInt()));
    {
        const QPointF from = center(slotRect(1));
        const QPointF to = from + QPointF(10, 6 * 72);
        hold(from);
        moveTo(from, to, 1); // a quick move, straight past the widget
        release(to);
        settle();
        eq(js("[home[1].x, home[1].y].join(',')"), "1,6", "Gmail is left six rows down");
        eq(js("packed.cells[0].col"), 0, "Chrome stays in the first cell");
        eq(js("packed.cells[2].col"), 1, "Phone flows up into the cell Gmail left");
        snap("pinned");
        // dragging it back over an app puts it back in the packed order
        const QPointF back = center(slotRect(0));
        hold(to);
        moveTo(to, back);
        release(back);
        settle();
        eq(homeLabels(), "Gmail,Chrome,Phone,[Clock]", "dragged back over Chrome, it takes its place");
        eq(js("home[0].x"), -1, "and is in the flow again");
    }

    // swipe down opens the drawer; search; Enter opens the first match
    swipe(QPointF(206, 400), QPointF(206, 600));
    check(js("drawerOpen").toBool(), "swipe down opens the drawer");
    eq(js("liveSlots.length"), 0, "no live widget slots under the drawer");
    snap("drawer");
    js("drawer.query = 'ma'");
    eq(js("drawer.shown.map(i => apps[i].label).join(',')"), "Gmail,Maps", "the search filters the list");
    {
        // the clear button empties the search and shows every app again
        settle(100);
        snap("search");
        auto *clear = win->findChild<QQuickItem *>("searchClear");
        check(clear && clear->isVisible(), "the search shows a clear button");
        if (clear) tap(clear->mapToScene(QPointF(clear->width() / 2, clear->height() / 2)));
        eq(js("drawer.query"), "", "tapping it clears the search");
        eq(js("drawer.shown.length === apps.length"), true, "and every app shows again");
        check(clear && !clear->isVisible(), "the clear button goes");
        js("drawer.query = 'ma'");
    }
    js("drawer.openFirst()");
    settle(300);
    eq(js("notice"), "Open com.example.gmail/.MainActivity", "opening the first match launches it");
    check(!js("drawerOpen").toBool(), "launching closes the drawer");

    // the drawer's long-press menu: Uninstall, not for system apps
    swipe(QPointF(206, 400), QPointF(206, 600));
    js("drawer.query = 'calc'");
    settle(100);
    {
        auto *grid = win->findChild<QQuickItem *>("drawerGrid");
        const QPointF p = grid->mapToScene(QPointF(grid->width() / 8, 42));
        hold(p); release(p); settle(300);
    }
    eq(sheetButtons().join(','), "Add to home,App info,Uninstall", "an app's menu in the drawer");
    snap("app-menu");
    sheetButton("Uninstall");
    eq(js("notice"), "Uninstall com.example.calculator/.MainActivity", "Uninstall goes to the host");
    check(js("sheet === '' && drawerOpen").toBool(), "uninstalling closes the menu, not the drawer");
    js("appSheetRow = drawer.shown[0]; sheet = 'app'");
    settle(250);
    sheetButton("Add to home");
    eq(js("notice"), "Added to home", "Add to home says so");
    js("back()");
    settle(300);
    eq(homeLabels(), "Gmail,Chrome,Phone,[Clock],Calculator", "added at the end");
    // the uninstalled app leaves home once it is gone from the list
    js("apps = apps.filter(a => a.label !== 'Calculator'); const r = {}; apps.forEach((a, i) => r[a.component] = i); appRow = r; dropGone()");
    eq(homeLabels(), "Gmail,Chrome,Phone,[Clock]", "uninstalled apps leave home");

    // tap launches
    tap(center(slotRect(0)));
    eq(js("notice"), "Open com.example.gmail/.MainActivity", "tapping a home app opens it");

    // hold a widget without moving: resize handles; drag them to snap it to the grid
    hold(center(slotRect(3))); release(center(slotRect(3))); settle(200);
    eq(js("resizing"), 3, "a widget gets handles, not the menu");
    eq(js("sheet"), "", "and no menu");
    snap("resizing");
    {
        auto *rb = win->findChild<QQuickItem *>("handleRB");
        const QPointF from = rb->mapToScene(QPointF(rb->width() / 2, rb->height() / 2));
        const QVariantMap r = js(slotRect(3)).toMap();
        const qreal colW = js("colW").toReal();
        const QPointF to(r.value("x").toReal() + colW * 2.1, r.value("y").toReal() + 140);
        press(from);
        moveTo(from, to);
        release(to);
        settle();
        eq(js("[home[3].w, home[3].rows].join(',')"), "2,2", "snapped to two columns, two rows");
        eq(js("resizing"), 3, "letting go of a handle keeps the handles");
        snap("resized");
    }
    // while it shows handles, dragging the widget itself moves it, without another long press
    {
        const QPointF from = center(slotRect(3));
        const QVariantMap r = js(slotRect(3)).toMap();
        const QPointF grabAt(r.value("x").toReal() + 20, r.value("y").toReal() + 20);
        const QPointF to(js("gridX").toReal() + 20, js("gridTop").toReal() + 20);
        Q_UNUSED(from);
        press(grabAt);
        settle(30);
        eq(js("lifted"), 3, "pressing a widget with handles picks it up");
        moveTo(grabAt, to);
        release(to);
        settle();
        eq(js("[home[3].x, home[3].y].join(',')"), "0,0", "dragged onto the top row, first cell");
        eq(js("packed.cells.filter((c, i) => i !== 3 && c.row < 2).length"), 3, "the apps fill the cells beside it");
        eq(js("resizing"), 3, "it keeps its handles after the move");
        snap("moved-widget");
    }
    // Done, then Remove from its menu
    js("resizing = -1");
    hold(center(slotRect(3)));
    eq(js("lifted"), 3, "hold lifts the widget");
    release(center(slotRect(3)));
    settle(100);
    eq(js("resizing"), 3, "and letting go shows the handles");
    js("removeSlot(resizing); resizing = -1");
    eq(homeLabels(), "Gmail,Chrome,Phone", "the widget is removed");

    // saved, and loaded again
    js("loadHome()");
    eq(homeLabels(), "Gmail,Chrome,Phone", "the layout is saved");

    // back closes things in order; home closes everything
    swipe(QPointF(206, 400), QPointF(206, 600));
    js("back()");
    settle(300);
    check(!js("drawerOpen").toBool(), "back closes the drawer");
    snap("end");

    if (failures) qWarning() << failures << "failures";
    else qInfo() << "e2e ok";
    return failures ? 1 : 0;
}
