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
    return app.exec();
}
