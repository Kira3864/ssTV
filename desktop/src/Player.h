#pragma once
#include <QQuickFramebufferObject>
#include <memory>
#include <mpv/client.h>

struct PlayerCore {
    mpv_handle *handle = nullptr;
    ~PlayerCore();
};

class Player : public QQuickFramebufferObject {
    Q_OBJECT
    Q_PROPERTY(bool paused READ paused NOTIFY stateChanged)
    Q_PROPERTY(bool loaded READ loaded NOTIFY stateChanged)
    Q_PROPERTY(bool buffering READ buffering NOTIFY stateChanged)
    Q_PROPERTY(double position READ position NOTIFY stateChanged)
    Q_PROPERTY(double duration READ duration NOTIFY stateChanged)
    Q_PROPERTY(double speed READ speed NOTIFY stateChanged)
    Q_PROPERTY(double volume READ volume NOTIFY stateChanged)
    Q_PROPERTY(bool holding READ holding NOTIFY stateChanged)
    Q_PROPERTY(QString decoder READ decoder NOTIFY stateChanged)
    Q_PROPERTY(QString error READ error NOTIFY stateChanged)
public:
    explicit Player(QQuickItem *parent = nullptr);
    ~Player() override;
    Renderer *createRenderer() const override;
    std::shared_ptr<PlayerCore> core() const { return m_core; }
    bool paused() const { return m_paused; }
    bool loaded() const { return m_loaded; }
    bool buffering() const { return m_buffering; }
    bool holding() const { return m_holding; }
    double position() const { return m_position; }
    double duration() const { return m_duration; }
    double speed() const { return m_speed; }
    double volume() const { return m_volume; }
    QString decoder() const { return m_decoder; }
    QString error() const { return m_error; }
    Q_INVOKABLE void open(const QString &url, double start = 0);
    Q_INVOKABLE void togglePause();
    Q_INVOKABLE void pause();
    Q_INVOKABLE void seek(double seconds);
    Q_INVOKABLE void skip(double seconds);
    Q_INVOKABLE void setSpeed(double speed);
    Q_INVOKABLE void setVolume(double volume);
    Q_INVOKABLE void beginHold(double speed);
    Q_INVOKABLE void endHold();
    Q_INVOKABLE void setHardware(bool enabled);
    Q_INVOKABLE void setAspect(const QString &aspect);
signals:
    void stateChanged();
    void finished();
    void opened(const QString &url);
private slots:
    void drainEvents();
private:
    void command(const QList<QByteArray> &args);
    void number(const char *name, double value);
    std::shared_ptr<PlayerCore> m_core;
    bool m_paused = true, m_loaded = false, m_buffering = false, m_holding = false;
    double m_position = 0, m_duration = 0, m_speed = 1, m_volume = 80, m_savedSpeed = 1;
    QString m_decoder, m_error;
};
