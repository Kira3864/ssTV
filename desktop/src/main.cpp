#include "Player.h"
#include "Backend.h"
#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickWindow>
#include <QQuickStyle>
#include <QStandardPaths>
#include <QFileInfo>
#include <QDir>
#include <QIcon>
#include <QTimer>
#include <QImage>
#include <QFile>
#include <QMutex>
#include <QTemporaryDir>
#include <windows.h>
#include <roapi.h>
#ifdef SSTV_SELF_TESTS
#include "../tests/SelfTest.h"
#endif

static void logMessage(QtMsgType type, const QMessageLogContext &, const QString &text) {
    static QMutex mutex;
    QMutexLocker lock(&mutex);
    QFile file(QCoreApplication::applicationDirPath() + "/startup.log");
    if (file.size() > 1024 * 1024) file.open(QIODevice::WriteOnly | QIODevice::Truncate);
    else file.open(QIODevice::WriteOnly | QIODevice::Append);
    file.write(text.toUtf8() + '\n');
    if (type == QtFatalMsg) abort();
}

int main(int argc, char *argv[]) {
    // Keep the Windows Runtime apartment alive until all Qt and media objects
    // (including cached native factories) have been released.
    struct Apartment {
        HRESULT result = RoInitialize(RO_INIT_SINGLETHREADED);
        ~Apartment() { if (SUCCEEDED(result)) RoUninitialize(); }
    } apartment;
    QGuiApplication app(argc, argv);
    struct ResetLogger { ~ResetLogger() { qInstallMessageHandler(nullptr); } } resetLogger;
    qInstallMessageHandler(logMessage);
    app.setOrganizationName("SquirrelTV"); app.setApplicationName("SquirrelTV"); app.setApplicationVersion("0.1.0");
    app.setWindowIcon(QIcon(":/design/app-icon/sstv.png"));
    QQuickWindow::setGraphicsApi(QSGRendererInterface::OpenGL);
    QQuickStyle::setStyle("Basic");
    qmlRegisterType<Player>("SquirrelTV.Player", 1, 0, "VideoPlayer");
    const bool portable = QFileInfo::exists(app.applicationDirPath() + "/portable.txt") || app.arguments().contains("--portable");
    QTemporaryDir testDir;
    auto dataDir = app.arguments().contains("--self-test") ? testDir.path() : portable ? app.applicationDirPath() + "/data" : QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);
    Backend backend(dataDir);
    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty("Backend", &backend);
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app, [] { QCoreApplication::exit(1); }, Qt::QueuedConnection);
    engine.loadFromModule("SquirrelTV", "Main");
    const auto args = app.arguments();
#ifdef SSTV_SELF_TESTS
    if (args.contains("--self-test")) {
        QTimer::singleShot(100, &app, [&] {
            const int result = runSelfTests(engine.rootObjects().value(0), &backend);
            app.exit(result);
        });
        return app.exec();
    }
#endif
    for (int i = 1; i < args.size(); ++i) if (!args[i].startsWith('-')) { QTimer::singleShot(500, &backend, [&backend, url = args[i]] { backend.openUrl(url); }); break; }
    if (args.contains("--smoke-test")) {
        QTimer::singleShot(3500, &app, [&] {
            auto window = qobject_cast<QQuickWindow *>(engine.rootObjects().value(0));
            if (!window) { app.exit(2); return; }
            window->grabWindow().save(dataDir + "/smoke.png"); app.quit();
        });
    }
    return app.exec();
}
