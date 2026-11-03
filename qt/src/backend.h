#pragma once

#include <QObject>
#include <QString>
#include <QVariantList>
#include <QVariantMap>
#include <QtQml/qqmlregistration.h>

// The QML app's window onto Android (HomeActivity.java). On a phone each call goes
// through JNI; on a desktop the backend serves demo data from qt/demo and turns the
// actions into toasts, so the UI can be tried and tested without a device.
//
// Geometry in and out of QML is in logical (QML) px; the backend scales it to the
// physical screen px HomeActivity works in.
class Backend : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool android READ isAndroid CONSTANT)
    Q_PROPERTY(qreal insetTop READ insetTop NOTIFY insetsChanged)
    Q_PROPERTY(qreal insetBottom READ insetBottom NOTIFY insetsChanged)

public:
    explicit Backend(QObject *parent = nullptr);
    static Backend *instance();

    bool isAndroid() const;
    qreal insetTop() const { return m_insetTop; }
    qreal insetBottom() const { return m_insetBottom; }

    // Lists: [{label, component, system}] and [{label, app, provider, h}].
    Q_INVOKABLE QVariantList apps() const;
    Q_INVOKABLE QVariantList savedApps() const; // the last list apps() returned, for a quick start
    Q_INVOKABLE QVariantList widgetProviders() const;

    Q_INVOKABLE void launch(const QString &component);
    Q_INVOKABLE void appInfo(const QString &component);
    Q_INVOKABLE void uninstall(const QString &component);
    Q_INVOKABLE void openSettings();
    Q_INVOKABLE void pickWallpaper();
    Q_INVOKABLE void openRecents();
    Q_INVOKABLE void checkUpdates();
    Q_INVOKABLE void haptic();

    // Widgets: addWidget answers with widgetAdded(id) once bound (and configured).
    Q_INVOKABLE void addWidget(const QString &provider);
    Q_INVOKABLE void removeWidget(int id);
    // [{id, x, y, w, h, editing}] in global logical px.
    Q_INVOKABLE void placeWidgets(const QVariantList &list);
    // The drawer covers the screen down to global logical y; -1 all of it, 0 none.
    Q_INVOKABLE void coverWidgets(qreal y);
    // A sheet covers the screen from global logical y down (0: none) and dims the rest.
    Q_INVOKABLE void sheetWidgets(qreal y, bool dim);
    // {w, minW, minH, maxW, maxH (0: no limit), h, v} in logical px.
    Q_INVOKABLE QVariantMap widgetLimits(int id) const;
    // [{x, y, w, h}] in global logical px: no system back swipe there.
    Q_INVOKABLE void excludeGestures(const QVariantList &rects);

    // The home layout, as JSON, kept in the app's settings.
    Q_INVOKABLE QString loadHome() const;
    Q_INVOKABLE void saveHome(const QString &json);

    // Tell HomeActivity QML is up (it then adds the widget layer and starts calling back).
    Q_INVOKABLE void ready();

    // From HomeActivity (on Android's thread; queued to Qt's).
    void setInsets(int top, int bottom);

signals:
    void insetsChanged();
    void appsChanged(const QString &packages); // comma-separated; empty: any
    void homePressed();
    void backPressed();
    void widgetAdded(int id);
    void widgetMenu(int id);
    void widgetGrab(int id);
    void widgetDrag(qreal x, qreal y);  // global logical px
    void widgetDrop(bool moved);
    void legacyHome(const QString &json); // the WebView build's saved home, read once
    void toast(const QString &text);    // desktop stand-in for an action

private:
    qreal dpr() const;
    qreal m_insetTop = 0, m_insetBottom = 0;
    mutable int m_demoWidget = 100;
};
