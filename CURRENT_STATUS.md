# 当前状态、问题与修改记录

记录日期：2026-10-01。当前分支 `me-release-danmu-v3`，基准提交 `c616c0aa3`（Fix proxy selection across redirects）。以下修改仍在当前工作区，本轮没有创建 Git 提交。

## 目标与范围

尽量保留当前在线播放、缓存播放及页面功能，针对公开播放器源码与 App 接口不一致的问题完成适配。播放器修改集中在 `app/src/main/java/com/fongmi/android/tv/player/`、`player-compat/` 和必要的构建配置。此前已经存在的业务、弹幕和页面修改保留，没有通过回退页面或删除功能来换取编译成功。

本次额外整理 Git 忽略规则，将构建必需的 `app/libs` 文件纳入版本管理，让项目依赖可随仓库保存；不把 APK、源码下载缓存、SDK 路径和签名密钥混入版本管理。

## 问题、修改和原因

| 问题 | 当前修改 | 为什么这样处理 |
| --- | --- | --- |
| `app/libs` 缺少配套播放器 AAR，App 引用了较新的定制接口 | 从固定版本 FongMi/media 构建 20 个播放器 AAR，保留原有 5 个 AAR | 普通官方 Media3 包不包含此项目使用的全部定制能力；统一使用同一套源码制品，避免混用版本 |
| 公开 media 源码缺少 `DanmakuPlayerViewController` | 新增 `DanmakuViewAdapter`，调用已有 `PlayerView` 的弹幕接口 | 保留配置、数据源、即时发送和播放器时钟同步，页面只替换控制器类型 |
| Exo 的 libass 与双字幕封装在公开源码中缺失 | 增加双字幕轨道选择、独立字幕输出和 ASS 渲染器；`player-compat` 动态调用现有 `libmpv.so` 中的 libass | 普通文本字幕与 ASS 分别使用合适的渲染路径，保留 ASS 动效、时间调整和双字幕，不再依赖缺失的封装类 |
| MKV ASS 数据中的时间是相对当前字幕样本的时间 | 将时间与样本时间戳合并后送入 libass，处理空字符终止 | 避免后面的字幕错误地从影片零时刻开始显示 |
| mpv 双字幕和字体接口形式与 App 不一致 | 通过 `secondary-sid`、字幕位置及样式参数适配；给 media 增加 `setSubtitleConfig` 小补丁，主字幕标记读取 `sid` | 复用已有原生能力和字幕控制器，正确区分主、副字幕，并由控制器在恢复默认时还原配置 |
| 字体名称读取依赖缺失的封装类 | 使用 `FontFamilyReader` 读取 SFNT name 表，支持 TTF、OTF 和 TTC 的首字体 | 保留字体导入与字体族设置，减少对不可取得接口的依赖 |
| 首次启动未授予共享存储权限时，字体配置写入失败 | `AndroidFontConfig` 优先保留共享目录配置，写入失败时回退应用内部文件目录 | 允许字幕渲染在尚未授权共享存储的场景初始化 |
| 本地没有正式签名配置时，Gradle 配置阶段报错 | 签名配置和 APK 签名后处理按实际配置是否存在启用 | Debug 可正常构建；缺少正式密钥时允许产生未签名 Release，发布前仍需配置自己的密钥 |
| Chaquopy 找不到适用的宿主 Python | 从本地 `local.properties` 读取可选的 `python.buildPython`，项目 Python 版本仍为 3.10 | 让每台机器指定本地解释器，不把个人绝对路径写入构建脚本；此前 pip/cssselect 报错不应归因于 Android Media3 缺失，本机最新完整构建的 Python 依赖步骤已通过 |
| 原 ignore 使用 `*build`、`*.properties` 和两处 `lib-*.aar` 排除规则，且漏掉生成文件 | 改为明确的构建目录、本地配置和产物规则，移除 AAR 排除 | 保留 `buildSrc`、Gradle 项目属性和构建输入，同时减少无关文件出现在 Git 状态中 |

工作区保留了历史记录对应的弹幕时间偏移配置和滚动行数配置。按用户后续要求，已移除手机版播放器下方重复的“弹控”按钮、专用 `DanmakuControlDialog`、对应布局和仅供它使用的两个 PlayerManager 方法；相关调节继续使用现有“弹幕”设置面板，保留共享配置和历史数据。此前缓存页面、设置卡片、播放入口与动态解析入口的调整不是本次播放器适配新增的内容；当前记录不把它们列为已全面回归的功能。

## libs 的来源与保存方式

`app/libs` 共 **25 个 AAR**，合计约 **53 MiB**：

- 20 个 `lib-*-release.aar` 来自 [FongMi/media](https://github.com/FongMi/media) 的 `release-1.11.0-fongmi` 分支，固定提交 `3c2cbe8ac742c2fe15eff52f03eeb3b1b648848d`，应用 [本地补丁](player-compat/patches/media-mpv-subtitle-config.patch) 后构建。
- 原有 `forcetech-release.aar`、`hook-release.aar`、`jianpian-release.aar`、`thunder-release.aar`、`tvbus-release.aar` 保留，已校验内容与基准提交一致。这五个制品的来源不在本次重新推断。
- 所有 AAR 均可被 Git 跟踪；新增的 20 个 AAR 在本轮加入暂存区，没有提交。它们虽然由其他项目构建生成，但在当前 App 中属于必须保存的构建输入。
- [artifacts.json](app/libs/artifacts.json) 记录全部 25 个制品的大小、SHA-256、来源，以及 media 补丁的散列。更新依赖后要同步更新此清单，避免制品与源码说明不一致。
- 完整制品已有许可证与 NOTICE 信息，不移除这些内容。

一般编译直接使用保存的 AAR。只有重新编译播放器依赖时才需要下载 media 源码；源码、构建缓存存放在忽略的 `.gradle/` 目录。具体步骤见 [播放器说明](player-compat/README.md) 和 [重建脚本](player-compat/tools/build-media.sh)。保留源码补丁和脚本，是为了让当前二进制依赖可以复现，而不仅是保存一组来历不明的 AAR。

## 忽略规则

根目录 `.gitignore` 覆盖所有模块的 `build/`、`.gradle/`、`.cxx/`、`.externalNativeBuild/` 和 `.kotlin/`；忽略 `Release/`、App 各变体的 `release/`/`debug/` 导出目录、APK/AAB/APKS、网站生成目录 `docs/`、Python 缓存、IDE 元数据、日志、临时文件及 `.DS_Store`。

`local.properties`、`*.jks`、`*.keystore` 继续忽略。`gradle.properties`、Gradle wrapper 属性、版本目录、源代码、布局、测试、`buildSrc`、播放器补丁与 libs 制品保留。`app/libs/.gitignore` 不再排除播放器 AAR。

忽略规则只影响未跟踪文件，已经跟踪的文件不会因此从仓库删除。本次没有删除已有库文件，也没有撤销之前暂存的代码修改。

## 构建环境与复现

- App：JDK 21，Android SDK 37，最低 API 24，AGP 9.3.1，App Gradle wrapper 9.7.1。
- 原生桥接：NDK `29.0.14206865`，CMake 3.22.1；当前打包 ARM64 与 ARM32。
- Chaquopy：Python 3.10，可在本地 `local.properties` 配置 `python.buildPython`。
- media 源码重编译使用它自己的 Gradle 9.1 wrapper；曾用 App 的较新 Gradle 编译 media 时遇到 Kotlin 注解相关构建冲突，因此分开使用 wrapper。

```sh
# 使用仓库内保存的 AAR 编译应用
bash gradlew :app:assembleLeanbackDebug :app:assembleMobileDebug

# 编译 Release；正式签名需要本地密钥配置
bash gradlew :app:assembleLeanbackRelease :app:assembleMobileRelease

# 在 ARM64 Android 设备或模拟器上验证播放器适配
bash gradlew :app:connectedLeanbackDebugAndroidTest

# 可选：重新构建播放器 AAR，需要 JDK/SDK/NDK 和依赖下载网络
bash player-compat/tools/build-media.sh
```

## 已验证结果与剩余范围

2026-10-01 的最终播放器适配验证：

- 20 个 media AAR 重建成功，重建脚本已实际执行。
- 电视版、手机版的 Debug 与 Release 均构建成功，Release 的 R8 与资源压缩通过。本地没有正式签名，Release APK 未签名。
- ARM64 Android 15 模拟器的 **6 项测试全部通过**：原生 ASS 生命周期及空轨道；ASS 动效、到期清除、时间回退和颜色修改；Exo 普通字幕模式双字幕；Exo libass 模式双字幕；MKV ASS 相对时间转换；mpv 副字幕轨道选择及关闭选项。
- 字体读取额外检查了实际 TTF、TTC 文件，并验证损坏文件被拒绝。
- ARM32 只完成构建，没有执行设备测试。真机硬解、特殊 ASS 片源、MKV 附件字体、缓存页面、旋转和线上解析接口尚未全面回归，不能据上述测试声称全部功能 100% 等同于原始定制播放器。
- 之前单独运行的 CatVod 单元测试曾有 1 项 macOS 网络绑定异常（`Can't assign requested address`）；不是播放器 Java 编译错误，也没有在本次整理 ignore/libs 时修复。上述 6 项通过仅指播放器设备测试，并非整个项目所有测试通过。

本轮仅整理 ignore、保存 libs 制品和补充记录，不改变运行时代码，因此不重复执行完整构建；检查重点是必需文件可跟踪、生成文件被忽略、libs 散列与清单一致。

本轮检查结果：25/25 个 AAR 已进入 Git 索引，工作区与暂存区散列一致，ZIP 完整性检查通过；12 个生成或本地路径按预期被忽略，34 个必需输入路径保持可跟踪。仓库原有已暂存的两个弹幕 Java 文件有末尾空行提示，本轮未改动这些文件的暂存内容。

## 后续调整：移除重复弹控入口（2026-10-01）

“弹幕”面板已包含时间调整、滚动行数等控制项，因此移除播放器下方重复的“弹控”入口及其专用实现，减少重复操作入口。没有删除共用弹幕配置、历史偏移数据或弹幕设置面板。本次仅移除界面和无其他调用的专用方法，不新增功能测试；`assembleMobileDebug` 与 `assembleLeanbackDebug` 均已成功，资源绑定与 Java 引用的编译检查通过。

## 分支构建输入完整性（2026-10-01）

除 25 个 AAR 之外，全部必需的 `player-compat` 源码、补丁、脚本、Java 兼容类、构建配置与设备测试也已加入暂存区。只保存 AAR 而漏掉这些接口适配源码，无法保证在其他机器编译成功。普通 App 构建不需要再克隆 media、mpv、mpv-android 或 CatVodSpider，也不需要运行 `build-media.sh`。该脚本仅供以后修改或重建播放器依赖使用。

其他机器仍需常规 Android 构建工具（JDK、SDK、NDK、CMake、Python），以及首次构建时的 Gradle/Maven/Python 包下载。这里保证的是播放器仓库与定制二进制依赖随本分支保存，不是无 SDK 或完全离线构建。

Git 暂存不等于分支历史：只有提交并推送后，其他机器才能通过拉取分支取得本次修改。是否提交并推送正等待用户确认，因为此前明确要求不做提交。

暂存内容的干净构建验证已完成：使用 `git checkout-index` 导出全部暂存文件到独立目录，只添加本机 SDK 和 Python 路径，不复制原工作区的 `.gradle`、播放器源码下载目录或构建产物，然后运行 `assembleLeanbackDebug` 与 `assembleMobileDebug`。两项构建均成功（38 秒，使用本机常规 Gradle 依赖缓存）。这验证了当前暂存的构建输入完整，但不表示其他机器首次下载依赖也能在相同时间完成。
