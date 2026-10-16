# ssTV · 松鼠TV

松鼠TV 是一款支持片源配置、影片搜索、在线播放、观看历史和离线缓存的播放器。

## 项目信息

- 项目名：ssTV
- 应用名：松鼠TV
- 版本：1.16（versionCode 17）
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
