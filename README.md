# ssTV · 松鼠TV

松鼠TV 是一款支持片源配置、影片搜索、在线播放、观看历史和离线缓存的播放器。

## 项目信息

- 项目名：ssTV
- 应用名：松鼠TV
- 版本：0.1（versionCode 18）
- 包名：`com.songshu.sstv`
- 语言：Kotlin
- 最低系统 API：26；编译和目标 API：34
- 构建工具：JDK 17、Gradle 8.7、AGP 8.5.2、Kotlin 1.9.24

## 编译

准备 JDK 17 和 API 34 SDK，在本地 `local.properties` 中配置 `sdk.dir`。

```shell
# Windows
.\gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

调试包输出至 `app/build/outputs/apk/debug/app-debug.apk`，使用本机调试签名。
详细步骤见《怎么编译成APK.txt》。

## 发布签名

将自己的密钥保存在本地，并在用户目录的 `.gradle/gradle.properties` 中设置：

```properties
SSTV_STORE_FILE=keystore/sstv.jks
SSTV_STORE_PASSWORD=你的密钥库密码
SSTV_KEY_ALIAS=你的密钥别名
SSTV_KEY_PASSWORD=你的密钥密码
```

密钥路径相对于项目根目录，也可使用绝对路径。密钥别名及密码必须与实际密钥一致。
配置完整后运行 `assembleRelease` 生成签名发布包；未配置时生成未签名发布包。
签名文件、本机 SDK 配置和构建产物均不提交到仓库。

## 安装说明

包名已经改为 `com.songshu.sstv`，系统会将松鼠TV视为新应用。
旧应用的设置、观看历史和缓存不会自动迁移。

## 内容来源

播放内容来自用户配置的第三方接口。松鼠TV本身不提供、不存储、不代理影片。

## 外观与体验

设置页「外观与动效」提供四种风格，选择后自动保存，返回其他页面也会同步：

- **曜石影院**：深黑朱红、适中圆角，适合沉浸观影。
- **极光漫游**：靛蓝背景、薄荷高亮、大圆角与柔和渐变。
- **晴日手帖**：暖米白、陶土色、衬线字体与小圆角，系统栏同步变浅。
- **像素终端**：墨绿背景、荧光绿、等宽字体与近直角控件。

可开启「减少动画」关闭页面和封面的渐入；也遵循系统动画设置。
首页与搜索页会根据屏幕宽度调整影片列数。首页筛选可展开收起，列表支持滑动分页。
底部导航包含首页、找片、播放、我的。竖屏视频置顶，下面显示选集、换源和影片信息；横屏进入全屏播放。

## 检查与回归测试

```shell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

回归测试覆盖多主题页面加载、外观偏好保存、同名片源选择、快速切换分类、
HLS 相对分片下载及多任务并发写入缓存索引。测试使用本地模拟接口，无需外部影视片源。
构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`。
