# 松鼠TV Windows 0.1.2 预览版

0.1.2：播放页标题显示影片、集数及片源；增加独立选集、线路和跨片源切换入口。
清晰度从 HLS 主列表读取真实分辨率/码率，没有多档的片源显示原画；切换保留进度。
默认 HLS 按 2.5 Mbps 上限选择可用档位，预读 30 秒（最多 96 MiB），缓存不足补足 2.5 秒后自动恢复。
用户暂停与缓冲等待分开显示，缓冲提示包含实际读取速度；暂停显示中央播放图标，恢复时短暂切换为暂停图标后隐藏。

0.1.1：默认改为中性深灰；播放器使用细进度条、轻量控制栏和倍速菜单。
点按方向键 → 快进 5 秒，按住超过 250 毫秒临时倍速，松开恢复原倍率；鼠标右键长按仍然保留。

技术栈：Qt Quick/QML、C++20、libmpv、SQLite。Windows x64。

## 使用

- 安装版：运行 Setup EXE，无需管理员权限。数据在 `%LOCALAPPDATA%/SquirrelTV/SquirrelTV/`。
- 免安装版：解压 ZIP 到可写目录，运行 `SquirrelTV.exe`。保留 `portable.txt`，数据存放于同目录 `data/`。更新时保留 `data/`。
- 支持本地文件、网络视频直链，以及用户配置的 MacCMS JSON 片源接口。

## 已实现

- 右键按住临时倍速，松开恢复原速度；移出画面释放、失去焦点及离开播放页也恢复。
- 双击全屏、空格暂停、方向键快进快退及音量、鼠标滚轮音量、剧场模式。
- 软解/硬解切换、常用倍速、画面比例、下一集、选集与播放线路切换。
- 四种配色主题、封面悬停动画、控制栏渐隐、减少动画。
- 片源分类、单片源搜索、分页、片源添加/移除/JSON 导入。
- 收藏、历史、续播、喜欢的时间点、资料导出。
- 直链下载、带强 ETag 的断点续传；有限点播 HLS 下载、复用已完成分片、本地播放列表、暂停继续删除。
- 安装包和包含运行库的免安装 ZIP 打包脚本。

## 待完成与限制

这是可运行的首版，尚未达到手机版全部功能对等：聚合搜索、排行、收藏分组、片源编辑/检测、内容分类过滤开关、网格列数偏好、DLNA 投屏与跨设备数据迁移仍待开发。
HLS 当前选择首条视频变体；支持普通分片、AES-128 key 和初始化段 URI 的本地化，暂不支持直播录制、字节范围分片、独立音轨合并与 SAMPLE-AES。
下载尚未加入手机端的多路加速与播放带宽优先策略。硬件解码效果依赖设备及渲染路径；首版不能代表所有显卡与片源的兼容性和性能。
当前使用 OpenGL 将 libmpv 画面合成到 Qt Quick，控制层与视频在同一场景中；尚未实现专门的 D3D 零拷贝路径。

## 构建

需要 MSVC 2022 x64、Windows SDK、CMake、Qt 6.8.3（qtbase、qtdeclarative、qtsvg）和 libmpv 开发包。
`scripts/build.ps1` 的参数可覆盖本机 Qt/MSVC/SDK 路径。
libmpv 放入 `.deps/mpv/`，需要 `include/mpv/*.h`、`mpv.lib`、`libmpv-2.dll`。

```powershell
./desktop/scripts/build.ps1
./desktop/scripts/package.ps1
```

缺少依赖时可先执行 `scripts/bootstrap.ps1`，下载固定版本的 Qt 与 libmpv；需要 Python、网络和 7-Zip。

开发回归使用 `build.ps1 -SelfTests` 编译可选测试入口。
安装 `imageio-ffmpeg` 后运行 `tests/make_media.py` 生成 MP4、HLS 及弱网测试媒体，再运行 `tests/fixture_server.py`。
设置 `SSTV_TEST_VIDEO`、`SSTV_TEST_SERVER`、`SSTV_TEST_REPORT` 后执行 `SquirrelTV.exe --self-test`。
测试涵盖真实解码、鼠标右键临时倍速和恢复、移出画面释放、页面切换恢复、空格暂停、跳转、双击全屏、四主题渲染、直链断点下载校验与离线 HLS 播放。
正式打包前重新执行不带 `-SelfTests` 的构建，不携带 QtTest。
