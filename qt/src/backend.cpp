#include "backend.h"

#include <QFile>
#include <QGuiApplication>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QScreen>
#include <QSettings>
#include <QTimer>
#include <cmath>

#ifdef Q_OS_ANDROID
#include <QJniEnvironment>
#include <QJniObject>
#endif

namespace {
Backend *g_backend = nullptr;

#ifdef Q_OS_ANDROID
constexpr char kActivity[] = "dev/ultra/launcher/HomeActivity";

QString callString(const char *method) {
    return QJniObject::callStaticObjectMethod(kActivity, method, "()Ljava/lang/String;").toString();
}

void callWithString(const char *method, const QString &s) {
    QJniObject::callStaticMethod<void>(kActivity, method, "(Ljava/lang/String;)V",
                                       QJniObject::fromString(s).object<jstring>());
}

void callVoid(const char *method) {
    QJniObject::callStaticMethod<void>(kActivity, method, "()V");
}

// Called by HomeActivity on Android's main thread: hop over to Qt's.
template <typename F>
void post(F f) {
    if (g_backend) QMetaObject::invokeMethod(g_backend, f, Qt::QueuedConnection);
}

void jAppsChanged(JNIEnv *env, jclass, jstring packages) {
    const char *c = env->GetStringUTFChars(packages, nullptr);
    const QString s = QString::fromUtf8(c);
    env->ReleaseStringUTFChars(packages, c);
    post([=] { emit g_backend->appsChanged(s); });
}
void jHomePressed(JNIEnv *, jclass) { post([] { emit g_backend->homePressed(); }); }
void jBackPressed(JNIEnv *, jclass) { post([] { emit g_backend->backPressed(); }); }
void jInsets(JNIEnv *, jclass, jint top, jint bottom) { post([=] { g_backend->setInsets(top, bottom); }); }
void jWidgetAdded(JNIEnv *, jclass, jint id) { post([=] { emit g_backend->widgetAdded(id); }); }
void jWidgetMenu(JNIEnv *, jclass, jint id) { post([=] { emit g_backend->widgetMenu(id); }); }
void jWidgetGrab(JNIEnv *, jclass, jint id) { post([=] { emit g_backend->widgetGrab(id); }); }
void jWidgetDrag(JNIEnv *, jclass, jint x, jint y) {
    post([=] {
        const qreal r = QGuiApplication::primaryScreen()->devicePixelRatio();
        emit g_backend->widgetDrag(x / r, y / r);
    });
}
void jWidgetDrop(JNIEnv *, jclass, jboolean moved) { post([=] { emit g_backend->widgetDrop(moved); }); }

void jLegacyHome(JNIEnv *env, jclass, jstring json) {
    const char *c = env->GetStringUTFChars(json, nullptr);
    const QString s = QString::fromUtf8(c);
    env->ReleaseStringUTFChars(json, c);
    post([=] { emit g_backend->legacyHome(s); });
}

void jAppList(JNIEnv *env, jclass, jstring json) {
    const char *c = env->GetStringUTFChars(json, nullptr);
    const QByteArray s(c);
    env->ReleaseStringUTFChars(json, c);
    post([=] { g_backend->takeAppList(s); });
}

void registerNatives() {
    static bool done = false;
    if (done) return;
    done = true;
    const JNINativeMethod methods[] = {
        {"appsChanged", "(Ljava/lang/String;)V", reinterpret_cast<void *>(jAppsChanged)},
        {"homePressed", "()V", reinterpret_cast<void *>(jHomePressed)},
        {"backPressed", "()V", reinterpret_cast<void *>(jBackPressed)},
        {"insets", "(II)V", reinterpret_cast<void *>(jInsets)},
        {"widgetAdded", "(I)V", reinterpret_cast<void *>(jWidgetAdded)},
        {"widgetMenu", "(I)V", reinterpret_cast<void *>(jWidgetMenu)},
        {"widgetGrab", "(I)V", reinterpret_cast<void *>(jWidgetGrab)},
        {"widgetDrag", "(II)V", reinterpret_cast<void *>(jWidgetDrag)},
        {"widgetDrop", "(Z)V", reinterpret_cast<void *>(jWidgetDrop)},
        {"appList", "(Ljava/lang/String;)V", reinterpret_cast<void *>(jAppList)},
        {"legacyHome", "(Ljava/lang/String;)V", reinterpret_cast<void *>(jLegacyHome)},
    };
    QJniEnvironment env;
    env.registerNativeMethods("dev/ultra/launcher/Native", methods, int(std::size(methods)));
}
#endif

QJsonDocument demo(const QString &name) {
    QFile f(QStringLiteral(":/qt/qml/Launcher/demo/") + name);
    if (!f.open(QIODevice::ReadOnly)) return {};
    return QJsonDocument::fromJson(f.readAll());
}
}  // namespace

Backend::Backend(QObject *parent) : QObject(parent) { g_backend = this; }

Backend *Backend::instance() { return g_backend; }

bool Backend::isAndroid() const {
#ifdef Q_OS_ANDROID
    return true;
#else
    return false;
#endif
}

qreal Backend::dpr() const {
    QScreen *s = QGuiApplication::primaryScreen();
    return s ? s->devicePixelRatio() : 1.0;
}

void Backend::setInsets(int top, int bottom) {
    m_insetTop = top / dpr();
    m_insetBottom = bottom / dpr();
    emit insetsChanged();
}

void Backend::ready() {
#ifdef Q_OS_ANDROID
    registerNatives();
    callVoid("ready");
#endif
}

static QVariantList appList(const QJsonDocument &doc) {
    QVariantList out;
    for (const QJsonValue &v : doc.object().value(QStringLiteral("apps")).toArray()) {
        const QJsonObject o = v.toObject();
        out.append(QVariantMap{{"label", o.value("label").toString()},
                               {"component", o.value("component").toString()},
                               {"system", o.value("system").toBool()}});
    }
    return out;
}

// The app list, and a copy kept for the next start: listing (and labelling) every app
// takes a while on a phone, so the home screen starts from the copy meanwhile.
QVariantList Backend::apps() const {
#ifdef Q_OS_ANDROID
    const QByteArray json = callString("apps").toUtf8();
#else
    const QByteArray json = demo(QStringLiteral("apps.json")).toJson(QJsonDocument::Compact);
#endif
    const QVariantList out = appList(QJsonDocument::fromJson(json));
    if (!out.isEmpty()) QSettings().setValue(QStringLiteral("apps"), QString::fromUtf8(json));
    return out;
}

// apps(), without holding up the UI thread meanwhile: the list arrives as appsLoaded.
void Backend::requestApps() {
#ifdef Q_OS_ANDROID
    callVoid("listApps");
#else
    QTimer::singleShot(0, this, [this] { takeAppList(demo(QStringLiteral("apps.json")).toJson(QJsonDocument::Compact)); });
#endif
}

void Backend::takeAppList(const QByteArray &json) {
    const QVariantList out = appList(QJsonDocument::fromJson(json));
    if (!out.isEmpty()) QSettings().setValue(QStringLiteral("apps"), QString::fromUtf8(json));
    emit appsLoaded(out);
}

QVariantList Backend::savedApps() const {
    return appList(QJsonDocument::fromJson(QSettings().value(QStringLiteral("apps")).toString().toUtf8()));
}

QVariantList Backend::widgetProviders() const {
#ifdef Q_OS_ANDROID
    const QJsonDocument doc = QJsonDocument::fromJson(callString("widgetProviders").toUtf8());
#else
    const QJsonDocument doc = demo(QStringLiteral("widgets.json"));
#endif
    QVariantList out;
    for (const QJsonValue &v : doc.object().value(QStringLiteral("widgets")).toArray()) {
        const QJsonObject o = v.toObject();
        out.append(QVariantMap{{"label", o.value("label").toString()},
                               {"app", o.value("app").toString()},
                               {"provider", o.value("provider").toString()},
                               {"h", o.value("h").toInt(120)}});
    }
    return out;
}

#ifdef Q_OS_ANDROID
void Backend::launch(const QString &c) { callWithString("launch", c); }
void Backend::appInfo(const QString &c) { callWithString("appInfo", c); }
void Backend::uninstall(const QString &c) { callWithString("uninstall", c); }
void Backend::openSettings() { callVoid("openSettings"); }
void Backend::pickWallpaper() { callVoid("pickWallpaper"); }
void Backend::openRecents() { callVoid("openRecents"); }
void Backend::checkUpdates() { callVoid("checkUpdates"); }
void Backend::haptic() { callVoid("haptic"); }
void Backend::addWidget(const QString &p) { callWithString("addWidget", p); }
void Backend::removeWidget(int id) { QJniObject::callStaticMethod<void>(kActivity, "removeWidget", "(I)V", jint(id)); }
#else
void Backend::launch(const QString &c) { emit toast("Open " + c); }
void Backend::appInfo(const QString &c) { emit toast("App info for " + c); }
void Backend::uninstall(const QString &c) { emit toast("Uninstall " + c); }
void Backend::openSettings() { emit toast("Open system settings"); }
void Backend::pickWallpaper() { emit toast("Pick a wallpaper"); }
void Backend::openRecents() { emit toast("Show open windows"); }
void Backend::checkUpdates() { emit toast("Check for updates"); }
void Backend::haptic() {}
void Backend::addWidget(const QString &) {
    const int id = m_demoWidget++;
    QTimer::singleShot(0, this, [this, id] { emit widgetAdded(id); });
}
void Backend::removeWidget(int) {}
#endif

void Backend::placeWidgets(const QVariantList &list) {
#ifdef Q_OS_ANDROID
    const qreal r = dpr();
    QJsonArray out;
    for (const QVariant &v : list) {
        const QVariantMap m = v.toMap();
        const qreal x = m.value("x").toReal() * r, y = m.value("y").toReal() * r;
        const qreal w = m.value("w").toReal() * r, h = m.value("h").toReal() * r;
        out.append(QJsonArray{m.value("id").toInt(), qRound(x), qRound(y), qRound(x + w) - qRound(x),
                              qRound(y + h) - qRound(y), m.value("editing").toBool() ? 1 : 0});
    }
    callWithString("placeWidgets", QString::fromUtf8(QJsonDocument(out).toJson(QJsonDocument::Compact)));
#else
    Q_UNUSED(list);
#endif
}

void Backend::coverWidgets(qreal y) {
#ifdef Q_OS_ANDROID
    const int py = y <= 0 ? int(y) : int(std::ceil(y * dpr()));
    QJniObject::callStaticMethod<void>(kActivity, "coverWidgets", "(I)V", jint(py));
#else
    Q_UNUSED(y);
#endif
}

void Backend::sheetWidgets(qreal y, bool dim) {
#ifdef Q_OS_ANDROID
    const int py = y <= 0 ? 0 : int(std::floor(y * dpr()));
    QJniObject::callStaticMethod<void>(kActivity, "sheetWidgets", "(IZ)V", jint(py), jboolean(dim));
#else
    Q_UNUSED(y);
    Q_UNUSED(dim);
#endif
}

QVariantMap Backend::widgetLimits(int id) const {
#ifdef Q_OS_ANDROID
    const QJsonObject o = QJsonDocument::fromJson(
        QJniObject::callStaticObjectMethod(kActivity, "widgetLimits", "(I)Ljava/lang/String;", jint(id))
            .toString().toUtf8()).object();
    const qreal r = dpr();
    QVariantMap m;
    for (const char *k : {"w", "minW", "minH", "maxW", "maxH"}) {
        if (o.contains(k)) m.insert(k, o.value(k).toDouble() / r);
    }
    for (const char *k : {"h", "v"}) {
        if (o.contains(k)) m.insert(k, o.value(k).toBool());
    }
    return m;
#else
    Q_UNUSED(id);
    return {{"minW", 120}, {"minH", 80}, {"maxW", 0}, {"maxH", 0}, {"h", true}, {"v", true}};
#endif
}

void Backend::excludeGestures(const QVariantList &rects) {
#ifdef Q_OS_ANDROID
    const qreal r = dpr();
    QJsonArray out;
    for (const QVariant &v : rects) {
        const QVariantMap m = v.toMap();
        out.append(QJsonArray{qRound(m.value("x").toReal() * r), qRound(m.value("y").toReal() * r),
                              qRound(m.value("w").toReal() * r), qRound(m.value("h").toReal() * r)});
    }
    callWithString("excludeGestures", QString::fromUtf8(QJsonDocument(out).toJson(QJsonDocument::Compact)));
#else
    Q_UNUSED(rects);
#endif
}

QString Backend::loadHome() const {
    QSettings s;
    return s.value(QStringLiteral("home")).toString();
}

void Backend::saveHome(const QString &json) {
    QSettings s;
    s.setValue(QStringLiteral("home"), json);
}
