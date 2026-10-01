# 缓存功能迁移说明

## 来源与目标

2026-10-01，将 `711a7ce4b8d0b6a894d04dfd2500447f97a1fa40` 的缓存功能移入 `me-release-danmu-v3`，迁移前基点为 `ad2ef5fed`。所有修改保留在工作区，没有创建提交。

来源提交基于旧版 4.1.6 播放器。当前分支使用 5.6.3 的 `PlaybackService`、`PlayerManager`、`PlayerEngine` 和共用 `VodPlaybackController`，因此不能直接保留旧版 `Players`、`Setting`、弹幕 `Loader` 接口。冲突处理保留当前播放器与现有弹幕设置，再通过适配接入缓存功能。

## 用户入口与行为

- 手机设置页：在「播放设置」下面增加「缓存」，卡片沿用其他设置卡片的布局和背景。
- 电视首页：增加「缓存」入口。
- 播放页：增加缓存当前集按钮，手机端按钮尺寸与相邻按钮一致；弹幕右侧增加「缓存」按钮，打开右侧任务面板。
- 完整页面与侧栏共用任务列表，按剧集分组、折叠展开，保存展开状态；标题最多两行，状态放在第二行线路信息后。
- 任务支持缓存进度、暂停、继续、失败重试、删除以及已完成任务播放。删除需要确认，独立缓存键保证同 URL 的不同任务互不影响。
- 已移除的「弹控」按钮保持移除，继续使用当前弹幕设置界面。

## 下载与保存

`OfflineCache` 使用 Media3 `DownloadManager`、`DownloadHelper`、独立 `SimpleCache` 和索引。任务使用当前播放视频实际 URL、请求头、视频质量、音轨偏好及原始节目元数据。下载并发数为 2，支持 MP4、HLS、DASH；HLS/DASH 保存选中视频与音频所需的完整分片。直播、DRM 或不完整的轨道选择在创建任务前拒绝。

缓存保存在应用内部 `files/offline_media`，与在线播放的临时缓存分开，一般「清理缓存」不会删除已下载视频。任务身份由节目、线路、剧集 URL 和清晰度组成，每个任务有独立媒体缓存键。下载错误文案不保存带令牌的 URL、Cookie 等异常详情。

本次继续使用当前仓库已经纳入 Git 的播放器 AAR 和兼容模块，未引入对外部播放器源码目录的构建依赖。

## 播放器适配与原因

缓存继续打开现有 `VideoActivity`，复用其布局、播放服务和控制逻辑。共用控制器将缓存元数据变成原有剧集列表，播放请求通过 `OfflinePlayback` 进入同一 `PlayerManager`，而不调用站点的在线播放地址接口。

离线媒体必须从下载缓存中读取，因此由 Exo 引擎通过无网络上游的 `CacheDataSource` 播放。即使全局选择 mpv，缓存播放也使用 Exo；播放页的引擎切换入口在离线模式下不切换引擎，解码选择保留。格式错误不允许回退到在线播放 URL；解码恢复也继续使用同一缓存数据源。

保留同一播放器的暂停、快进、倍速、音视频轨道、横竖屏（手机端）、弹幕搜索/导入/设置和片头片尾功能。在线与缓存播放共用原站点的 `History` 数据库记录：缓存打开时读取最新在线进度，定期及退出时将缓存播放进度写回同一记录。当前分支的倍速仍沿用统一 `SpeedSetting`。

直接进入缓存页面时播放服务可能尚未连接，离线详情通过服务就绪队列投递，避免初始化期间访问空播放器。缓存媒体的播放键与宿主页面一致，保证服务所有权、旋转和进度跟踪正常。

## 弹幕与云搜

当前分支通过 Media3 `PlayerView` 的 URI 接口加载弹幕，旧的 `Loader` 已不存在。`OfflineDanmakuCache` 在专用弹幕 HTTP 客户端保存成功加载的弹幕，在网络失败时读取内部 `files/danmaku_saved` 文件；本地文件或内容 URI 导入沿用现有接口。

缓存任务保存原始解析 `Result` 和弹幕列表。离线页面需要动态解析入口时，从保存的配置恢复解析列表；扩展解析器按需加载同一提供方 jar。沿用当前分支的「解析」入口和弹出列表，配置存在时即显示，避免因离线视频无需视频解析而隐藏云搜。

点击缓存播放中的解析项，或在线播放中名称包含「弹幕」的解析项，只获取弹幕，不替换当前视频。云搜使用原始节目 URL、站点 key、线路 flag；返回的弹幕更新当前列表，并保存到离线任务偏好。云搜仍需要网络和可用解析服务；已保存的弹幕、视频可离线读取。

## 主要代码

| 位置 | 职责 |
| --- | --- |
| `offline/OfflineCache`、`OfflineVideo` | 下载、索引、数据源、请求元数据 |
| `offline/OfflineDownloadService` | 后台下载通知和网络恢复调度 |
| `offline/OfflineCacheActivity`、`OfflineCacheDialog`、`OfflineCacheList` | 完整页面、侧栏和共用分组列表 |
| `offline/OfflineIntegration`、`OfflinePlayback` | 在线缓存入口、离线剧集及历史适配 |
| `playback/vod/VodPlaybackController`、`VodHistoryPolicy` | 复用剧集选择、播放控制和历史保存 |
| `player/PlayerManager`、`player/exo/ExoPlayerEngine` | 缓存专用媒体源、解码及弹幕结果接入 |
| `player/parse/ParseJob`、`api/config/VodConfig` | 仅弹幕解析、持久配置恢复 |
| `offline/OfflineDanmakuCache`、`player/danmaku/DanmakuViewAdapter` | 当前弹幕接口的持久保存与断网读取 |

## 在线与缓存共用播放记录（2026-10-01）

为了最小改动，在线播放流程、数据库结构和历史列表保持原有实现。新增 `OfflineHistory` 作为缓存播放到在线历史的适配层：

1. 依据缓存任务内保存的原始 `History`，识别原站点、节目 ID 和配置 ID。查询原配置的记录，不使用可能已切换的当前配置 ID。
2. 打开缓存时读取最新在线进度。同集才能续播，另一集或不同原始剧集 URL 的进度不套用；选择不同缓存剧集时也重新匹配。
3. 播放页内部仍使用 `offline:<任务 ID>`；写回数据库时还原原始 key、线路、剧集 URL 和剧集名称，让在线历史能正常打开原站点节目。
4. 缓存播放约每 5 秒保存一次，切集和退出也保存；退出保存完成后刷新历史列表。片头片尾、画面比例等沿用共用记录，倍速沿用现有全局设置。
5. 使用同一串行保存队列，旧时间戳的异步写入不能覆盖较新的在线记录。不进行同名节目合并，避免影响其他站点的记录。
6. 不读取或迁移此前独立保存的缓存历史，不另建离线进度记录。在线记录尚未创建时，使用当前缓存任务携带的原始信息初始化，这是正常创建流程。无痕模式不写历史，未准备好的播放不覆盖有效进度。

当前在线历史按节目保存最近播放的一集，缓存沿用同一结构。点击指定缓存集时，不会使用另一集的时间点。删除下载文件不会删除已同步的在线播放记录。

## 验证

迁入源提交中的 6 项数据单元测试、13 项 Android 缓存测试及本地 MP4/HLS/DASH 测试媒体。旧测试针对控件 ID、播放器访问方式、解码行为、配置字段位置和倍速控件作了适配，迁移缓存功能时的验证结果：

- 手机端 19 项设备测试全部通过：13 项缓存测试，加上现有 6 项播放器、字幕兼容测试。
- 电视端 13 项缓存设备测试全部通过。
- 6 项缓存数据单元测试全部通过。
- 手机端、电视端 Debug 和 Release 四个构建全部通过；Release APK 沿用当前分支配置，未签名。

删除旧记录兼容处理后，保留 6 项历史同步设备测试及 1 项实际播放页往返续播测试。当前验证结果：

- 手机端：6 项历史同步测试及 14 项缓存/播放页测试全部通过。
- 6 项缓存数据单元测试通过。
- 本次简化后的手机端、电视端 Debug 构建全部通过。共享历史初版的两端 Release 构建和电视端设备测试此前已通过。

复现命令（需要 Java 21、当前项目对应 Android SDK，以及连接的 Android 设备或模拟器）：

```sh
bash gradlew :app:assembleMobileDebug :app:assembleLeanbackDebug :app:testMobileDebugUnitTest
bash gradlew :app:connectedMobileDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fongmi.android.tv.offline.OfflineCacheTest,com.fongmi.android.tv.offline.OfflineHistoryTest
bash gradlew :app:connectedLeanbackDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fongmi.android.tv.offline.OfflineCacheTest,com.fongmi.android.tv.offline.OfflineHistoryTest
```

## 使用边界

缓存没有自动容量淘汰，空间不足时需要在缓存列表删除内容。只有已完成任务可以播放；损坏或缺失的媒体报离线播放错误。站点签名 URL 过期时，可删除旧任务，再从正常在线播放页面重新缓存。应用卸载或清除数据会删除内部缓存。
