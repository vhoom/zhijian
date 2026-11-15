---
date: 2026-11-14
---

# APK 构建说明

构建配置位于 [app/build.gradle](../app/build.gradle)。每次完整构建所选类型的 APK，会同时输出 `arm64-v8a`、`armeabi-v7a` 和 `universal` 三个独立安装包；选择其中一个安装即可。

[settings.gradle](../settings.gradle) 中的 Gradle 工程名为 `zhijian`。应用显示名称与 APK 文件前缀读取 `brand.xml` 的“止间”，应用 ID 为 `github.vhoom.zhijian`。

## 输出类型与文件名

| 构建类型 | 架构输出 | 文件名 |
| --- | --- | --- |
| Debug | ARM 64 位 | `止间_<版本号>_debug_arm64-v8a.apk` |
| Debug | ARM 32 位 | `止间_<版本号>_debug_armeabi-v7a.apk` |
| Debug | 通用 | `止间_<版本号>_debug_universal.apk` |
| Release | ARM 64 位 | `止间_<版本号>_release_arm64-v8a.apk` |
| Release | ARM 32 位 | `止间_<版本号>_release_armeabi-v7a.apk` |
| Release | 通用 | `止间_<版本号>_release_universal.apk` |

展示名称读取 `app/src/main/res/values/brand.xml` 中的 `app_name`，版本号沿用现有日期规则。文件名中的架构后缀来自 Android Gradle 插件的输出过滤器，没有 ABI 过滤器时使用 `universal`。

`splits.abi` 分别生成 `arm64-v8a` 和 `armeabi-v7a` 专用包，同时启用通用输出。项目没有设置全局 `ndk.abiFilters`；通用包保留构建输入中实际提供的全部架构，不能补出依赖本身未提供的原生库。

当前源码没有自带 `.so` 原生库。Java 和 XML 不因架构后缀而变成不同的 CPU 机器码，因此三份 APK 的内容和大小可能接近。架构拆分主要用于筛选原生库。

## Android Studio 构建调试包

1. 打开工程，准备与项目兼容的 Gradle JDK 和 Android SDK；Java 目标及 SDK 版本以 `app/build.gradle` 为准。
2. 点击 **File → Sync Project with Gradle Files**，同步构建配置。
3. 打开 **View → Tool Windows → Build Variants**，将 `app` 的变体设为 `debug`。
4. 在 **Build** 菜单中选择 APK 生成操作，通常位于 **Generate App Bundles or APKs → Generate APKs**；部分版本显示为 **Build Bundle(s) / APK(s) → Build APK(s)**。
5. 构建完成后点击通知中的 **locate**，或打开 `app/build/outputs/apk/debug/`，查找表中的三个 Debug APK。

ABI 是同一构建变体的输出过滤条件，Build Variants 面板仍使用 `debug`、`release`，无需逐一切换架构。运行按钮用于向当前设备部署适配的安装包；需要取得三种完整产物时，使用 APK 生成操作或下方的 `assembleDebug` 任务。

Debug APK 使用调试签名，适合开发和本机验证。

## Android Studio 构建签名正式包

1. 点击 **Build → Generate Signed App Bundle or APK**，选择 **APK**。
2. 选择 `app` 模块及已有签名文件；首次发布可通过 **Create new** 创建密钥和别名。
3. 选择 `release`、输出目录并完成向导。Gradle 按同一 ABI 配置生成 ARM 64 位、ARM 32 位和通用 APK。
4. 从向导指定的输出目录或完成通知进入产物位置，核对三个 Release 文件。

Release 已启用代码混淆和资源压缩。当前脚本没有配置发布签名，直接执行 `assembleRelease` 会生成未签名包；文件名中的 `release` 不代表已经签名。对外分发使用签名向导生成的产物，后续更新继续使用同一发布签名。

签名文件、密码和本机路径保持在版本控制之外。用于还原 Release 异常堆栈的文件位于 `app/build/outputs/mapping/release/mapping.txt`，随对应版本在本地妥善保留。

## 命令行入口

获得构建授权后，在仓库根目录运行：

| 目的 | PowerShell 命令 | 常规产物目录 |
| --- | --- | --- |
| 构建三种 Debug APK | `.\gradlew.bat :app:assembleDebug` | `app/build/outputs/apk/debug/` |
| 构建三种未签名 Release APK | `.\gradlew.bat :app:assembleRelease` | `app/build/outputs/apk/release/` |

Unix 环境将命令入口替换为 `./gradlew`。签名向导的导出位置以实际选择为准。

## 产物核对与验证范围

构建后核对输出文件及 `output-metadata.json`：ARM 64 位包应带 `arm64-v8a` ABI 过滤器，ARM 32 位包应带 `armeabi-v7a` ABI 过滤器，通用包不带 ABI 过滤器。通过 **Build → Analyze APK** 查看各包的 `lib/` 内容；没有原生库时，该目录可能不存在。

本次仅核对 Gradle 配置、当前 Android Gradle 插件的 ABI 拆分接口、文件命名和文档引用，未执行 Gradle 同步、编译、测试、签名或安装。实际产物数量及安装情况仍需按上述流程验证。
