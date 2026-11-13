#pragma once

#include "layout.h"

#include <QBasicTimer>
#include <QHash>
#include <QImage>
#include <QPointF>
#include <QRasterWindow>
#include <QSet>
#include <QString>
#include <QVector>
#include <QElapsedTimer>

struct AppInfo {
    QString label;
    QString component;
    bool system = false;
};

struct WidgetProviderInfo {
    QString provider;
    QString label;
    int w = 2;
    int h = 2;
};

class LauncherWindow : public QRasterWindow {
    Q_OBJECT

public:
    LauncherWindow();

protected:
    void paintEvent(QPaintEvent *event) override;
    void resizeEvent(QResizeEvent *event) override;
    void mousePressEvent(QMouseEvent *event) override;
    void mouseMoveEvent(QMouseEvent *event) override;
    void mouseReleaseEvent(QMouseEvent *event) override;
    void wheelEvent(QWheelEvent *event) override;
    void keyPressEvent(QKeyEvent *event) override;
    void timerEvent(QTimerEvent *event) override;

private:
    enum class SheetKind { None, Menu, HomeItem, DrawerApp, Widgets };

    void pollHostEvents();
    void refreshInsets();
    void refreshApps();
    void refreshWidgetProviders();
    void loadHome();
    void seedDefaultHome();
    void saveHome() const;
    QImage iconFor(const QString &component, int px);

    // Geometry helpers (logical px)
    qreal insetTop() const;
    qreal insetBottom() const;
    qreal contentW() const;
    qreal contentX() const;
    qreal gridX() const;
    qreal gridW() const;
    int homeCols() const;
    qreal homeColW() const;
    qreal panelY() const;
    qreal gridTop() const;
    QRectF appsPillRect() const;
    QRectF drawerSearchRect() const;
    QRectF drawerCloseRect() const;
    QRectF drawerClearRect() const;
    QVector<AppInfo> filteredApps() const;
    int hitHomeSlot(const QPointF &pos) const;
    int hitDrawerApp(const QPointF &pos) const;
    int hitSheetButton(const QPointF &pos) const;

    void openDrawer();
    void closeDrawer();
    void openSheet(SheetKind kind, int index);
    void closeSheet();
    void triggerSheetAction(int actionIdx);

    void drawHome(QPainter &p);
    void drawDrawer(QPainter &p);
    void drawSheet(QPainter &p);
    void drawIconAndLabel(QPainter &p, const QRectF &box, const QString &component,
                          const QString &label, int iconSize, bool lifted);

    // Host state
    int m_physInsetTop = 0;
    int m_physInsetBottom = 0;
    QVector<AppInfo> m_apps;
    QHash<QString, int> m_appIndex;
    QVector<Layout::HomeSlot> m_home;
    QVector<WidgetProviderInfo> m_providers;
    QHash<QString, QImage> m_icons;
    QSet<QString> m_missingIcons;
    QString m_lastPlacedWidgets;
    bool m_lastWidgetsVisible = true;

    // UI state
    bool m_drawerOpen = false;
    qreal m_drawerScroll = 0.0;
    qreal m_homeScroll = 0.0;
    QString m_search;
    bool m_searchFocused = false;
    SheetKind m_sheet = SheetKind::None;
    int m_sheetIndex = -1;

    // Pointer / gesture tracking
    bool m_pressed = false;
    bool m_dragging = false;
    bool m_longPressed = false;
    int m_pressedHome = -1;
    int m_pressedDrawer = -1;
    int m_liftedHome = -1;
    QPointF m_pressPos;
    QPointF m_lastPos;
    QPointF m_liftPos;
    QElapsedTimer m_pressTimer;
    QBasicTimer m_pollTimer;
    QBasicTimer m_longPressTimer;
};
