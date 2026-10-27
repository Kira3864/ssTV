#include "Backend.h"
#include <QCryptographicHash>
#include <QDesktopServices>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QRegularExpression>
#include <QSaveFile>
#include <QSqlQuery>
#include <QSqlError>
#include <QUrlQuery>
#include <QDateTime>
#include <QPointer>

struct DownloadTask {
    QVariantMap data;
    QPointer<QNetworkReply> reply;
    bool paused = true, removed = false;
    QList<QPair<QUrl, QString>> segments;
    int segment = 0;
    QString playlist;
    QUrl playlistUrl;
};
static QString keyOf(const QVariantMap &v) { return v.value("api").toString() + QLatin1Char('|') + v.value("id").toString(); }
static QString digest(const QString &v) { return QString::fromLatin1(QCryptographicHash::hash(v.toUtf8(), QCryptographicHash::Sha256).toHex().left(24)); }
static QVariantMap parseVideo(const QJsonObject &o, const QVariantMap &source) {
    QVariantMap v{{"id", o.value("vod_id").toVariant().toString()}, {"name", o.value("vod_name").toString()},
        {"pic", o.value("vod_pic").toString()}, {"remark", o.value("vod_remarks").toString()},
        {"year", o.value("vod_year").toVariant().toString()}, {"area", o.value("vod_area").toString()},
        {"type", o.value("type_name").toString()}, {"actor", o.value("vod_actor").toString()},
        {"api", source.value("api")}, {"source", source.value("name")}};
    QString content = o.value("vod_content").toString();
    content.remove(QRegularExpression("<[^>]*>")); content.replace("&nbsp;", " "); content.replace("&amp;", "&");
    v["content"] = content;
    const auto names = o.value("vod_play_from").toString().split("$$$");
    const auto urls = o.value("vod_play_url").toString().split("$$$");
    QVariantList lines;
    for (int i = 0; i < urls.size(); ++i) {
        QVariantList episodes;
        for (auto &seg : urls[i].split('#', Qt::SkipEmptyParts)) {
            int at = seg.indexOf('$');
            QString url = at < 0 ? seg : seg.mid(at + 1);
            if (!url.startsWith("http")) continue;
            episodes.append(QVariantMap{{"name", at < 0 ? QString("第%1集").arg(episodes.size() + 1) : seg.left(at)}, {"url", url}});
        }
        if (!episodes.isEmpty()) lines.append(QVariantMap{{"name", i < names.size() ? names[i] : QString("线路%1").arg(i + 1)}, {"episodes", episodes}});
    }
    v["lines"] = lines;
    return v;
}
Backend::Backend(const QString &dir, QObject *parent) : QObject(parent), m_dataDir(dir) {
    QDir().mkpath(dir);
    m_db = QSqlDatabase::addDatabase("QSQLITE"); m_db.setDatabaseName(dir + "/library.sqlite");
    if (!m_db.open()) qFatal("Cannot open data directory: %s", qPrintable(m_db.lastError().text()));
    QSqlQuery q(m_db); q.exec("PRAGMA journal_mode=WAL"); q.exec("CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value BLOB NOT NULL)");
    QFile presets(":/desktop/resources/sources.json"); presets.open(QIODevice::ReadOnly);
    m_sources = read("sources", QJsonDocument::fromJson(presets.readAll()).array().toVariantList()).toList();
    m_history = read("history", QVariantList{}).toList(); m_favorites = read("favorites", QVariantList{}).toList(); m_marks = read("marks", QVariantList{}).toList();
    for (const auto &v : read("downloads", QVariantList{}).toList()) {
        auto task = std::make_shared<DownloadTask>(); task->data = v.toMap();
        if (task->data.value("state") != "已完成") task->data["state"] = "已暂停";
        m_tasks.append(task);
    }
}
Backend::~Backend() {
    for (auto reply : m_net.findChildren<QNetworkReply *>()) { reply->disconnect(this); reply->abort(); }
    for (auto &task : m_tasks) { task->paused = true; if (task->reply) task->reply->abort(); }
    persistTasks();
    const auto connection = m_db.connectionName(); m_db.close(); m_db = QSqlDatabase(); QSqlDatabase::removeDatabase(connection);
}
QVariant Backend::read(const QString &key, const QVariant &fallback) const {
    QSqlQuery q(m_db); q.prepare("SELECT value FROM state WHERE key=?"); q.addBindValue(key);
    if (!q.exec() || !q.next()) return fallback;
    return QJsonDocument::fromJson(q.value(0).toByteArray()).object().value("value").toVariant();
}
void Backend::write(const QString &key, const QVariant &value) {
    QSqlQuery q(m_db); q.prepare("INSERT OR REPLACE INTO state(key,value) VALUES(?,?)");
    q.addBindValue(key); q.addBindValue(QJsonDocument(QJsonObject{{"value", QJsonValue::fromVariant(value)}}).toJson(QJsonDocument::Compact));
    if (!q.exec()) message("数据保存失败：" + q.lastError().text());
}
QVariant Backend::setting(const QString &key, const QVariant &fallback) const { return read("setting/" + key, fallback); }
void Backend::setSetting(const QString &key, const QVariant &value) { write("setting/" + key, value); }
void Backend::message(const QString &text) { m_status = text; emit statusChanged(); }
void Backend::get(const QUrl &url, Reply callback) {
    QNetworkRequest request(url); request.setTransferTimeout(20000);
    request.setRawHeader("User-Agent", "Mozilla/5.0 (Linux; Android 12) SquirrelTV/0.1");
    request.setRawHeader("Referer", url.adjusted(QUrl::RemovePath | QUrl::RemoveQuery | QUrl::RemoveFragment).toEncoded() + '/');
    auto reply = m_net.get(request);
    auto body = std::make_shared<QByteArray>();
    reply->setReadBufferSize(256 * 1024);
    connect(reply, &QNetworkReply::readyRead, this, [reply, body] {
        body->append(reply->readAll());
        if (body->size() > 4 * 1024 * 1024) reply->abort();
    });
    connect(reply, &QNetworkReply::finished, this, [reply, callback, body] {
        body->append(reply->readAll()); QString error;
        if (reply->error() != QNetworkReply::NoError) error = reply->errorString();
        callback(*body, error, reply->url()); reply->deleteLater();
    });
}
void Backend::browse(int sourceIndex, const QString &keyword, const QString &category, int page) {
    if (sourceIndex < 0 || sourceIndex >= m_sources.size()) return;
    const auto source = m_sources[sourceIndex].toMap();
    const int generation = ++m_generation;
    m_busy = true; emit busyChanged(); m_videos.clear(); emit videosChanged();
    QUrl url(source.value("api").toString()); QUrlQuery query(url);
    query.addQueryItem("ac", "detail"); query.addQueryItem("pg", QString::number(page));
    if (!keyword.trimmed().isEmpty()) query.addQueryItem("wd", keyword.trimmed());
    if (!category.isEmpty()) query.addQueryItem("t", category);
    url.setQuery(query);
    get(url, [this, generation, source, page](const QByteArray &bytes, const QString &error, const QUrl &) {
        if (generation != m_generation) return;
        m_busy = false; emit busyChanged();
        QJsonParseError parseError; auto doc = QJsonDocument::fromJson(bytes, &parseError);
        if (!error.isEmpty() || !doc.isObject()) { message(error.isEmpty() ? "片源返回了无效 JSON" : error); return; }
        auto o = doc.object();
        m_page = page; m_pages = qMax(1, o.value("pagecount").toVariant().toInt());
        for (auto value : o.value("list").toArray()) m_videos.append(parseVideo(value.toObject(), source));
        emit videosChanged(); message(m_videos.isEmpty() ? "这个片源暂无结果，可切换片源重试" : QString("已加载 %1 部影片").arg(m_videos.size()));
    });
    if (page == 1 && keyword.isEmpty()) {
        QUrl classes(source.value("api").toString()); QUrlQuery cq(classes); cq.addQueryItem("ac", "list"); classes.setQuery(cq);
        get(classes, [this, generation](const QByteArray &bytes, const QString &, const QUrl &) {
            if (generation != m_generation) return;
            m_categories.clear();
            for (auto v : QJsonDocument::fromJson(bytes).object().value("class").toArray()) {
                auto o = v.toObject(); m_categories.append(QVariantMap{{"id", o.value("type_id").toVariant().toString()}, {"name", o.value("type_name").toString()}});
            }
            emit categoriesChanged();
        });
    }
}
void Backend::inspect(const QVariantMap &video) {
    m_detail = video; emit detailChanged();
    const auto generation = ++m_detailGeneration;
    QUrl url(video.value("api").toString()); if (!url.isValid() || url.scheme().isEmpty()) return;
    QUrlQuery q(url); q.addQueryItem("ac", "detail"); q.addQueryItem("ids", video.value("id").toString()); url.setQuery(q);
    get(url, [this, generation, video](const QByteArray &bytes, const QString &error, const QUrl &) {
        if (generation != m_detailGeneration) return;
        if (!error.isEmpty()) { message(error); return; }
        auto list = QJsonDocument::fromJson(bytes).object().value("list").toArray();
        if (!list.isEmpty()) {
            m_detail = parseVideo(list.first().toObject(), {{"name", video.value("source")}, {"api", video.value("api")}});
            emit detailChanged();
        }
    });
}
void Backend::resolve(const QString &url, std::function<void(QString)> done) {
    const auto path = QUrl(url).path().toLower();
    if (url.contains(".m3u8", Qt::CaseInsensitive) || QRegularExpression("\\.(mp4|mkv|ts|webm|mov|avi|flv|mp3|m4a)$").match(path).hasMatch() || QUrl(url).isLocalFile()) { done(url); return; }
    get(QUrl(url + (url.endsWith('/') ? "" : "/") + "index.m3u8"), [this, url, done](const QByteArray &b, const QString &error, const QUrl &actual) {
        if (error.isEmpty() && b.trimmed().startsWith("#EXTM3U")) { done(actual.toString()); return; }
        get(QUrl(url), [url, done](const QByteArray &bytes, const QString &, const QUrl &actual) {
            auto html = QString::fromUtf8(bytes.left(256 * 1024)).replace("\\/", "/").replace("&amp;", "&");
            QRegularExpression re("https?://[^\\s\"'<>]{4,400}?\\.m3u8[^\\s\"'<>]*");
            auto match = re.match(html);
            if (match.hasMatch()) { done(match.captured()); return; }
            QRegularExpression rel("[\"'](/[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*)[\"']"); match = rel.match(html);
            done(match.hasMatch() ? actual.resolved(QUrl(match.captured(1))).toString() : url);
        });
    });
}
void Backend::playEpisode(int line, int episode, double start) {
    auto lines = m_detail.value("lines").toList(); if (line < 0 || line >= lines.size()) return;
    auto episodes = lines[line].toMap().value("episodes").toList(); if (episode < 0 || episode >= episodes.size()) return;
    m_current = m_detail; m_current["line"] = line; m_current["episode"] = episode;
    m_current["episodeName"] = episodes[episode].toMap().value("name");
    QString url = episodes[episode].toMap().value("url").toString(); m_current["url"] = url;
    if (start < 0) {
        start = 0;
        for (auto &v : m_history) { auto h = v.toMap(); if (keyOf(h) == keyOf(m_current) && h.value("url") == url) { start = h.value("position").toDouble(); break; } }
    }
    emit currentChanged(); const auto generation = ++m_playGeneration;
    message("正在解析播放地址…");
    resolve(url, [this, generation, start](const QString &resolved) {
        if (generation != m_playGeneration) return;
        m_current["resolved"] = resolved; emit currentChanged(); message("播放已就绪"); emit playRequested(resolved, start);
    });
}
void Backend::openUrl(const QString &url) {
    auto u = QUrl::fromUserInput(url); if (!u.isValid()) return;
    ++m_playGeneration;
    m_current = {{"id", digest(url)}, {"name", u.isLocalFile() ? QFileInfo(u.toLocalFile()).fileName() : "网络视频"},
        {"url", u.toString()}, {"resolved", u.toString()}, {"episodeName", ""}};
    emit currentChanged(); emit playRequested(u.toString(), 0);
}
void Backend::remember(double position, double duration) {
    if (m_current.isEmpty() || duration <= 0) return;
    auto item = m_current; item["position"] = position; item["duration"] = duration; item["updated"] = QDateTime::currentMSecsSinceEpoch();
    for (int i = m_history.size() - 1; i >= 0; --i) if (keyOf(m_history[i].toMap()) == keyOf(item)) m_history.removeAt(i);
    m_history.prepend(item); while (m_history.size() > 200) m_history.removeLast();
    write("history", m_history); emit libraryChanged();
}
bool Backend::isFavorite(const QVariantMap &video) const {
    for (auto &v : m_favorites) if (keyOf(v.toMap()) == keyOf(video)) return true;
    return false;
}
void Backend::favorite(const QVariantMap &video) {
    if (video.isEmpty()) return;
    for (int i = 0; i < m_favorites.size(); ++i) if (keyOf(m_favorites[i].toMap()) == keyOf(video)) {
        m_favorites.removeAt(i); write("favorites", m_favorites); emit libraryChanged(); return;
    }
    m_favorites.prepend(video); write("favorites", m_favorites); emit libraryChanged();
}
void Backend::mark(double position) {
    if (m_current.isEmpty()) return;
    auto v = m_current; v["position"] = position; m_marks.prepend(v); write("marks", m_marks); emit libraryChanged(); message("已记录喜欢的时间点");
}
void Backend::restore(const QVariantMap &entry) {
    if (entry.value("url").toString().isEmpty()) { inspect(entry); return; }
    m_current = entry; m_detail = entry; emit currentChanged(); emit detailChanged();
    const auto generation = ++m_playGeneration;
    resolve(entry.value("url").toString(), [this, generation, entry](const QString &url) { if (generation == m_playGeneration) emit playRequested(url, entry.value("position").toDouble()); });
}
void Backend::addSource(const QString &name, const QString &api) {
    auto url = QUrl::fromUserInput(api.trimmed());
    if (name.trimmed().isEmpty() || (url.scheme() != "http" && url.scheme() != "https") || url.host().isEmpty()) { message("请输入片源名称和有效的 HTTP 接口"); return; }
    if (url.path().isEmpty() || url.path() == "/") url.setPath("/api.php/provide/vod");
    for (auto &v : m_sources) if (v.toMap().value("api") == url.toString()) { message("这个接口已经存在"); return; }
    m_sources.append(QVariantMap{{"name", name.trimmed()}, {"api", url.toString()}}); write("sources", m_sources); emit sourcesChanged();
}
void Backend::removeSource(int index) { if (index >= 0 && index < m_sources.size()) { m_sources.removeAt(index); write("sources", m_sources); emit sourcesChanged(); } }
void Backend::importSources(const QString &file) {
    QFile f(QUrl(file).isLocalFile() ? QUrl(file).toLocalFile() : file); if (!f.open(QIODevice::ReadOnly)) { message(f.errorString()); return; }
    const auto doc = QJsonDocument::fromJson(f.readAll());
    if (!doc.isArray()) { message("导入格式应为包含 name、api 的 JSON 数组"); return; }
    for (auto v : doc.array()) { auto o = v.toObject(); addSource(o.value("name").toString(), o.value("api").toString()); }
}
void Backend::exportLibrary(const QString &file) {
    QSaveFile f(QUrl(file).isLocalFile() ? QUrl(file).toLocalFile() : file);
    if (!f.open(QIODevice::WriteOnly)) { message(f.errorString()); return; }
    f.write(QJsonDocument(QJsonObject{{"sources", QJsonArray::fromVariantList(m_sources)}, {"history", QJsonArray::fromVariantList(m_history)},
        {"favorites", QJsonArray::fromVariantList(m_favorites)}, {"marks", QJsonArray::fromVariantList(m_marks)}}).toJson());
    if (!f.commit()) message(f.errorString()); else message("资料已导出");
}
void Backend::revealData() { QDesktopServices::openUrl(QUrl::fromLocalFile(m_dataDir)); }
QVariantList Backend::downloads() const { QVariantList list; for (auto &t : m_tasks) list.append(t->data); return list; }
void Backend::persistTasks() { write("downloads", downloads()); }
void Backend::downloadEpisode(int line, int episode) {
    QVariantMap v = m_detail;
    auto lines = v.value("lines").toList(); if (line < 0 || line >= lines.size()) return;
    auto eps = lines[line].toMap().value("episodes").toList(); if (episode < 0 || episode >= eps.size()) return;
    auto e = eps[episode].toMap(); const auto id = digest(keyOf(v) + e.value("url").toString());
    for (auto &t : m_tasks) if (t->data.value("taskId") == id) { message("该集已在下载列表中"); return; }
    auto t = std::make_shared<DownloadTask>();
    t->data = {{"taskId", id}, {"name", v.value("name")}, {"episodeName", e.value("name")}, {"url", e.value("url")},
        {"state", "准备中"}, {"progress", 0}, {"bytes", 0}, {"directory", m_dataDir + "/downloads/" + id}};
    m_tasks.prepend(t); persistTasks(); emit downloadsChanged(); runTask(t);
}
void Backend::taskError(const std::shared_ptr<DownloadTask> &t, const QString &error) {
    if (t->removed || t->paused) return;
    t->data["state"] = "失败"; t->data["error"] = error; persistTasks(); emit downloadsChanged();
}
void Backend::runTask(const std::shared_ptr<DownloadTask> &t) {
    t->paused = false; t->data["state"] = "下载中"; t->data["error"] = ""; emit downloadsChanged();
    QDir().mkpath(t->data.value("directory").toString());
    resolve(t->data.value("url").toString(), [this, t](const QString &url) {
        if (t->paused || t->removed) return;
        t->data["resolved"] = url;
        if (url.contains(".m3u8", Qt::CaseInsensitive)) { hls(t, QUrl(url)); return; }
        QString ext = QFileInfo(QUrl(url).path()).suffix().toLower();
        if (!QStringList{"mp4", "mkv", "webm", "ts", "mov", "avi", "flv"}.contains(ext)) ext = "mp4";
        const auto target = t->data.value("directory").toString() + "/video." + ext;
        transfer(t, QUrl(url), target + ".part", true, [this, t, target] {
            if (!QFile::rename(target + ".part", target)) { taskError(t, "无法保存已完成的视频文件"); return; }
            t->data["path"] = target; t->data["state"] = "已完成"; t->data["progress"] = 1.; persistTasks(); emit downloadsChanged();
        });
    });
}
void Backend::hls(const std::shared_ptr<DownloadTask> &t, const QUrl &url, int depth) {
    if (depth > 4) { taskError(t, "播放列表嵌套过深"); return; }
    get(url, [this, t, depth](const QByteArray &b, const QString &error, const QUrl &actual) {
        if (t->paused || t->removed) return;
        if (!error.isEmpty()) { taskError(t, error); return; }
        const auto text = QString::fromUtf8(b);
        if (!text.trimmed().startsWith("#EXTM3U")) { taskError(t, "不是有效的 HLS 播放列表"); return; }
        const auto lines = text.split('\n');
        if (text.contains("#EXT-X-STREAM-INF:")) {
            for (int i = 0; i + 1 < lines.size(); ++i) if (lines[i].startsWith("#EXT-X-STREAM-INF:")) {
                hls(t, actual.resolved(QUrl(lines[i + 1].trimmed())), depth + 1); return;
            }
        }
        if (!text.contains("#EXT-X-ENDLIST")) { taskError(t, "首版仅下载点播 HLS，暂不录制直播"); return; }
        if (text.contains("#EXT-X-BYTERANGE") || text.contains("BYTERANGE=") || text.contains("SAMPLE-AES")) {
            taskError(t, "首版暂不支持该 HLS 分段格式"); return;
        }
        t->segments.clear(); t->segment = 0; t->playlist.clear(); t->playlistUrl = actual;
        QRegularExpression uri("URI=\"([^\"]+)\"");
        for (auto line : lines) {
            line = line.trimmed();
            if (line.startsWith('#')) {
                auto match = uri.match(line);
                if (match.hasMatch()) {
                    const auto u = actual.resolved(QUrl(match.captured(1)));
                    const auto local = digest(u.toString()) + (line.startsWith("#EXT-X-MAP") ? ".mp4" : ".key");
                    t->segments.append({u, local}); line.replace(match.captured(1), local);
                }
            } else if (!line.isEmpty()) {
                auto u = actual.resolved(QUrl(line));
                auto extension = QFileInfo(u.path()).suffix().toLower();
                if (!QStringList{"ts", "m4s", "mp4", "aac", "mp3"}.contains(extension)) extension = "ts";
                auto local = digest(u.toString()) + '.' + extension;
                t->segments.append({u, local}); line = local;
            }
            t->playlist += line + '\n';
        }
        if (t->segments.isEmpty()) { taskError(t, "播放列表没有可下载的分片"); return; }
        nextSegment(t);
    });
}
void Backend::nextSegment(const std::shared_ptr<DownloadTask> &t) {
    if (t->paused || t->removed) return;
    auto dir = t->data.value("directory").toString();
    while (t->segment < t->segments.size() && QFileInfo(dir + '/' + t->segments[t->segment].second).size() > 0) ++t->segment;
    t->data["progress"] = double(t->segment) / t->segments.size(); emit downloadsChanged();
    if (t->segment == t->segments.size()) {
        QSaveFile file(dir + "/index.m3u8");
        if (!file.open(QIODevice::WriteOnly) || file.write(t->playlist.toUtf8()) < 0 || !file.commit()) { taskError(t, "离线播放列表保存失败"); return; }
        t->data["path"] = dir + "/index.m3u8"; t->data["state"] = "已完成"; persistTasks(); emit downloadsChanged(); return;
    }
    const auto seg = t->segments[t->segment]; const auto target = dir + '/' + seg.second;
    transfer(t, seg.first, target + ".part", false, [this, t, target] {
        if (!QFile::rename(target + ".part", target)) { taskError(t, "分片保存失败"); return; }
        ++t->segment; nextSegment(t);
    });
}
void Backend::transfer(const std::shared_ptr<DownloadTask> &t, const QUrl &url, const QString &path, bool resume, std::function<void()> done) {
    auto file = std::make_shared<QFile>(path);
    qint64 offset = resume && !t->data.value("etag").toString().isEmpty() ? QFileInfo(path).size() : 0;
    if (!file->open(offset ? QIODevice::WriteOnly | QIODevice::Append : QIODevice::WriteOnly | QIODevice::Truncate)) { taskError(t, file->errorString()); return; }
    QNetworkRequest req(url); req.setTransferTimeout(30000);
    req.setRawHeader("User-Agent", "Mozilla/5.0 SquirrelTV/0.1");
    req.setRawHeader("Referer", url.adjusted(QUrl::RemovePath | QUrl::RemoveQuery | QUrl::RemoveFragment).toEncoded() + '/');
    req.setRawHeader("Accept-Encoding", "identity");
    if (offset) { req.setRawHeader("Range", "bytes=" + QByteArray::number(offset) + '-'); req.setRawHeader("If-Range", t->data.value("etag").toByteArray()); }
    auto r = m_net.get(req); r->setReadBufferSize(256 * 1024); t->reply = r;
    auto valid = std::make_shared<bool>(false);
    connect(r, &QNetworkReply::metaDataChanged, this, [this, r, t, file, valid, offset, resume] {
        int status = r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (status >= 300 && status < 400) return;
        if (status == 200) { if (offset) { file->resize(0); file->seek(0); } *valid = true; }
        else if (status == 206 && r->rawHeader("Content-Range").startsWith("bytes " + QByteArray::number(offset) + '-')) *valid = true;
        else { taskError(t, QString("下载响应无效：HTTP %1").arg(status)); r->abort(); return; }
        if (resume) {
            auto etag = r->rawHeader("ETag"); if (etag.startsWith("W/")) etag.clear();
            t->data["etag"] = QString::fromLatin1(etag); persistTasks();
        }
    });
    connect(r, &QNetworkReply::readyRead, this, [this, r, t, file, valid] {
        if (!*valid || t->paused || t->removed) return;
        auto b = r->readAll(); if (file->write(b) != b.size()) { taskError(t, file->errorString()); r->abort(); }
    });
    connect(r, &QNetworkReply::downloadProgress, this, [this, t, resume, file](qint64 received, qint64 total) {
        if (t->paused || t->removed) return;
        t->data["bytes"] = file->size();
        if (resume) t->data["progress"] = total > 0 ? double(received) / total : 0;
        emit downloadsChanged();
    });
    connect(r, &QNetworkReply::finished, this, [this, r, t, file, valid, done] {
        file->close(); if (t->reply == r) t->reply.clear();
        if (t->removed || t->paused) { r->deleteLater(); return; }
        if (r->error() != QNetworkReply::NoError || !*valid || file->size() == 0) taskError(t, r->errorString());
        else done();
        r->deleteLater();
    });
}
void Backend::pauseDownload(int index) {
    if (index < 0 || index >= m_tasks.size()) return;
    auto t = m_tasks[index]; if (t->data.value("state") == "已完成") return;
    t->paused = true; t->data["state"] = "已暂停"; if (t->reply) t->reply->abort(); persistTasks(); emit downloadsChanged();
}
void Backend::resumeDownload(int index) {
    if (index < 0 || index >= m_tasks.size()) return;
    auto t = m_tasks[index]; if (t->reply || t->data.value("state") == "下载中" || t->data.value("state") == "已完成") return;
    runTask(t);
}
void Backend::removeDownload(int index) {
    if (index < 0 || index >= m_tasks.size()) return;
    auto t = m_tasks.takeAt(index); t->removed = true; if (t->reply) t->reply->abort();
    const auto root = QDir(m_dataDir + "/downloads").absolutePath();
    const auto id = t->data.value("taskId").toString();
    const auto expected = QDir::cleanPath(root + '/' + id);
    if (QRegularExpression("^[a-f0-9]{24}$").match(id).hasMatch() &&
        QDir::cleanPath(t->data.value("directory").toString()) == expected &&
        expected.startsWith(root + '/')) QDir(expected).removeRecursively();
    persistTasks(); emit downloadsChanged();
}
void Backend::playDownload(int index) {
    if (index < 0 || index >= m_tasks.size()) return;
    auto t = m_tasks[index]; if (t->data.value("state") != "已完成") return;
    openUrl(QUrl::fromLocalFile(t->data.value("path").toString()).toString());
}
