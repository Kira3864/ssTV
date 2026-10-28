#include "Player.h"
#include <QOpenGLContext>
#include <QOpenGLFramebufferObject>
#include <QQuickWindow>
#include <QQuickOpenGLUtils>
#include <QUrl>
#include <QPointer>
#include <QGuiApplication>
#include <QKeyEvent>
#include <algorithm>
#include <mpv/render_gl.h>

PlayerCore::~PlayerCore() { if (handle) mpv_terminate_destroy(handle); }

class VideoRenderer final : public QQuickFramebufferObject::Renderer {
    std::shared_ptr<PlayerCore> core;
    mpv_render_context *context = nullptr;
    QPointer<Player> item;
public:
    explicit VideoRenderer(Player *p) : core(p->core()), item(p) {}
    ~VideoRenderer() override {
        if (context) {
            mpv_render_context_set_update_callback(context, nullptr, nullptr);
            mpv_render_context_free(context);
        }
    }
    QOpenGLFramebufferObject *createFramebufferObject(const QSize &size) override {
        return new QOpenGLFramebufferObject(size, QOpenGLFramebufferObject::CombinedDepthStencil);
    }
    void render() override {
        if (!core->handle) return;
        if (!context) {
            mpv_opengl_init_params init{[](void *, const char *name) -> void * {
                return reinterpret_cast<void *>(QOpenGLContext::currentContext()->getProcAddress(name));
            }, nullptr};
            mpv_render_param params[] = {{MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
                {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &init}, {MPV_RENDER_PARAM_INVALID, nullptr}};
            if (mpv_render_context_create(&context, core->handle, params) < 0) return;
            mpv_render_context_set_update_callback(context, [](void *opaque) {
                auto renderer = static_cast<VideoRenderer *>(opaque);
                if (renderer->item) QMetaObject::invokeMethod(renderer->item.data(), "update", Qt::QueuedConnection);
            }, this);
        }
        mpv_render_context_update(context);
        const auto size = framebufferObject()->size();
        mpv_opengl_fbo fbo{static_cast<int>(framebufferObject()->handle()), size.width(), size.height(), 0};
        int flip = 1;
        mpv_render_param params[] = {{MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
            {MPV_RENDER_PARAM_FLIP_Y, &flip}, {MPV_RENDER_PARAM_INVALID, nullptr}};
        mpv_render_context_render(context, params);
        QQuickOpenGLUtils::resetOpenGLState();
    }
};

Player::Player(QQuickItem *parent) : QQuickFramebufferObject(parent), m_core(std::make_shared<PlayerCore>()) {
    qApp->installEventFilter(this);
    m_rightTimer.setSingleShot(true);
    m_rightTimer.setInterval(250);
    connect(&m_rightTimer, &QTimer::timeout, this, [this] {
        if (m_rightDown && window()) beginHold(window()->property("holdSpeed").toDouble());
    });
    setMirrorVertically(true);
    m_core->handle = mpv_create();
    auto h = m_core->handle;
    if (!h) { m_error = QStringLiteral("播放器初始化失败"); return; }
    for (auto pair : {std::pair{"vo", "libmpv"}, {"hwdec", "auto-safe"}, {"terminal", "no"},
                      {"input-default-bindings", "no"}, {"input-vo-keyboard", "no"},
                      {"cache", "yes"}, {"demuxer-max-bytes", "96MiB"}, {"demuxer-max-back-bytes", "8MiB"},
                      {"demuxer-readahead-secs", "30"}, {"cache-pause", "yes"}, {"cache-pause-wait", "2.5"},
                      {"cache-pause-initial", "yes"}, {"network-timeout", "15"}, {"hls-bitrate", "2500000"},
                      {"keep-open", "yes"}, {"idle", "yes"}})
        mpv_set_option_string(h, pair.first, pair.second);
    if (mpv_initialize(h) < 0) { m_error = QStringLiteral("播放器初始化失败"); return; }
    number("volume", 80);
    for (const char *name : {"time-pos", "duration", "speed", "volume", "demuxer-cache-duration"})
        mpv_observe_property(h, 0, name, MPV_FORMAT_DOUBLE);
    for (const char *name : {"pause", "paused-for-cache", "core-idle", "eof-reached"}) mpv_observe_property(h, 0, name, MPV_FORMAT_FLAG);
    mpv_observe_property(h, 0, "cache-speed", MPV_FORMAT_INT64);
    mpv_observe_property(h, 0, "hwdec-current", MPV_FORMAT_STRING);
    mpv_set_wakeup_callback(h, [](void *data) {
        QMetaObject::invokeMethod(static_cast<Player *>(data), "drainEvents", Qt::QueuedConnection);
    }, this);
}
Player::~Player() { if (m_core->handle) mpv_set_wakeup_callback(m_core->handle, nullptr, nullptr); }
QQuickFramebufferObject::Renderer *Player::createRenderer() const { return new VideoRenderer(const_cast<Player *>(this)); }
void Player::command(const QList<QByteArray> &args) {
    if (!m_core->handle) return;
    QList<const char *> ptrs;
    for (auto &arg : args) ptrs.append(arg.constData());
    ptrs.append(nullptr);
    mpv_command_async(m_core->handle, 0, ptrs.data());
}
void Player::number(const char *name, double value) {
    if (m_core->handle) mpv_set_property_async(m_core->handle, 0, name, MPV_FORMAT_DOUBLE, &value);
}
void Player::open(const QString &url, double start) {
    if (!m_qualityReload) mpv_set_property_string(m_core->handle, "options/hls-bitrate", "2500000");
    m_qualityReload = false;
    endHold(); m_error.clear(); m_loaded = false; m_duration = 0; m_position = 0;
    m_url = url; m_paused = false; m_eof = false; m_loadSpeed = 0; m_buffered = 0;
    m_cachePaused = false; m_coreIdle = true; m_buffering = QUrl(url).scheme().startsWith(QStringLiteral("http"));
    auto parsed = QUrl(url);
    QString path = parsed.isLocalFile() ? parsed.toLocalFile() : url;
    if (parsed.scheme().startsWith(QStringLiteral("http"))) {
        const auto referer = parsed.adjusted(QUrl::RemovePath | QUrl::RemoveQuery | QUrl::RemoveFragment).toString() + QLatin1Char('/');
        mpv_set_property_string(m_core->handle, "referrer", referer.toUtf8().constData());
        mpv_set_property_string(m_core->handle, "user-agent", "Mozilla/5.0 SquirrelTV/0.1");
    }
    command({"loadfile", path.toUtf8(), "replace", "-1", QByteArray("start=") + QByteArray::number(start)});
    int flag = 0; mpv_set_property_async(m_core->handle, 0, "pause", MPV_FORMAT_FLAG, &flag);
    emit stateChanged(); emit opened(url);
}
void Player::togglePause() {
    m_paused = !m_paused;
    int value = m_paused; mpv_set_property_async(m_core->handle, 0, "pause", MPV_FORMAT_FLAG, &value);
    m_buffering = !m_paused && !m_eof && (m_cachePaused || m_coreIdle); emit stateChanged();
}
void Player::pause() { m_paused = true; m_buffering = false; int value = 1; mpv_set_property_async(m_core->handle, 0, "pause", MPV_FORMAT_FLAG, &value); emit stateChanged(); }
void Player::selectQuality(qint64 bitrate) {
    const auto url = m_url; const auto position = m_position; const bool paused = m_paused;
    mpv_set_property_string(m_core->handle, "options/hls-bitrate", bitrate <= 0 ? "max" : QByteArray::number(bitrate).constData());
    m_qualityReload = true; open(url, position); if (paused) pause();
}
void Player::seek(double seconds) { command({"seek", QByteArray::number(std::max(0., seconds)), "absolute+exact"}); }
void Player::skip(double seconds) { command({"seek", QByteArray::number(seconds), "relative+exact"}); }
void Player::setSpeed(double value) { number("speed", std::clamp(value, .25, 4.)); }
void Player::setVolume(double value) { number("volume", std::clamp(value, 0., 100.)); }
void Player::beginHold(double speed) {
    if (m_holding || !m_loaded) return;
    m_savedSpeed = m_speed; m_holding = true; setSpeed(speed); emit stateChanged();
}
void Player::endHold() { m_rightTimer.stop(); m_rightDown = false; if (m_holding) { m_holding = false; setSpeed(m_savedSpeed); emit stateChanged(); } }
bool Player::eventFilter(QObject *object, QEvent *event) {
    if (object != window() || !window() || window()->property("page").toString() != QStringLiteral("player")) return false;
    if (event->type() != QEvent::KeyPress && event->type() != QEvent::KeyRelease) return false;
    auto key = static_cast<QKeyEvent *>(event);
    if (key->key() != Qt::Key_Right) return false;
    if (!m_rightDown) {
        for (auto focus = window()->activeFocusItem(); focus; focus = focus->parentItem())
            if (focus->inherits("QQuickTextInput") || focus->inherits("QQuickTextEdit") || focus->inherits("QQuickPopupItem")) return false;
    }
    if (key->isAutoRepeat()) return true;
    if (event->type() == QEvent::KeyPress) {
        if (!m_rightDown) { m_rightDown = true; m_rightTimer.start(); }
    } else if (m_rightDown) {
        bool wasHold = m_holding;
        endHold();
        if (!wasHold && m_loaded) skip(5);
    }
    key->accept(); return true;
}
void Player::setHardware(bool enabled) { endHold(); command({"set", "hwdec", enabled ? "auto-safe" : "no"}); }
void Player::setAspect(const QString &aspect) { command({"set", "video-aspect-override", aspect.toUtf8()}); }
void Player::drainEvents() {
    while (auto event = mpv_wait_event(m_core->handle, 0)) {
        if (event->event_id == MPV_EVENT_NONE) break;
        if (event->event_id == MPV_EVENT_PROPERTY_CHANGE) {
            auto p = static_cast<mpv_event_property *>(event->data);
            if (!p->data) continue;
            QByteArray name(p->name);
            if (p->format == MPV_FORMAT_DOUBLE) {
                double value = *static_cast<double *>(p->data);
                if (name == "time-pos") m_position = value;
                else if (name == "duration") m_duration = value;
                else if (name == "speed") m_speed = value;
                else if (name == "volume") m_volume = value;
                else if (name == "demuxer-cache-duration") m_buffered = value;
            } else if (p->format == MPV_FORMAT_INT64) {
                if (name == "cache-speed") m_loadSpeed = double(*static_cast<int64_t *>(p->data));
            } else if (p->format == MPV_FORMAT_FLAG) {
                bool value = *static_cast<int *>(p->data) != 0;
                // User pause intent is owned by the controls. A cache underrun
                // must not turn the play button into a manually paused state.
                if (name == "paused-for-cache") m_cachePaused = value;
                else if (name == "core-idle") m_coreIdle = value;
                else if (name == "eof-reached") m_eof = value;
            } else if (p->format == MPV_FORMAT_STRING) m_decoder = QString::fromUtf8(*static_cast<char **>(p->data));
            m_buffering = !m_paused && !m_eof && (m_cachePaused || (m_coreIdle && !m_url.isEmpty()));
            emit stateChanged();
        } else if (event->event_id == MPV_EVENT_FILE_LOADED) {
            m_loaded = true; emit stateChanged();
        } else if (event->event_id == MPV_EVENT_END_FILE) {
            endHold();
            auto e = static_cast<mpv_event_end_file *>(event->data);
            if (e->reason == MPV_END_FILE_REASON_ERROR) m_error = QString::fromUtf8(mpv_error_string(e->error));
            if (e->reason == MPV_END_FILE_REASON_EOF) emit finished();
            emit stateChanged();
        }
    }
}
