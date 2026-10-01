# 公开播放器源码适配

播放器依赖基于 [FongMi/media](https://github.com/FongMi/media) 的 `release-1.11.0-fongmi`，固定版本 `3c2cbe8ac742c2fe15eff52f03eeb3b1b648848d`。App 使用源码编译的 20 个 AAR，不混入另一版本的官方 Media3 AAR。

公开版本缺少 App 引用的较新接口，适配方式如下：

- 弹幕通过 `DanmakuViewAdapter` 使用已有的 `PlayerView` 接口，保留配置、数据源、实时发送和播放时钟同步。
- Exo 双字幕通过轨道选择器和独立字幕输出显示；普通字幕使用现有 `TextRenderer`，ASS 使用 `AssSubtitleRenderer`。
- `player_ass_bridge` 从随 mpv 打包的 `libmpv.so` 获取 libass 接口，用当前播放时间绘制 ASS 位图，支持样式、缩放和字幕延迟。桥接只支持现有库提供的 ARM32/ARM64 ABI；无法加载时保留标准字幕解码。
- mpv 字幕使用既有 `secondary-sid`、位置和样式参数。媒体源码补丁增加 `MpvPlayer.setSubtitleConfig`，把字体和双字幕配置交给现有的字幕控制器，并让 Media3 的主字幕标记读取 `sid`，避免把 mpv 的副字幕误认为主字幕；恢复默认设置时由它还原原始配置。
- 字体名称通过 SFNT name 表读取，兼容 TTF、OTF 和 TTC 首字体。

## 重编译依赖

需要 JDK 21、Android SDK 37、NDK `29.0.14206865`、CMake 3.22.1，以及可访问 Gradle/Maven 仓库的网络。FFmpeg 和 mpv 原生依赖沿用 media 仓库随附的源码、头文件和二进制。

```sh
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/android-sdk
bash player-compat/tools/build-media.sh
bash gradlew :app:assembleLeanbackDebug :app:assembleMobileDebug
```

脚本会创建独立的忽略目录、固定源码版本、应用 `patches/media-mpv-subtitle-config.patch`，用 media 自己的 Gradle 9.1 wrapper 构建，再复制全部 AAR 到 `app/libs`。已有的同版本源码目录可以作为脚本第一个参数传入。不要覆盖 AAR 后遗漏该补丁。

## 运行验证

```sh
bash gradlew :app:connectedLeanbackDebugAndroidTest
```

ARM64 设备测试包含原生 ASS 加载、动效、到期清除、时间回退、样式修改，以及 Exo 开关 libass 两种模式的双字幕输出；mpv 测试检查字幕轨道加载和实际原生副字幕选项。测试覆盖不等于所有影片、设备和外挂字体已验证。MKV 附件字体等特殊场景仍需真实片源回归。

本模块通过动态链接复用现有 mpv/libass，不额外打包第二份解码库。现有 AAR 的许可证与 NOTICE 文件保持在制品中。

## 本次验证结果（2026-10-01）

- 20 个播放器 AAR 使用固定源码及上述补丁重建成功，重建脚本已实际执行。
- `assembleLeanbackDebug`、`assembleMobileDebug`、`assembleLeanbackRelease`、`assembleMobileRelease` 全部成功。Release 已执行 R8 与资源压缩；本地没有正式签名配置，Release APK 未签名。
- ARM64 Android 15 模拟器运行 6 项测试，全部通过；ARM32 完成构建，未执行设备测试。
- 字体读取额外验证了实际 TTF/TTC 文件和损坏文件拒绝处理。
- 缓存页面、旋转、在线接口、特殊影片和真机硬解未在本次接口适配中做全面回归。

Debug APK 位于 `app/build/outputs/apk/leanback/debug` 与 `app/build/outputs/apk/mobile/debug`；Release APK 位于相应 `release` 目录和 `Release/apk`。
