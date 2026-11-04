#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QtQml/qqmlextensionplugin.h>
#include <QQuickWindow>
#include <QSurfaceFormat>

#include "backend.h"
#include "iconprovider.h"

Q_IMPORT_QML_PLUGIN(LauncherPlugin)

int main(int argc, char *argv[]) {
    // The window is see-through: the wallpaper shows behind the home screen.
    QQuickWindow::setDefaultAlphaBuffer(true);
    QSurfaceFormat fmt = QSurfaceFormat::defaultFormat();
    fmt.setAlphaBufferSize(8);
    QSurfaceFormat::setDefaultFormat(fmt);

    QGuiApplication app(argc, argv);
    QGuiApplication::setOrganizationName(QStringLiteral("ultra"));
    QGuiApplication::setApplicationName(QStringLiteral("home"));

    Backend backend;
    QQmlApplicationEngine engine;
    engine.addImageProvider(QStringLiteral("icon"), new IconProvider);
    qmlRegisterSingletonInstance("Launcher.Host", 1, 0, "Host", &backend);
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app,
                     [] { QCoreApplication::exit(1); }, Qt::QueuedConnection);
    engine.loadFromModule("Launcher", "Main");
    if (engine.rootObjects().isEmpty()) return 1;
    // Timing of the window's lifecycle, next to the icon timings (category ultra.icon).
    QObject::connect(&app, &QGuiApplication::applicationStateChanged, [](Qt::ApplicationState s) {
        qInfo("ultra.state %d", int(s));
    });
    if (auto *win = qobject_cast<QQuickWindow *>(engine.rootObjects().first())) {
        QObject::connect(win, &QQuickWindow::sceneGraphInitialized, [] { qInfo("ultra.sg initialized"); });
        QObject::connect(win, &QQuickWindow::sceneGraphInvalidated, [] { qInfo("ultra.sg invalidated"); });
        QObject::connect(win, &QQuickWindow::frameSwapped, win, [win] {
            static int n = 0;
            if (n++ < 3) qInfo("ultra.sg frame %d", n);
        });
    }
    return app.exec();
}
