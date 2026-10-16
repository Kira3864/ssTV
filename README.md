# 净影播放器 Android

导入自「净影播放器-手机版-源码-v1.16.zip」。

- 应用版本：1.16（versionCode 17）
- 包名：com.laoyang.jingying
- 开发语言：Kotlin
- Android：最低 API 26，编译及目标 API 34
- 构建环境：JDK 17、Gradle 8.7、Android Gradle Plugin 8.5.2

## 编译

使用 Android Studio 打开项目，配置 Android SDK 后同步 Gradle。
原压缩包中的《怎么编译成APK.txt》保留于项目根目录，可供参考。

本机 SDK 配置 `local.properties` 和签名文件 `keystore/` 未提交。
原构建脚本的 debug、release 构建均引用 `keystore/jingying.jks`；
编译前需要在本地配置签名文件，或按自己的签名方案调整 `app/build.gradle`。

本次仅导入源码，未验证 APK 编译和运行。
