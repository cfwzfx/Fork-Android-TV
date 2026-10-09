# 当前状态、问题与修改记录

最新补充：2026-10-08，历史记录集数文字最多四行显示与手机版 Release 打包，详见文末。本次修改基于 `fa10f63cf`，保留在工作区，未暂存、未提交。下方 2026-10-01 的内容保留为当时的记录。

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

- 20 个 `lib-*-release.aar` 来自 [FongMi/media](https://github.com/FongMi/media) 的 `release-1.11.0-fongmi` 分支，固定提交 `3c2cbe8ac742c2fe15eff52f03eeb3b1b648848d`，应用 [本地补丁](../player-compat/patches/media-mpv-subtitle-config.patch) 后构建。
- 原有 `forcetech-release.aar`、`hook-release.aar`、`jianpian-release.aar`、`thunder-release.aar`、`tvbus-release.aar` 保留，已校验内容与基准提交一致。这五个制品的来源不在本次重新推断。
- 所有 AAR 均可被 Git 跟踪；新增的 20 个 AAR 在本轮加入暂存区，没有提交。它们虽然由其他项目构建生成，但在当前 App 中属于必须保存的构建输入。
- [artifacts.json](../app/libs/artifacts.json) 记录全部 25 个制品的大小、SHA-256、来源，以及 media 补丁的散列。更新依赖后要同步更新此清单，避免制品与源码说明不一致。
- 完整制品已有许可证与 NOTICE 信息，不移除这些内容。

一般编译直接使用保存的 AAR。只有重新编译播放器依赖时才需要下载 media 源码；源码、构建缓存存放在忽略的 `.gradle/` 目录。具体步骤见 [播放器说明](../player-compat/README.md) 和 [重建脚本](../player-compat/tools/build-media.sh)。保留源码补丁和脚本，是为了让当前二进制依赖可以复现，而不仅是保存一组来历不明的 AAR。

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

## 历史记录集数文字最多四行显示（2026-10-08）

当前分支 `me-release-danmu-v3` 已通过 `git pull --ff-only` 更新至 `fa10f63cf`。按用户后续要求，将历史卡片中表示最近播放集数的 `remark` 文字从跑马灯改为静态多行显示：自动换行，最多四行，超过四行时在末尾显示省略号。

- 电视端 `HistoryPresenter` 和手机端 `HistoryAdapter`：在各自历史卡片 ViewHolder 中设置 `setSingleLine(false)`、`setMaxLines(4)` 和 `setEllipsize(END)`，覆盖共用布局的单行设置。
- 移除上一版专门用于跑马灯的选中状态、焦点监听、无限循环和回收清理逻辑，文字不再随焦点滚动。
- 删除模式和集数文字与影片名称相同的隐藏规则沿用原实现；历史数据、卡片点击及长按行为不变。修改仅作用于历史卡片，不更改其他影片列表的共用布局。

本次使用 JDK 21 和项目 Gradle wrapper 构建手机版 Release，并验证电视端 Java 编译。该 Gradle 构建流程与 Android Studio 使用的项目构建流程一致。

```powershell
.\gradlew.bat :app:assembleMobileRelease :app:compileLeanbackDebugJavaWithJavac --console=plain
```

上述构建结果为 `BUILD SUCCESSFUL`，手机版 Release 的 R8 压缩和打包、电视端 Debug Java 编译通过，`git diff --check` 通过。构建保留了已有的弃用 API、未检查操作及 Gradle 弃用提示。

手机版版本为 `5.6.3`（versionCode 563），按 ARM64、ARM32 分包输出至 `Release/apk/mobile-arm64_v8a.apk` 和 `Release/apk/mobile-armeabi_v7a.apk`。当前命令行构建未配置正式签名，产物为未签名 APK，不能直接安装。用户曾要求使用 Android Studio 中已有的正式签名配置继续打包，随后明确取消打包；签名步骤已停止，尚未交付正式签名包。

本次没有连接安卓设备，尚未实机验证长集数文字在不同屏幕、字体大小和卡片宽度下的换行与省略效果。两处 Java 修改和本记录文档均保留在工作区，未暂存、未提交。

## 模拟器选集错乱排查（2026-10-08）

用户暂停弹幕条数排查，要求在现有模拟器的“牧神记”上反复验证选集错乱，并明确保留 APP 和数据。本轮使用 `emulator-5554` 上已安装的手机版 Debug 5.6.3；源为“优汐┃搜搜”（UC），线路为“优汐智1”。未安装或卸载 APP，未清理应用数据、播放缓存、配置或数据库。操作前通过 `run-as` 只读备份数据库（包含 WAL）和 shared_prefs 至本机被 Git 忽略的 `.gradle/episode-investigation/app-data-before.tar`。

- 日志记录到第 2、3、42、43、44、45 集的多次选集请求；重复请求同一集时，源返回的直链路径和 HTTP Content-Length 一致，未捕获请求文件名与所点击集数不一致的情况。
- 第 43 集还核对了视频画面内的“第四十三集”片头，与所选文件名一致。其他集的请求、页面标签一致，不等于全部实际视频内容均已验证。
- 捕获一个独立的选集窗口异常：选中“41–50”页签时，下方显示 S01E81–S01E90；切到其他页签后列表恢复对应。截图保存为 `.gradle/episode-investigation/sheet.png`。尚未确定触发原因，以及它与用户描述的相邻集播放错乱是否相关。
- 本机配置未开启“预加载下一集”，因此不能用预加载假设解释本轮现象。此次没有创建下载任务，缓存内容错集尚未复现或验证。

本轮未确认用户报告的“第二集实际播放第三集”的根因，未修改播放或缓存代码，不能宣称问题已修复。日志、截图和诊断脚本只保存在被忽略的本机目录，不包含在提交中；日志与备份可能含源鉴权信息，不应公开上传。此前的历史文字四行显示改动继续保留，未提交。

### 全屏中部上下滑动的复现线索

用户补充复现步骤：全屏播放第 44 集，通过屏幕中部上下滑动切到第 43 集，观看一会后滑回第 44 集，或继续滑到第 45 集，随后可能播放错集。后续验证应以这条手势路径为准，不能用普通选集按钮的结果代替。

继续排查时，当前在线的是 API37.2 模拟器，未安装本项目 APP；原有 APP、配置和历史仍保留在 API35。启动 API35 后，另行只读备份了数据库和 shared_prefs 至 `.gradle/episode-investigation/gesture-data-before.tar`。打开历史中的第 44 集后，日志记录源 UC、线路“优汐智1”、请求文件 S01E44，但尚未进入全屏就发生原生崩溃：`SIGILL`，ARM64 调用位置为源缓存中的 `cache/danMugo_v8.so`，播放 Activity 被关闭。崩溃日志保存在 `.gradle/episode-investigation/gesture-source-crash.txt`。本轮启动的 API35 模拟器进程后续也退出；尚不能确定两种退出的因果关系。

因此尚未完成新增手势步骤的实际复现，也未确认选集错乱根因。没有卸载、重装 APP、清理数据、替换源脚本或修改播放代码；待确认用户实际复现的模拟器和线路，并恢复可正常播放的测试环境后继续。

## 牧神记弹幕始终显示 3600 条排查（2026-10-09）

用户重新要求测试弹幕条数问题。本轮连接 `emulator-5554`，从当前历史记录打开“牧神记”（优汐┃搜搜，S01E44）。在弹幕加载完成前，应用于设备时间 11:40:04 再次发生 `SIGILL`，ARM64 调用位置为 `cache/danMugo_v8.so` 的 `0x570fa0`，随后回到历史页面。因此本轮未实际捕获“成功加载 3600 条弹幕”的提示或对应原始响应，不能声称已复现条数问题或确定根因。

- 已只读备份本轮数据库（含 WAL）和 shared_prefs 至忽略目录 `.gradle/danmaku-inspection/current/data.tar`；未卸载、重装、清理 APP 数据或替换源。
- 当前偏好中 `danmaku_load=true`、`danmaku_show=true`；应用私有目录中没有 `files/danmaku_saved`，无法从该目录取得此次弹幕响应。
- 核对 `DanmakuViewAdapter` 和仓库 `lib-ui-danmaku-release.aar` 字节码：完整加载回调传递的是解析结果数组长度，提示按此实际数量显示；这条路径没有写死 3600。库内 3600 常量用于时间转换，不是条数上限。以上只说明所检查的仓库实现，不能代替实际响应核对。
- 既有播放日志中的源弹幕地址是应用本地代理 `127.0.0.1:9978/proxy?do=danmu`，由源脚本提供；尚未取得当前可正常播放时代理返回的 XML/JSON，因此不能断定源采样、服务端限制或解析丢失中的哪一种成立。

诊断日志与备份保存在被 Git 忽略的本机目录，可能包含源鉴权信息，不应公开上传。待用户将模拟器停在能正常显示 3600 条提示的播放页面后，再抓取同一次加载的原始数据并核对条数。未修改弹幕运行时代码，未提交。

## 首页加载后崩溃及重新启动失败排查（2026-10-09）

用户报告从下载通知进入缓存页面后，后续进入 APP 可看到首页数据，但随即崩溃；出现两个按钮，点击类似“重新加载”的按钮后再也无法进入。已确认仓库 `CrashActivity` 的两个按钮为“重新启动”和“错误信息”，但尚未取得本次故障设备的异常堆栈，不能将本次故障与之前源 `danMugo_v8.so` 原生崩溃视为同一问题。

本轮先将当前连接的 `emulator-5554` 的数据库（含 WAL）、shared_prefs 和现有日志只读备份至忽略目录 `.gradle/startup-investigation`。该模拟器可以从历史返回首页，也完成了一次停止进程后的冷启动：首页“豆豆┃片单”数据正常显示，检查时没有新增应用崩溃记录。模拟器没有下载任务，本轮未复现用户描述的下载通知路径及持续启动失败，测试结果不代表故障设备已恢复。

已检查下载通知 PendingIntent、`OfflineCacheActivity`、首页初始化下载服务以及 `CrashActivity` 的重新启动与 `crash` 标记逻辑；没有足够证据确定根因，因此未修改运行时代码。待确认故障发生在当前模拟器还是实体手机，并取得该设备本次异常记录后继续。未卸载、重装或清理应用数据，未提交代码。

### 实体机 Release 启动崩溃已复现

用户连接实体机后，本轮确认设备为小米 `23127PN0CC`（houji）、Android 16，安装包为 `com.fongmi.android.tv` Release 5.6.3（versionCode 563、targetSdk 37，最后更新于 2026-10-08 01:13:06）。先保存现有 main/system/crash 日志，再通过启动器 MAIN/LAUNCHER Intent 打开 APP，没有安装新包或变更应用数据。

2026-10-09 19:19:52 启动时成功复现：应用进程被 `SIGABRT` 终止，系统退出记录为 `APP CRASH(NATIVE)`，错误为 `JNI DETECTED ERROR IN APPLICATION: obj == null`，发生在 `CallObjectMethod`，原生入口 `com.github.catvod.spider.DexNative.getSpider(Object, String)`。调用链包含外部源 `DouDouGuard` 构造函数、`BaseSpiderGuard`、`Init.getSpider`、宿主创建 Spider 的反射调用和 `Site.spider`。此前 19:18:16、19:18:59、19:19:11 的记录也是相同错误，支持持续启动失败是重复触发同一源原生错误。本次故障栈没有落在下载通知、缓存页面或下载服务中，不能据此判断所有历史崩溃都与缓存无关。

源原生库位于应用私有缓存的 `.ftyfnw*` 路径，所在源 JAR 缓存名为 `2f2007552ef33f04e7c709c71ed05d93`。当前本机同地址源 JAR 的参考字节码显示 `Init.getSpider` 将 `Init.loader()` 传入 native；`Init.init` 使用 native `getLoader` 初始化内部加载器。实体机 Release 无 `run-as` 访问权限，尚未取得实体机私有源文件来验证其内容完全一致，也未确认是内部加载器为空、native 中其他对象为空或该源初始化的其他错误。不能把这点进一步归因于 targetSdk、安全策略、源文件损坏或某项宿主改动。

已检查本机 `mobileRelease/mapping.txt`，其 R8 map id 与实体机日志的 `8326c4a3330f23cae9590327a7ac1a121a5a1662f90b1100e0c24a4e7f95b71e` 一致，后续可用于还原宿主混淆堆栈；源的 native 内部实现不包含在该 mapping 中。原生 JNI 错误直接终止进程，不能靠 Java `catch(Throwable)` 或现有“重新启动”按钮修复。当前源码的首页恢复标记是在 `site.spider()` 之后读取，也无法阻止 Spider 构造阶段的原生崩溃。

本轮已定位此次启动崩溃发生在外部首页源创建过程，尚未修复源内部错误或恢复手机可进入状态。日志与系统退出记录保存在 `.gradle/startup-investigation/physical`，未公开上传；没有尝试读取或备份无权限的手机私有数据库，没有卸载、清理数据、切换源或提交代码。

### 尝试性初始化保护与 Release 诊断日志

按用户授权修改宿主代码，由用户自行打 Release 包覆盖安装验证，不安装测试包、不卸载或清理原 APP。

- 新增 `SpiderInitializer`：调用源公开的 `init(Context)`；真实初始化异常向上传递，不再由 `JarLoader` 吞掉后继续登记加载器。源没有 Init 类或 init 方法时保持可加载。
- 源若公开静态 `loader()` 且声明返回 `ClassLoader` 子类型，初始化后检查其返回值；为空时抛出 Java 异常，阻止继续创建 Spider。没有该方法、同名方法返回其他类型时不作这项检查。此检查针对本轮参考源字节码中传入 native 的内部加载器，不能保证拦截所有 native 内部空对象错误。
- `SiteApi.homeContent` 的已有 `crash` 标记检查前移到 `site.spider()` 之前：标记为真时跳过一次首页源创建。没有新增持久化原生崩溃检测，因此这一现有标记不能独立解决所有 native 崩溃循环。
- `TV-SpiderInit` 日志在 Release 下同样执行，覆盖 JAR 初始化开始/失败/完成、Init 类定位、init 调用前后、内部 loader 检查前后、Spider 构造前后及 site init 前后。记录 JAR 哈希、源键哈希、文件长度、只读状态、类名和异常类型；新增日志不输出源 URL、ext、密码或鉴权参数。现有项目未配置移除这些 `Log.i/w/e` 调用的规则。本轮仅完成 Java 编译，最终 R8 后行为仍需用户 Release 包验证。

验证：4 项初始化边界测试及原有 6 项 OfflineVideo 单元测试全部通过。用保留原先“吞掉初始化异常、不检查内部加载器”行为的独立负向对照运行同样 4 项测试，空加载器和初始化异常两项按预期失败。`testMobileDebugUnitTest`、`compileMobileReleaseJavaWithJavac`、`compileLeanbackDebugJavaWithJavac` 最终运行均成功，保留已有弃用提示；没有执行安装或完整 Release 打包。原实体机 Release 对应的 mapping 已另存忽略目录，避免用户后续构建覆盖后丢失原崩溃还原依据。

测试建议：用原签名、相同包名覆盖安装，首次启动时保持 ADB 连接。若首页不再崩溃但加载失败，可进入设置选择其他源；若仍崩溃，抓取 `TV-SpiderInit` 的最后阶段与 crash buffer，确认是 native getLoader 内部、内部 loader 为空还是 Spider 构造中其他错误。当前改动是宿主边界保护和诊断，不代表源内部问题已经彻底修复。工作区修改及本记录未提交。

### 用户覆盖安装后的实体机启动验证

确认实体机应用最后更新时间为 2026-10-09 19:31:00，用户覆盖安装的新 Release 中 `TV-SpiderInit` 日志实际保留。保存原日志后启动 APP，并进行两次停止进程后的冷启动；没有卸载、清理应用数据或修改配置。

第一次启动（19:31:41）源 JAR 的 `init` 正常返回，内部 loader 为非空 `dalvik.system.DexClassLoader`，首页只显示源标题、内容为空；没有 Spider 构造日志，表现与已有 `crash` 标记跳过一次首页请求一致，但 Release 私有偏好无法直接读取，不能将其当作已直接验证的标记值。

第二次（19:32:25）和第三次（19:32:52）冷启动均记录完整的 `spider_construct_returned class=com.github.catvod.spider.DouDouGuard`、`spider_site_init_start`、`spider_ready`，UI 显示实际首页分类和影片列表，进程保持存活。抓取日志时最后一条原生崩溃仍为旧包的 19:19:52，没有新增崩溃。

因此当前新包已能正常进入并加载首页，且不能将结果仅归因于跳过首页源。本轮没有出现空 loader 拒绝或初始化失败日志，不能据两次正常加载断定某条保护分支已实际拦截原问题，也不能证明间歇性故障彻底修复。下载通知进入缓存页的原始完整操作路径尚未回归。日志、UI 快照与退出记录保存在 `.gradle/startup-investigation/physical/patched`；本轮未继续改动运行时代码，记录未提交。
