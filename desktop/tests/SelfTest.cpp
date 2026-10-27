#include "SelfTest.h"
#include "../src/Backend.h"
#include "../src/Player.h"
#include <QtTest>
#include <QQuickWindow>
#include <QQuickItem>
#include <QFile>
#include <QDir>
#include <QImage>

class SelfTest : public QObject {
    Q_OBJECT
public:
    QQuickWindow *window;
    Player *player;
    Backend *backend;
    QPoint point() const {
        auto stage = window->findChild<QQuickItem *>("videoStage");
        return stage->mapToScene(QPointF(stage->width()/2, stage->height()/2)).toPoint();
    }
private slots:
    void initTestCase() {
        QVERIFY(window); QVERIFY(player); QVERIFY(!backend->sources().isEmpty());
        QVERIFY(QTest::qWaitForWindowExposed(window));
        window->requestActivate();
        if (qEnvironmentVariableIsSet("SSTV_TEST_SOFTWARE")) player->setHardware(false);
        backend->openUrl(qEnvironmentVariable("SSTV_TEST_VIDEO"));
        QTRY_VERIFY_WITH_TIMEOUT(player->loaded(), 10000);
        QTRY_VERIFY_WITH_TIMEOUT(player->duration() > 15, 5000);
    }
    void rightButtonRestoresOriginalSpeed() {
        player->setSpeed(1.25); QTRY_VERIFY(qAbs(player->speed() - 1.25) < .01);
        QTest::mousePress(window, Qt::RightButton, Qt::NoModifier, point());
        QTest::qWait(250); QTRY_VERIFY(player->holding()); QTRY_VERIFY(qAbs(player->speed() - 3) < .01);
        QTest::mouseRelease(window, Qt::RightButton, Qt::NoModifier, point());
        QTRY_VERIFY(!player->holding()); QTRY_VERIFY(qAbs(player->speed() - 1.25) < .01);
    }
    void releasingOutsideVideoAlsoRestoresSpeed() {
        QTest::mousePress(window, Qt::RightButton, Qt::NoModifier, point());
        QTest::qWait(250); QTRY_VERIFY(player->holding());
        QTest::mouseRelease(window, Qt::RightButton, Qt::NoModifier, QPoint(8, 8));
        QTRY_VERIFY(!player->holding()); QTRY_VERIFY(qAbs(player->speed() - 1.25) < .01);
    }
    void navigationCancelsHold() {
        player->beginHold(3); QTRY_VERIFY(player->holding());
        QMetaObject::invokeMethod(window, "go", Q_ARG(QVariant, "home"));
        QTRY_VERIFY(!player->holding()); QTRY_VERIFY(qAbs(player->speed() - 1.25) < .01);
        window->setProperty("page", "player");
    }
    void losingFocusRestoresSpeed() {
        player->beginHold(3); QTRY_VERIFY(player->holding());
        QWindow other; other.resize(200, 100); other.show(); other.requestActivate();
        QTRY_VERIFY(!window->isActive()); QTRY_VERIFY(!player->holding());
        QTRY_VERIFY(qAbs(player->speed() - 1.25) < .01);
        other.close(); window->requestActivate(); QTRY_VERIFY(window->isActive());
    }
    void spacePauseAndSeek() {
        QTest::qWait(150);
        bool paused = player->paused(); QTest::keyClick(window, Qt::Key_Space);
        QTRY_VERIFY(player->paused() != paused);
        player->seek(5); QTRY_VERIFY(qAbs(player->position() - 5) < 1);
    }
    void doubleClickFullscreen() {
        const bool paused = player->paused();
        QTest::mouseDClick(window, Qt::LeftButton, Qt::NoModifier, point());
        QTRY_COMPARE(window->visibility(), QWindow::FullScreen);
        QTest::qWait(650); QCOMPARE(player->paused(), paused);
        QTest::keyClick(window, Qt::Key_Escape);
        QTRY_COMPARE(window->visibility(), QWindow::Windowed);
    }
    void themesRenderWithVideo() {
        const auto report = qEnvironmentVariable("SSTV_TEST_REPORT"); QDir().mkpath(report);
        for (int i = 0; i < 4; ++i) {
            window->setProperty("theme", i); QTest::qWait(180);
            auto image = window->grabWindow(); QVERIFY(!image.isNull());
            QVERIFY(image.save(report + QString("/theme-%1.png").arg(i)));
        }
        backend->remember(player->position(), player->duration()); QVERIFY(!backend->history().isEmpty());
    }
    void downloadAndResumeDirectFile() {
        auto base = qEnvironmentVariable("SSTV_TEST_SERVER"); if (base.isEmpty()) QSKIP("No fixture server");
        backend->addSource("Local fixture", base + "/api");
        int source = backend->sources().size() - 1; backend->browse(source);
        QTRY_VERIFY_WITH_TIMEOUT(!backend->busy(), 10000); QVERIFY(!backend->videos().isEmpty());
        backend->inspect(backend->videos().first().toMap());
        QTRY_VERIFY(!backend->detail().value("lines").toList().isEmpty());
        backend->downloadEpisode(0, 0);
        QTRY_VERIFY_WITH_TIMEOUT(!backend->downloads().isEmpty() && backend->downloads().first().toMap().value("bytes").toLongLong() > 0, 10000);
        backend->pauseDownload(0); QTRY_COMPARE(backend->downloads().first().toMap().value("state").toString(), QString("已暂停"));
        QTest::qWait(100); backend->resumeDownload(0);
        QTRY_COMPARE_WITH_TIMEOUT(backend->downloads().first().toMap().value("state").toString(), QString("已完成"), 30000);
        QFile downloaded(backend->downloads().first().toMap().value("path").toString()); QVERIFY(downloaded.open(QIODevice::ReadOnly));
        QFile original(qEnvironmentVariable("SSTV_TEST_VIDEO")); QVERIFY(original.open(QIODevice::ReadOnly));
        QCOMPARE(downloaded.readAll(), original.readAll());
    }
    void hlsDownloadsAndPlaysOffline() {
        if (qEnvironmentVariable("SSTV_TEST_SERVER").isEmpty()) QSKIP("No fixture server");
        backend->downloadEpisode(0, 1);
        QTRY_COMPARE_WITH_TIMEOUT(backend->downloads().first().toMap().value("state").toString(), QString("已完成"), 30000);
        backend->playDownload(0);
        QTRY_VERIFY_WITH_TIMEOUT(player->loaded(), 10000);
        QTRY_VERIFY(player->duration() > 15);
        QVERIFY(QFileInfo::exists(backend->downloads().first().toMap().value("path").toString()));
    }
};
int runSelfTests(QObject *root, Backend *backend) {
    SelfTest tests; tests.window = qobject_cast<QQuickWindow *>(root);
    tests.player = root ? root->findChild<Player *>("videoPlayer") : nullptr; tests.backend = backend;
    const auto report = qEnvironmentVariable("SSTV_TEST_REPORT"); QDir().mkpath(report);
    QByteArray output = (report + "/results.xml,junitxml").toUtf8();
    char name[] = "SquirrelTV-tests"; char option[] = "-o";
    char *args[] = {name, option, output.data()};
    return QTest::qExec(&tests, 3, args);
}
#include "SelfTest.moc"
