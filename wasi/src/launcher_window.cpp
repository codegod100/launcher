#include "launcher_window.h"

#include "gui.h"

#include <QDir>
#include <QFile>
#include <QGuiApplication>
#include <QInputMethod>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QKeyEvent>
#include <QMouseEvent>
#include <QPainter>
#include <QPainterPath>
#include <QWheelEvent>

#include <algorithm>
#include <cmath>
#include <cstring>

namespace {

gui_string_t toWitStr(const QString &s) {
    QByteArray utf8 = s.toUtf8();
    gui_string_t out;
    gui_string_dup(&out, utf8.constData());
    return out;
}

QString fromWitStr(const gui_string_t &s) {
    return QString::fromUtf8(reinterpret_cast<const char *>(s.ptr), int(s.len));
}

QByteArray fromWitBytes(const gui_string_t &s) {
    return QByteArray(reinterpret_cast<const char *>(s.ptr), int(s.len));
}

} // namespace

LauncherWindow::LauncherWindow() {
    setTitle(QStringLiteral("ultra Home"));
    QSurfaceFormat fmt = format();
    fmt.setAlphaBufferSize(8);
    setFormat(fmt);
    resize(412, 892);

    refreshInsets();
    refreshApps();
    loadHome();
    if (m_home.isEmpty() && !QFile::exists(QStringLiteral("/data/home.json"))) {
        seedDefaultHome();
    }
    m_pollTimer.start(100, this);
}

void LauncherWindow::refreshInsets() {
    gui_tuple2_u32_u32_t ins{};
    ultra_launcher_android_insets(&ins);
    if (int(ins.f0) != m_physInsetTop || int(ins.f1) != m_physInsetBottom) {
        m_physInsetTop = int(ins.f0);
        m_physInsetBottom = int(ins.f1);
        update();
    }
}

void LauncherWindow::refreshApps() {
    gui_string_t raw{};
    ultra_launcher_android_apps(&raw);
    QByteArray json = fromWitBytes(raw);
    gui_string_free(&raw);

    QJsonDocument doc = QJsonDocument::fromJson(json);
    QJsonArray arr = doc.object().value(QStringLiteral("apps")).toArray();

    m_apps.clear();
    m_appIndex.clear();
    m_apps.reserve(arr.size());
    for (const QJsonValue &v : arr) {
        QJsonObject o = v.toObject();
        AppInfo info;
        info.label = o.value(QStringLiteral("label")).toString();
        info.component = o.value(QStringLiteral("component")).toString();
        info.system = o.value(QStringLiteral("system")).toBool(false);
        if (!info.component.isEmpty()) {
            m_appIndex.insert(info.component, m_apps.size());
            m_apps.append(info);
        }
    }
    update();
}

void LauncherWindow::refreshWidgetProviders() {
    gui_string_t raw{};
    ultra_launcher_android_widget_providers(&raw);
    QByteArray json = fromWitBytes(raw);
    gui_string_free(&raw);

    m_providers.clear();
    QJsonDocument doc = QJsonDocument::fromJson(json);
    for (const QJsonValue &v : doc.array()) {
        QJsonObject o = v.toObject();
        WidgetProviderInfo info;
        info.provider = o.value(QStringLiteral("provider")).toString();
        info.label = o.value(QStringLiteral("label")).toString();
        info.w = std::max(1, o.value(QStringLiteral("w")).toInt(2));
        info.h = std::max(1, o.value(QStringLiteral("h")).toInt(2));
        if (!info.provider.isEmpty()) m_providers.append(info);
    }
}

void LauncherWindow::loadHome() {
    QFile f(QStringLiteral("/data/home.json"));
    if (!f.open(QIODevice::ReadOnly)) return;
    QJsonDocument doc = QJsonDocument::fromJson(f.readAll());
    if (!doc.isArray()) return;
    m_home.clear();
    for (const QJsonValue &v : doc.array()) {
        QJsonObject o = v.toObject();
        const QString kind = o.value(QStringLiteral("kind")).toString(QStringLiteral("app"));
        Layout::HomeSlot slot;
        slot.x = o.value(QStringLiteral("x")).toInt(-1);
        slot.y = o.value(QStringLiteral("y")).toInt(-1);
        if (kind == QLatin1String("widget")) {
            slot.widgetId = o.value(QStringLiteral("id")).toInt(-1);
            slot.provider = o.value(QStringLiteral("str")).toString();
            slot.w = std::max(1, o.value(QStringLiteral("w")).toInt(2));
            slot.h = std::max(1, o.value(QStringLiteral("h")).toInt(2));
            if (slot.widgetId < 0) continue;
        } else {
            slot.component = o.value(QStringLiteral("str")).toString();
            if (slot.component.isEmpty()) continue;
        }
        m_home.append(slot);
        if (m_home.size() >= 200) break;
    }
}

void LauncherWindow::seedDefaultHome() {
    struct SeedApp { const char *label; int x; int y; int nth; };
    static const SeedApp kSeeds[] = {
        {"Maps", 0, 0, 0},
        {"Muse", 1, 0, 0},
        {"Claude", 2, 0, 0},
        {"Claude", 3, 0, 0},
        {"Transit", 4, 0, 0},
        {"Chrome", 0, 1, 0},
        {"Coinbase", 1, 1, 0},
        {"Cash App", 2, 1, 0},
        {"Webby", 3, 1, 0},
        {"Amazon Shopping", 4, 1, 0},
        {"Bluesky", 0, 2, 0},
        {"HEY Calendar", 1, 2, 0},
        {"fanchat", 2, 2, 0},
        {"Files", 0, 3, 0},
    };
    for (const SeedApp &seed : kSeeds) {
        const QString want = QString::fromUtf8(seed.label);
        QString comp;
        for (const AppInfo &a : m_apps) {
            if (a.label.compare(want, Qt::CaseInsensitive) == 0) {
                comp = a.component;
                break;
            }
        }
        if (comp.isEmpty()) {
            for (const AppInfo &a : m_apps) {
                if (a.label.startsWith(want, Qt::CaseInsensitive)) {
                    comp = a.component;
                    break;
                }
            }
        }
        if (!comp.isEmpty()) {
            Layout::HomeSlot s;
            s.component = comp;
            s.x = seed.x;
            s.y = seed.y;
            m_home.append(s);
        }
    }
    saveHome();

    // Also request binding the two widgets from the original home screen.
    const char *kDefaultWidgets[] = {
        "com.google.android.apps.weather/com.google.android.apps.weather.widget.CurrentForecastWidgetReceiver",
        "com.probadosoft.moonphasecalendar/com.probadosoft.moonphasecalendar.AppWidget",
    };
    for (const char *p : kDefaultWidgets) {
        gui_string_t witP = toWitStr(QString::fromUtf8(p));
        ultra_launcher_android_add_widget(&witP);
        gui_string_free(&witP);
    }
}

void LauncherWindow::saveHome() const {
    QDir().mkpath(QStringLiteral("/data"));
    QJsonArray arr;
    for (const auto &s : m_home) {
        QJsonObject o;
        if (s.isWidget()) {
            o.insert(QStringLiteral("kind"), QStringLiteral("widget"));
            o.insert(QStringLiteral("id"), s.widgetId);
            o.insert(QStringLiteral("str"), s.provider);
            o.insert(QStringLiteral("w"), s.w);
            o.insert(QStringLiteral("h"), s.h);
        } else {
            o.insert(QStringLiteral("kind"), QStringLiteral("app"));
            o.insert(QStringLiteral("str"), s.component);
        }
        o.insert(QStringLiteral("x"), s.x);
        o.insert(QStringLiteral("y"), s.y);
        arr.append(o);
    }
    QFile f(QStringLiteral("/data/home.json"));
    if (f.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        f.write(QJsonDocument(arr).toJson(QJsonDocument::Compact));
    }
}

QImage LauncherWindow::iconFor(const QString &component, int px) {
    const QString cacheKey = component + QLatin1Char('@') + QString::number(px);
    auto it = m_icons.constFind(cacheKey);
    if (it != m_icons.constEnd()) return it.value();
    if (m_missingIcons.contains(cacheKey)) return {};

    gui_string_t witKey = toWitStr(QStringLiteral("a:") + component);
    gui_string_t b64{};
    bool ok = ultra_launcher_android_icon(&witKey, uint32_t(px), &b64);
    gui_string_free(&witKey);
    if (!ok) {
        m_missingIcons.insert(cacheKey);
        return {};
    }

    QByteArray raw = QByteArray::fromBase64(fromWitBytes(b64));
    gui_string_free(&b64);
    const int side = int(std::lround(std::sqrt(raw.size() / 4.0)));
    if (side <= 0 || side * side * 4 != raw.size()) {
        m_missingIcons.insert(cacheKey);
        return {};
    }
    QImage img(side, side, QImage::Format_RGBA8888_Premultiplied);
    std::memcpy(img.bits(), raw.constData(), size_t(raw.size()));
    m_icons.insert(cacheKey, img);
    return img;
}

void LauncherWindow::pollHostEvents() {
    gui_list_string_t evs{};
    ultra_launcher_android_poll_events(&evs);
    bool changedApps = false;
    for (size_t i = 0; i < evs.len; ++i) {
        const QString ev = fromWitStr(evs.ptr[i]);
        if (ev == QLatin1String("home")) {
            closeSheet();
            closeDrawer();
            m_liftedHome = -1;
            m_homeScroll = 0.0;
            update();
        } else if (ev == QLatin1String("back")) {
            if (m_sheet != SheetKind::None) {
                closeSheet();
            } else if (m_drawerOpen) {
                closeDrawer();
            } else if (m_liftedHome >= 0) {
                m_liftedHome = -1;
                update();
            }
        } else if (ev == QLatin1String("insets")) {
            refreshInsets();
        } else if (ev.startsWith(QLatin1String("apps-changed:"))) {
            const QString pkgs = ev.mid(13);
            if (pkgs.isEmpty()) {
                m_icons.clear();
                m_missingIcons.clear();
            } else {
                const QStringList list = pkgs.split(QLatin1Char(','), Qt::SkipEmptyParts);
                for (const QString &p : list) {
                    const QString prefix = p + QLatin1Char('/');
                    for (auto it = m_icons.begin(); it != m_icons.end();) {
                        if (it.key().startsWith(prefix)) it = m_icons.erase(it);
                        else ++it;
                    }
                    for (auto it = m_missingIcons.begin(); it != m_missingIcons.end();) {
                        if (it->startsWith(prefix)) it = m_missingIcons.erase(it);
                        else ++it;
                    }
                }
            }
            changedApps = true;
        } else if (ev.startsWith(QLatin1String("widget-added:"))) {
            const QString rest = ev.mid(13);
            const int sep = rest.indexOf(QLatin1Char(':'));
            const int id = (sep >= 0 ? rest.left(sep) : rest).toInt();
            const QString prov = sep >= 0 ? rest.mid(sep + 1) : QString();
            if (id >= 0) {
                Layout::HomeSlot ws;
                ws.widgetId = id;
                ws.provider = prov;
                ws.w = 2;
                ws.h = 2;
                if (prov.contains(QLatin1String("CurrentForecastWidgetReceiver"))) {
                    ws.x = 3;
                    ws.y = 2;
                } else if (prov.contains(QLatin1String("moonphasecalendar"))) {
                    ws.x = 0;
                    ws.y = 5;
                }
                m_home.append(ws);
                saveHome();
                update();
            }
        }
    }
    gui_list_string_free(&evs);
    if (changedApps) refreshApps();
}

// ---- Geometry ----

qreal LauncherWindow::insetTop() const { return m_physInsetTop / devicePixelRatio(); }
qreal LauncherWindow::insetBottom() const { return m_physInsetBottom / devicePixelRatio(); }
qreal LauncherWindow::contentW() const { return std::min<qreal>(width(), 560.0); }
qreal LauncherWindow::contentX() const { return (width() - contentW()) / 2.0; }
qreal LauncherWindow::gridX() const { return contentX() + 16.0; }
qreal LauncherWindow::gridW() const { return contentW() - 32.0; }
int LauncherWindow::homeCols() const { return width() >= 480 ? 6 : 5; }
qreal LauncherWindow::homeColW() const {
    const int cols = homeCols();
    return (gridW() - (cols - 1) * Layout::kColGap) / cols;
}
qreal LauncherWindow::panelY() const { return insetTop() + 16.0; }
qreal LauncherWindow::gridTop() const { return panelY() + 54.0 + 16.0; }

QRectF LauncherWindow::appsPillRect() const {
    return QRectF(gridX(), panelY(), gridW(), 54.0);
}

QRectF LauncherWindow::drawerSearchRect() const {
    const qreal closeW = 72.0;
    return QRectF(gridX(), insetTop() + 14.0, gridW() - closeW - 10.0, 48.0);
}

QRectF LauncherWindow::drawerCloseRect() const {
    const QRectF s = drawerSearchRect();
    return QRectF(s.right() + 10.0, s.top(), 72.0, s.height());
}

QRectF LauncherWindow::drawerClearRect() const {
    const QRectF s = drawerSearchRect();
    return QRectF(s.right() - 40.0, s.top() + 4.0, 36.0, 40.0);
}

QVector<AppInfo> LauncherWindow::filteredApps() const {
    if (m_search.trimmed().isEmpty()) return m_apps;
    const QString q = m_search.trimmed();
    QVector<AppInfo> out;
    for (const auto &a : m_apps) {
        if (a.label.contains(q, Qt::CaseInsensitive)) out.append(a);
    }
    return out;
}

int LauncherWindow::hitHomeSlot(const QPointF &pos) const {
    QSet<QString> hidden;
    const auto packed = Layout::packHome(m_home, homeCols(), hidden);
    const qreal cw = homeColW();
    for (int i = 0; i < packed.cells.size(); ++i) {
        if (packed.cells[i].col < 0 || m_home[i].isWidget()) continue;
        QRectF r = Layout::cellBox(packed.cells[i], cw);
        r.translate(gridX(), gridTop() - m_homeScroll);
        if (r.contains(pos)) return i;
    }
    return -1;
}

int LauncherWindow::hitDrawerApp(const QPointF &pos) const {
    const QVector<AppInfo> list = filteredApps();
    const int cols = width() >= 480 ? 5 : 4;
    const qreal cw = (gridW() - (cols - 1) * Layout::kColGap) / cols;
    const qreal top = drawerSearchRect().bottom() + 16.0;
    if (pos.y() < top) return -1;
    for (int i = 0; i < list.size(); ++i) {
        const int col = i % cols;
        const int row = i / cols;
        const QRectF r(gridX() + col * (cw + Layout::kColGap),
                       top + row * (Layout::kCellH + Layout::kRowGap) - m_drawerScroll,
                       cw, Layout::kCellH);
        if (r.contains(pos)) return i;
    }
    return -1;
}

int LauncherWindow::hitSheetButton(const QPointF &pos) const {
    const int count = (m_sheet == SheetKind::Menu) ? 3
                    : (m_sheet == SheetKind::HomeItem) ? 4
                    : (m_sheet == SheetKind::DrawerApp) ? 3 : 0;
    if (count == 0) return -1;
    const qreal btnH = 52.0;
    const qreal pad = 16.0;
    const qreal sheetH = pad * 2 + count * btnH + (count - 1) * 8.0 + insetBottom();
    const qreal sheetY = height() - sheetH;
    if (pos.y() < sheetY) return -2; // outside sheet
    for (int i = 0; i < count; ++i) {
        const QRectF r(gridX(), sheetY + pad + i * (btnH + 8.0), gridW(), btnH);
        if (r.contains(pos)) return i;
    }
    return -1;
}

// ---- Actions ----

void LauncherWindow::openDrawer() {
    m_drawerOpen = true;
    m_drawerScroll = 0.0;
    m_search.clear();
    m_searchFocused = false;
    update();
}

void LauncherWindow::closeDrawer() {
    m_drawerOpen = false;
    m_search.clear();
    m_searchFocused = false;
    if (auto *im = QGuiApplication::inputMethod()) im->hide();
    update();
}

void LauncherWindow::openSheet(SheetKind kind, int index) {
    m_sheet = kind;
    m_sheetIndex = index;
    ultra_launcher_android_haptic();
    update();
}

void LauncherWindow::closeSheet() {
    m_sheet = SheetKind::None;
    m_sheetIndex = -1;
    update();
}

void LauncherWindow::triggerSheetAction(int actionIdx) {
    if (m_sheet == SheetKind::Menu) {
        closeSheet();
        if (actionIdx == 0) openDrawer();
        else if (actionIdx == 1) ultra_launcher_android_pick_wallpaper();
        else if (actionIdx == 2) ultra_launcher_android_open_settings();
        return;
    }
    if (m_sheet == SheetKind::HomeItem && m_sheetIndex >= 0 && m_sheetIndex < m_home.size()) {
        const QString comp = m_home[m_sheetIndex].component;
        const int idx = m_sheetIndex;
        closeSheet();
        if (actionIdx == 0) {
            gui_string_t c = toWitStr(comp);
            ultra_launcher_android_launch(&c);
            gui_string_free(&c);
        } else if (actionIdx == 1) {
            gui_string_t c = toWitStr(comp);
            ultra_launcher_android_app_info(&c);
            gui_string_free(&c);
        } else if (actionIdx == 2) {
            m_home.removeAt(idx);
            saveHome();
            update();
        } else if (actionIdx == 3) {
            gui_string_t c = toWitStr(comp);
            ultra_launcher_android_uninstall(&c);
            gui_string_free(&c);
        }
        return;
    }
    if (m_sheet == SheetKind::DrawerApp) {
        const QVector<AppInfo> list = filteredApps();
        if (m_sheetIndex >= 0 && m_sheetIndex < list.size()) {
            const AppInfo app = list[m_sheetIndex];
            closeSheet();
            if (actionIdx == 0) {
                int existing = -1;
                for (int i = 0; i < m_home.size(); ++i) {
                    if (m_home[i].component == app.component) { existing = i; break; }
                }
                if (existing >= 0) {
                    m_home.removeAt(existing);
                } else if (m_home.size() < 200) {
                    Layout::HomeSlot slot;
                    slot.component = app.component;
                    m_home.append(slot);
                }
                saveHome();
                update();
            } else if (actionIdx == 1) {
                gui_string_t c = toWitStr(app.component);
                ultra_launcher_android_app_info(&c);
                gui_string_free(&c);
            } else if (actionIdx == 2 && !app.system) {
                gui_string_t c = toWitStr(app.component);
                ultra_launcher_android_uninstall(&c);
                gui_string_free(&c);
            }
        } else {
            closeSheet();
        }
    }
}

// ---- Painting ----

void LauncherWindow::paintEvent(QPaintEvent *) {
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    p.setRenderHint(QPainter::SmoothPixmapTransform);

    // Clear to transparent so the Android wallpaper shows behind the launcher.
    p.setCompositionMode(QPainter::CompositionMode_Source);
    p.fillRect(QRect(QPoint(0, 0), size()), Qt::transparent);
    p.setCompositionMode(QPainter::CompositionMode_SourceOver);

    const bool showWidgets = !m_drawerOpen && m_sheet == SheetKind::None;
    if (showWidgets != m_lastWidgetsVisible) {
        m_lastWidgetsVisible = showWidgets;
        ultra_launcher_android_set_widgets_visible(showWidgets);
    }

    if (m_drawerOpen) drawDrawer(p);
    else drawHome(p);

    if (m_sheet != SheetKind::None) drawSheet(p);
}

void LauncherWindow::drawIconAndLabel(QPainter &p, const QRectF &box, const QString &component,
                                      const QString &label, int iconSize, bool lifted) {
    const qreal scale = lifted ? 1.1 : 1.0;
    const qreal sz = iconSize * scale;
    const QRectF iconRect(box.center().x() - sz / 2.0, box.top() + 4.0, sz, sz);

    const int px = std::max(48, int(std::ceil(sz * devicePixelRatio())));
    const QImage img = iconFor(component, px);
    if (!img.isNull()) {
        p.drawImage(iconRect, img);
    } else {
        p.setPen(Qt::NoPen);
        p.setBrush(QColor(255, 255, 255, 46));
        p.drawEllipse(iconRect);
    }

    QFont f = p.font();
    f.setPixelSize(12);
    f.setWeight(QFont::Medium);
    p.setFont(f);
    const QRectF textRect(box.left() + 2.0, iconRect.bottom() + 5.0, box.width() - 4.0, 20.0);
    const QString elided = p.fontMetrics().elidedText(label, Qt::ElideRight, int(textRect.width()));
    p.setPen(QColor(0, 0, 0, 160));
    p.drawText(textRect.translated(0, 1), Qt::AlignHCenter | Qt::AlignTop, elided);
    p.setPen(Qt::white);
    p.drawText(textRect, Qt::AlignHCenter | Qt::AlignTop, elided);
}

void LauncherWindow::drawHome(QPainter &p) {
    // Top pill ("Windows" -> opens system Recents / window list)
    const QRectF pill = appsPillRect();
    p.setPen(QPen(QColor(255, 255, 255, 55), 1.0));
    p.setBrush(QColor(24, 24, 30, 135));
    p.drawRoundedRect(pill, 27.0, 27.0);

    QFont f = p.font();
    f.setPixelSize(16);
    f.setWeight(QFont::Medium);
    p.setFont(f);
    p.setPen(Qt::white);
    p.drawText(pill, Qt::AlignCenter, QStringLiteral("Windows"));

    if (m_home.isEmpty()) {
        f.setPixelSize(14);
        f.setWeight(QFont::Normal);
        p.setFont(f);
        p.setPen(QColor(255, 255, 255, 190));
        p.drawText(QRectF(gridX(), gridTop() + 40.0, gridW(), 60.0),
                   Qt::AlignHCenter | Qt::AlignTop | Qt::TextWordWrap,
                   QStringLiteral("Swipe down for Apps • Long press for menu"));
        return;
    }

    QSet<QString> hidden;
    const auto packed = Layout::packHome(m_home, homeCols(), hidden);
    const qreal cw = homeColW();
    const qreal dpr = devicePixelRatio();
    QJsonArray placedWidgets;
    for (int i = 0; i < m_home.size(); ++i) {
        if (i == m_liftedHome || packed.cells[i].col < 0) continue;
        const auto &slot = m_home[i];
        QRectF r = Layout::cellBox(packed.cells[i], cw, slot.w, slot.h);
        r.translate(gridX(), gridTop() - m_homeScroll);
        if (slot.isWidget()) {
            QJsonArray entry;
            entry.append(slot.widgetId);
            entry.append(int(std::lround(r.x() * dpr)));
            entry.append(int(std::lround(r.y() * dpr)));
            entry.append(int(std::lround(r.width() * dpr)));
            entry.append(int(std::lround(r.height() * dpr)));
            placedWidgets.append(entry);
            continue;
        }
        if (r.bottom() < gridTop() || r.top() > height()) continue;
        const int appIdx = m_appIndex.value(slot.component, -1);
        const QString label = appIdx >= 0 ? m_apps[appIdx].label : slot.component.section(QLatin1Char('/'), 0, 0);
        drawIconAndLabel(p, r, slot.component, label, 52, false);
    }
    const QString placedJson = QString::fromUtf8(QJsonDocument(placedWidgets).toJson(QJsonDocument::Compact));
    if (placedJson != m_lastPlacedWidgets) {
        m_lastPlacedWidgets = placedJson;
        gui_string_t witJson = toWitStr(placedJson);
        ultra_launcher_android_place_widgets(&witJson);
        gui_string_free(&witJson);
    }

    if (m_liftedHome >= 0 && m_liftedHome < m_home.size()) {
        const QString comp = m_home[m_liftedHome].component;
        const int appIdx = m_appIndex.value(comp, -1);
        const QString label = appIdx >= 0 ? m_apps[appIdx].label : comp;
        const QRectF r(m_liftPos.x() - cw / 2.0, m_liftPos.y() - Layout::kCellH / 2.0, cw, Layout::kCellH);
        drawIconAndLabel(p, r, comp, label, 52, true);
    }
}

void LauncherWindow::drawDrawer(QPainter &p) {
    p.fillRect(QRect(QPoint(0, 0), size()), QColor(14, 15, 20, 222));

    const QRectF search = drawerSearchRect();
    p.setPen(QPen(QColor(255, 255, 255, m_searchFocused ? 140 : 60), 1.0));
    p.setBrush(QColor(255, 255, 255, 24));
    p.drawRoundedRect(search, 24.0, 24.0);

    QFont f = p.font();
    f.setPixelSize(16);
    f.setWeight(QFont::Normal);
    p.setFont(f);
    p.setPen(m_search.isEmpty() ? QColor(255, 255, 255, 130) : Qt::white);
    p.drawText(search.adjusted(18, 0, -44, 0), Qt::AlignVCenter | Qt::AlignLeft,
               m_search.isEmpty() ? QStringLiteral("Search apps") : m_search);

    if (!m_search.isEmpty()) {
        p.setPen(QColor(255, 255, 255, 190));
        p.drawText(drawerClearRect(), Qt::AlignCenter, QStringLiteral("✕"));
    }

    const QRectF closeBtn = drawerCloseRect();
    p.setPen(Qt::white);
    f.setWeight(QFont::Medium);
    p.setFont(f);
    p.drawText(closeBtn, Qt::AlignCenter, QStringLiteral("Close"));

    const QVector<AppInfo> list = filteredApps();
    const int cols = width() >= 480 ? 5 : 4;
    const qreal cw = (gridW() - (cols - 1) * Layout::kColGap) / cols;
    const qreal top = search.bottom() + 16.0;

    p.save();
    p.setClipRect(QRectF(0, top, width(), height() - top));
    for (int i = 0; i < list.size(); ++i) {
        const int col = i % cols;
        const int row = i / cols;
        const QRectF r(gridX() + col * (cw + Layout::kColGap),
                       top + row * (Layout::kCellH + Layout::kRowGap) - m_drawerScroll,
                       cw, Layout::kCellH);
        if (r.bottom() < top || r.top() > height()) continue;
        drawIconAndLabel(p, r, list[i].component, list[i].label, 52, false);
    }
    p.restore();
}

void LauncherWindow::drawSheet(QPainter &p) {
    p.fillRect(QRect(QPoint(0, 0), size()), QColor(0, 0, 0, 140));

    QStringList items;
    if (m_sheet == SheetKind::Menu) {
        items = {QStringLiteral("All apps"), QStringLiteral("Wallpaper"), QStringLiteral("Settings")};
    } else if (m_sheet == SheetKind::HomeItem) {
        items = {QStringLiteral("Open"), QStringLiteral("App info"), QStringLiteral("Remove from home"), QStringLiteral("Uninstall")};
    } else if (m_sheet == SheetKind::DrawerApp) {
        const QVector<AppInfo> list = filteredApps();
        bool onHome = false;
        bool isSystem = false;
        if (m_sheetIndex >= 0 && m_sheetIndex < list.size()) {
            isSystem = list[m_sheetIndex].system;
            for (const auto &s : m_home) {
                if (s.component == list[m_sheetIndex].component) { onHome = true; break; }
            }
        }
        items << (onHome ? QStringLiteral("Remove from home") : QStringLiteral("Add to home"));
        items << QStringLiteral("App info");
        items << (isSystem ? QStringLiteral("System app") : QStringLiteral("Uninstall"));
    }

    const qreal btnH = 52.0;
    const qreal pad = 16.0;
    const qreal sheetH = pad * 2 + items.size() * btnH + std::max(0, int(items.size()) - 1) * 8.0 + insetBottom();
    const QRectF panel(contentX(), height() - sheetH, contentW(), sheetH);

    p.setPen(Qt::NoPen);
    p.setBrush(QColor(28, 29, 36, 245));
    p.drawRoundedRect(panel.adjusted(0, 0, 0, 24), 24.0, 24.0);

    QFont f = p.font();
    f.setPixelSize(16);
    f.setWeight(QFont::Medium);
    p.setFont(f);

    for (int i = 0; i < items.size(); ++i) {
        const QRectF r(gridX(), panel.top() + pad + i * (btnH + 8.0), gridW(), btnH);
        p.setBrush(QColor(255, 255, 255, 22));
        p.drawRoundedRect(r, 14.0, 14.0);
        p.setPen(Qt::white);
        p.drawText(r, Qt::AlignCenter, items[i]);
        p.setPen(Qt::NoPen);
    }
}

// ---- Input & Timers ----

void LauncherWindow::resizeEvent(QResizeEvent *event) {
    QRasterWindow::resizeEvent(event);
    refreshInsets();
    update();
}

void LauncherWindow::mousePressEvent(QMouseEvent *event) {
    if (event->button() != Qt::LeftButton) return;
    m_pressed = true;
    m_dragging = false;
    m_longPressed = false;
    m_pressPos = event->position();
    m_lastPos = m_pressPos;
    m_pressTimer.restart();

    if (m_sheet != SheetKind::None) return;

    if (m_drawerOpen) {
        m_pressedDrawer = hitDrawerApp(m_pressPos);
        if (m_pressedDrawer >= 0) m_longPressTimer.start(450, this);
    } else {
        m_pressedHome = hitHomeSlot(m_pressPos);
        if (!appsPillRect().contains(m_pressPos)) {
            m_longPressTimer.start(450, this);
        }
    }
}

void LauncherWindow::mouseMoveEvent(QMouseEvent *event) {
    if (!m_pressed) return;
    const QPointF pos = event->position();
    const qreal dist = std::hypot(pos.x() - m_pressPos.x(), pos.y() - m_pressPos.y());
    if (dist > 10.0 && !m_dragging) {
        m_dragging = true;
        m_longPressTimer.stop();
    }

    if (m_liftedHome >= 0) {
        m_liftPos = pos;
        update();
        return;
    }

    if (m_dragging && m_drawerOpen) {
        const qreal dy = m_lastPos.y() - pos.y();
        const QVector<AppInfo> list = filteredApps();
        const int cols = width() >= 480 ? 5 : 4;
        const int rows = (list.size() + cols - 1) / cols;
        const qreal top = drawerSearchRect().bottom() + 16.0;
        const qreal totalH = rows * (Layout::kCellH + Layout::kRowGap) + insetBottom() + 24.0;
        const qreal maxScroll = std::max<qreal>(0.0, totalH - (height() - top));
        m_drawerScroll = std::clamp(m_drawerScroll + dy, 0.0, maxScroll);
        update();
    }
    m_lastPos = pos;
}

void LauncherWindow::mouseReleaseEvent(QMouseEvent *event) {
    if (event->button() != Qt::LeftButton || !m_pressed) return;
    m_pressed = false;
    m_longPressTimer.stop();
    const QPointF pos = event->position();

    if (m_liftedHome >= 0) {
        const int target = hitHomeSlot(pos);
        if (target >= 0 && target != m_liftedHome && target < m_home.size()) {
            std::swap(m_home[m_liftedHome], m_home[target]);
            saveHome();
        } else if (target < 0) {
            // Drop into grid coordinates if over empty cell
            const qreal localX = pos.x() - gridX();
            const qreal localY = pos.y() - (gridTop() - m_homeScroll);
            if (localX >= 0 && localY >= 0) {
                const int col = std::clamp(int(localX / (homeColW() + Layout::kColGap)), 0, homeCols() - 1);
                const int row = std::max(0, int(localY / (Layout::kCellH + Layout::kRowGap)));
                m_home[m_liftedHome].x = col;
                m_home[m_liftedHome].y = row;
                saveHome();
            }
        }
        m_liftedHome = -1;
        update();
        return;
    }

    if (m_longPressed) return;

    if (m_sheet != SheetKind::None) {
        const int hit = hitSheetButton(pos);
        if (hit == -2) closeSheet();
        else if (hit >= 0) triggerSheetAction(hit);
        return;
    }

    if (m_drawerOpen) {
        if (!m_dragging) {
            if (drawerCloseRect().contains(pos)) {
                closeDrawer();
            } else if (!m_search.isEmpty() && drawerClearRect().contains(pos)) {
                m_search.clear();
                m_drawerScroll = 0.0;
                update();
            } else if (drawerSearchRect().contains(pos)) {
                m_searchFocused = true;
                if (auto *im = QGuiApplication::inputMethod()) im->show();
                update();
            } else {
                const int idx = hitDrawerApp(pos);
                const QVector<AppInfo> list = filteredApps();
                if (idx >= 0 && idx < list.size()) {
                    gui_string_t c = toWitStr(list[idx].component);
                    ultra_launcher_android_launch(&c);
                    gui_string_free(&c);
                }
            }
        }
        return;
    }

    // Home screen
    if (m_dragging) {
        // Swipe down opens drawer
        if (pos.y() - m_pressPos.y() > 72.0) {
            openDrawer();
        }
        return;
    }

    if (appsPillRect().contains(pos)) {
        ultra_launcher_android_open_recents();
        return;
    }

    const int idx = hitHomeSlot(pos);
    if (idx >= 0 && idx < m_home.size()) {
        gui_string_t c = toWitStr(m_home[idx].component);
        ultra_launcher_android_launch(&c);
        gui_string_free(&c);
    }
}

void LauncherWindow::wheelEvent(QWheelEvent *event) {
    if (!m_drawerOpen) return;
    const QVector<AppInfo> list = filteredApps();
    const int cols = width() >= 480 ? 5 : 4;
    const int rows = (list.size() + cols - 1) / cols;
    const qreal top = drawerSearchRect().bottom() + 16.0;
    const qreal totalH = rows * (Layout::kCellH + Layout::kRowGap) + insetBottom() + 24.0;
    const qreal maxScroll = std::max<qreal>(0.0, totalH - (height() - top));
    m_drawerScroll = std::clamp(m_drawerScroll - event->angleDelta().y() * 0.5, 0.0, maxScroll);
    update();
}

void LauncherWindow::keyPressEvent(QKeyEvent *event) {
    if (!m_drawerOpen) return;
    if (event->key() == Qt::Key_Escape) {
        closeDrawer();
        return;
    }
    if (event->key() == Qt::Key_Backspace) {
        if (!m_search.isEmpty()) {
            m_search.chop(1);
            m_drawerScroll = 0.0;
            update();
        }
        return;
    }
    if (event->key() == Qt::Key_Return || event->key() == Qt::Key_Enter) {
        const QVector<AppInfo> list = filteredApps();
        if (!list.isEmpty()) {
            gui_string_t c = toWitStr(list.first().component);
            ultra_launcher_android_launch(&c);
            gui_string_free(&c);
        }
        return;
    }
    const QString t = event->text();
    if (!t.isEmpty() && t.at(0).isPrint()) {
        m_search += t;
        m_drawerScroll = 0.0;
        update();
    }
}

void LauncherWindow::timerEvent(QTimerEvent *event) {
    if (event->timerId() == m_pollTimer.timerId()) {
        pollHostEvents();
        return;
    }
    if (event->timerId() == m_longPressTimer.timerId()) {
        m_longPressTimer.stop();
        if (!m_pressed || m_dragging) return;
        m_longPressed = true;
        if (m_drawerOpen) {
            if (m_pressedDrawer >= 0) openSheet(SheetKind::DrawerApp, m_pressedDrawer);
        } else if (m_pressedHome >= 0) {
            openSheet(SheetKind::HomeItem, m_pressedHome);
        } else {
            openSheet(SheetKind::Menu, -1);
        }
    }
}
