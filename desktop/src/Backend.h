#pragma once
#include <QObject>
#include <QNetworkAccessManager>
#include <QSqlDatabase>
#include <QVariantList>
#include <functional>
#include <memory>

struct DownloadTask;
class Backend : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantList sources READ sources NOTIFY sourcesChanged)
    Q_PROPERTY(QVariantList videos READ videos NOTIFY videosChanged)
    Q_PROPERTY(QVariantList categories READ categories NOTIFY categoriesChanged)
    Q_PROPERTY(QVariantList history READ history NOTIFY libraryChanged)
    Q_PROPERTY(QVariantList favorites READ favorites NOTIFY libraryChanged)
    Q_PROPERTY(QVariantList marks READ marks NOTIFY libraryChanged)
    Q_PROPERTY(QVariantList downloads READ downloads NOTIFY downloadsChanged)
    Q_PROPERTY(QVariantMap detail READ detail NOTIFY detailChanged)
    Q_PROPERTY(QVariantMap current READ current NOTIFY currentChanged)
    Q_PROPERTY(bool busy READ busy NOTIFY busyChanged)
    Q_PROPERTY(QString status READ status NOTIFY statusChanged)
    Q_PROPERTY(QString dataDir READ dataDir CONSTANT)
    Q_PROPERTY(int page READ page NOTIFY videosChanged)
    Q_PROPERTY(int pages READ pages NOTIFY videosChanged)
    Q_PROPERTY(QVariantList qualities READ qualities NOTIFY qualityChanged)
    Q_PROPERTY(QVariantList alternatives READ alternatives NOTIFY alternativesChanged)
public:
    explicit Backend(const QString &dataDir, QObject *parent = nullptr);
    ~Backend() override;
    QVariantList sources() const { return m_sources; }
    QVariantList videos() const { return m_videos; }
    QVariantList categories() const { return m_categories; }
    QVariantList history() const { return m_history; }
    QVariantList favorites() const { return m_favorites; }
    QVariantList marks() const { return m_marks; }
    QVariantList downloads() const;
    QVariantMap detail() const { return m_detail; }
    QVariantMap current() const { return m_current; }
    bool busy() const { return m_busy; }
    QString status() const { return m_status; }
    QString dataDir() const { return m_dataDir; }
    int page() const { return m_page; }
    int pages() const { return m_pages; }
    QVariantList qualities() const { return m_qualities; }
    QVariantList alternatives() const { return m_alternatives; }
    Q_INVOKABLE void findAlternatives(int source);
    Q_INVOKABLE void switchSource(const QVariantMap &video, double position);
    Q_INVOKABLE void browse(int source, const QString &keyword = {}, const QString &category = {}, int page = 1);
    Q_INVOKABLE void inspect(const QVariantMap &video);
    Q_INVOKABLE void playEpisode(int line, int episode, double start = -1);
    Q_INVOKABLE void playCurrent(int line, int episode, double start) { m_detail = m_current; playEpisode(line, episode, start); }
    Q_INVOKABLE void openUrl(const QString &url);
    Q_INVOKABLE void remember(double position, double duration);
    Q_INVOKABLE void favorite(const QVariantMap &video);
    Q_INVOKABLE bool isFavorite(const QVariantMap &video) const;
    Q_INVOKABLE void mark(double position);
    Q_INVOKABLE void restore(const QVariantMap &entry);
    Q_INVOKABLE void addSource(const QString &name, const QString &api);
    Q_INVOKABLE void removeSource(int index);
    Q_INVOKABLE void importSources(const QString &file);
    Q_INVOKABLE void exportLibrary(const QString &file);
    Q_INVOKABLE void downloadEpisode(int line, int episode);
    Q_INVOKABLE void pauseDownload(int index);
    Q_INVOKABLE void resumeDownload(int index);
    Q_INVOKABLE void removeDownload(int index);
    Q_INVOKABLE void playDownload(int index);
    Q_INVOKABLE QVariant setting(const QString &key, const QVariant &fallback) const;
    Q_INVOKABLE void setSetting(const QString &key, const QVariant &value);
    Q_INVOKABLE void revealData();
signals:
    void sourcesChanged(); void videosChanged(); void categoriesChanged(); void libraryChanged();
    void downloadsChanged(); void detailChanged(); void currentChanged(); void busyChanged(); void statusChanged();
    void playRequested(const QString &url, double start);
    void qualityChanged(); void alternativesChanged();
private:
    using Reply = std::function<void(const QByteArray &, const QString &, const QUrl &)>;
    void get(const QUrl &url, Reply callback);
    void resolve(const QString &url, std::function<void(QString)> callback);
    void discoverQualities(const QString &url, int generation);
    QVariantList m_qualities, m_alternatives;
    int m_alternativeGeneration = 0;
    void message(const QString &text);
    QVariant read(const QString &key, const QVariant &fallback) const;
    void write(const QString &key, const QVariant &value);
    void persistTasks();
    void runTask(const std::shared_ptr<DownloadTask> &task);
    void hls(const std::shared_ptr<DownloadTask> &task, const QUrl &url, int depth = 0);
    void nextSegment(const std::shared_ptr<DownloadTask> &task);
    void transfer(const std::shared_ptr<DownloadTask> &task, const QUrl &url,
                  const QString &path, bool resume, std::function<void()> done);
    void taskError(const std::shared_ptr<DownloadTask> &task, const QString &error);
    QNetworkAccessManager m_net;
    QSqlDatabase m_db;
    QString m_dataDir, m_status;
    QVariantList m_sources, m_videos, m_categories, m_history, m_favorites, m_marks;
    QVariantMap m_detail, m_current;
    QList<std::shared_ptr<DownloadTask>> m_tasks;
    bool m_busy = false;
    int m_generation = 0, m_detailGeneration = 0, m_playGeneration = 0, m_page = 1, m_pages = 1;
};
