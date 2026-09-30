# 缓存功能及播放器复用改动说明

## 1. 文档范围

本文记录本次缓存功能开发、界面调整、离线播放复用和弹幕云搜修复的最终实现，供后续维护、测试和继续开发使用。

以当前分支 `me-416-danmu-v2` 的以下提交为准：

| 项目 | 内容 |
| --- | --- |
| 提交 | `cffad45dd145db482aeabcb724b07c49d7ee4312` |
| 提交标题 | 自定义功能 - 新增缓存功能 |
| 提交时间 | 2026-09-30 22:56:21，UTC+08:00 |
| 对比基点 | 父提交 `28b9c30f7e3729618ada8a0417ad7d8a63a5490a` |
| 改动规模 | 85 个文件，新增 2711 行，删除 34 行；包含测试媒体文件和资源文件 |

缓存功能最初来自 `/Users/feicai/Code/project/android/TV` 中未提交的实现，迁入当前 Android-TV 项目后，又按本次讨论调整了界面、播放器复用和弹幕功能。本文描述最新提交的最终状态，不将开发中已经撤回的界面方案当作现有功能。

本文档本身是该提交之后新增的说明文件。更早提交中已经存在的弹幕功能，只在本次涉及集成或修复时说明。

## 2. 这次改动要解决什么问题

本次工作的目标是让用户在在线播放时缓存当前剧集，随后在统一的缓存页面管理任务，并用原来的播放页面播放已完成的缓存。

用户体验形成以下流程：

1. 在在线播放页缓存当前正在播放的剧集。
2. 在播放页右侧打开缓存面板，查看已经缓存和正在缓存的内容。
3. 在设置页进入“缓存”，按剧集折叠查看任务，进行播放、暂停、继续、重试和删除。
4. 播放已完成的缓存时，继续使用原有播放器布局和常用控制功能。
5. 需要弹幕时，使用已保存的弹幕、本地导入，或者在有网络时使用配置提供的动态“弹幕云搜”入口。

## 3. 缓存入口及界面调整

### 3.1 设置页面

手机端设置页新增缓存入口，放在“播放设置”下面。入口名称统一使用“缓存”，不再使用“我的缓存”。英文资源为 `Cache`，繁体中文为“快取”。

缓存卡片采用与播放设置卡片一致的布局结构、背景、内边距、字体和间距，移除了导致卡片额外增高的设置，让相邻卡片高度和视觉风格一致。

电视端首页功能列表也新增缓存入口。

### 3.2 播放页面

手机端播放页面有两个用途不同的入口：

| 位置 | 操作 | 用途 |
| --- | --- | --- |
| 播放控制区域右上方的缓存图标 | 点击 | 缓存当前剧集、当前选择的播放质量 |
| 同一个缓存图标 | 长按 | 打开完整缓存页面 |
| 底部操作栏中“弹控”右侧的缓存列表按钮 | 点击 | 从右侧打开缓存管理面板 |

右上方缓存按钮调整为 40dp 控件、6dp 内边距及 `fitCenter` 图标显示，改善原来图标偏小、位置看起来偏下的问题，使其与相邻按钮对齐。

电视端播放页增加缓存当前剧集的入口。手机端右侧面板的快捷入口属于手机界面，不应理解为电视端也具有完全相同的操作栏布局。

### 3.3 完整页面与右侧面板

完整缓存页面和右侧面板共用 `OfflineCacheList`，因此任务状态、分组和操作逻辑保持一致。

右侧面板采用 `SideSheetDialog`，从右侧展开，关闭按钮位于面板内。最终宽度取 420dp 与屏幕宽度 92% 中较小的一项，面板占满可用高度，不允许拖拽。此前讨论中的“太宽”随后澄清为列表条目高度太高，因此最终重点是压缩条目高度，并未保留更窄的实验方案。

完整页面处理系统栏和屏幕缺口的边距；手机端页面使用 `fullUser` 方向设置，电视端使用 `sensorLandscape`。

列表提供空状态、读取异常状态，以及已完成数量、待完成数量和缓存大小的汇总。正常情况下每秒刷新任务状态，读取失败时延后重试；离开界面后停止刷新，避免持续占用后台资源。

### 3.4 按剧集折叠分组

缓存列表将同一剧集的多个缓存任务显示在一个可折叠分组中：

- 优先使用缓存时保存的原始播放历史 `key` 分组。同一原始剧集下的不同线路可以归在一起。
- 旧任务没有原始历史信息时，使用标题作为列表分组依据。
- 没有标题时，按任务 ID 独立分组。
- 标题相同但原始来源 `key` 不同的任务，仍然分开显示。

分组默认折叠。展开状态保存在 `offline_ui` 的 `expanded` 偏好项中，列表刷新和重新打开页面时能够保留。

分组标题显示名称、集数、已完成数量和缓存大小，箭头随展开状态变化。

这里的“目录”是缓存列表中的逻辑分组。媒体数据仍由 Media3 缓存系统管理，没有将已经下载的媒体文件移动为“剧名/集数”形式的物理文件夹。

### 3.5 单集条目的信息与操作

单集条目改为紧凑布局，减少原先过高的视觉占用：

| 区域 | 最终显示方式 |
| --- | --- |
| 第一行 | 单集标题，最多两行，超出后以省略号结尾 |
| 第二行 | 线路/渠道名称，后面接缓存状态，例如已完成、缓存中、已暂停 |
| 进度信息 | 已下载大小、百分比等任务信息 |
| 异常信息 | 下载失败时显示错误提示，按内容需要展开 |
| 操作区域 | 使用播放、暂停、继续、重试、删除图标，保留操作说明和可访问性描述 |

操作按钮保留 44dp 的触摸/焦点区域。短标题条目的高度在界面测试中检查为小于 80dp；这不是所有条目的固定高度，标题两行或错误提示仍可能增加实际高度。

删除任务前有确认步骤。暂停、继续和重试根据任务状态显示，只有已完成任务提供缓存播放。

## 4. 下载与存储实现

### 4.1 下载的是当前剧集和当前质量

`OfflineIntegration.cacheCurrent()` 从当前播放器和播放历史创建不可变的任务快照，保存标题、集数、线路、媒体 URL、请求头、媒体类型、历史信息、弹幕列表及原始播放结果。

创建任务前检查播放器是否处于可用播放状态，拒绝不适合缓存的情况，包括未准备好、当前已经在缓存播放、非 HTTP(S) 媒体、直播和检测到的 DRM 内容。

任务保存当前的画质约束和音轨语言选择。自适应媒体会通过 Media3 的 `DownloadHelper` 生成包含所选轨道信息的 `DownloadRequest`，并检查是否存在可下载的音视频轨道。

此入口缓存当前一集，不自动缓存整部剧；也不一次下载所有画质。字幕文本轨道未纳入这次下载选择。

### 4.2 稳定任务身份和请求隔离

任务身份由原始播放历史、线路、集地址及质量信息生成，避免同一集的临时签名媒体 URL 变化后生成重复任务。

请求头在创建任务时复制并固定，后续切集或修改播放器参数不会改变已提交的下载任务。缓存键加入任务 ID 前缀，使两个任务即使引用相同媒体 URL，也能分别管理缓存；删除一个任务不会误删另一个任务的数据。

### 4.3 Media3 下载管理

`OfflineCache` 使用以下组件：

- `SimpleCache` 和 `NoOpCacheEvictor` 存储媒体数据。
- `StandaloneDatabaseProvider` 与名为 `offline` 的下载索引保存任务记录。
- `DownloadManager` 管理下载，最大并行任务数为 2。
- 生产下载要求满足 `Requirements.NETWORK`。
- `OfflineDownloadService` 提供前台下载服务和进度通知。
- `PlatformScheduler` 配合任务调度，通知点击后进入缓存页面。

支持 MP4、HLS 和 DASH 等 Media3 能下载的媒体形式。本次测试包含 HLS 分离音视频和 DASH 音视频分片，验证了音轨与视频分片都能供后续离线播放和跳转使用。

下载准备有超时保护，重复提交和仍在准备的任务会被拦截。失败原因保存在独立偏好项中，供界面展示。

### 4.4 暂停、继续、重试和删除

暂停通过下载停止原因记录任务状态，继续使用原任务请求恢复；失败任务也可以重新提交同一个请求重试。删除通过下载服务移除对应任务和媒体缓存。

重试保留的是原下载请求，不会自动回到站点重新解析已过期的签名 URL。如果媒体地址或鉴权信息过期，需要重新在线播放获取有效地址，再重新缓存。

### 4.5 存储位置与容量边界

媒体数据存放在应用内部 `filesDir/offline_media`，与普通临时缓存区分离。因此普通“清理缓存”操作不会删除这些已下载视频；应用卸载或清除应用数据仍会移除它们。

当前使用 `NoOpCacheEvictor`，没有自动按容量淘汰旧视频的功能。用户通过删除任务回收空间，磁盘不足可能导致下载失败。

Manifest 增加了数据同步前台服务权限、开机相关权限、下载服务及调度服务声明。这些是后台任务运行基础，不代表可以绕过系统强制停止、厂商后台限制或保证任何情况下都能自动恢复下载。

## 5. 缓存播放复用原有播放器

### 5.1 复用方式

本次没有保留一套独立的缓存播放器页面。点击缓存播放后，`OfflineIntegration.play()` 使用 `offline_id` 等 Intent 参数进入原有手机端或电视端 `VideoActivity`。

`OfflinePlayback` 将下载记录转换成原播放页需要的 `Vod`、`Flag` 和 `Episode`，集地址使用 `offline:<任务 ID>`。同组已完成任务形成可切换的集列表，并按集数排序。

有原始历史信息的任务按原历史 key 组织播放列表；旧任务的播放列表采用“标题 + 线路”回退分组。这与列表界面对旧任务按标题分组的规则有所不同，因此旧任务在一个列表分组内，不一定全部出现在同一播放集列表中。

### 5.2 只从已下载媒体读取

`Players.startOffline()` 继续使用原有 ExoPlayer、渲染器、轨道选择器和加载控制逻辑，但切换为缓存播放的数据源。

缓存视频数据源不配置网络上游，也不在播放时写入缓存。媒体缺失或任务未完成时显示错误，不会静默改成联网播放。`DownloadRequest.toMediaItem()` 保留下载轨道选择及缓存键信息，保证播放读取对应任务的内容。

外层保留 `DefaultDataSource` 的本地资源能力，供本地文件等输入使用。缓存播放异常不会自动切换到在线站点；从缓存流程明确切回在线流程时，会清理离线适配状态。

### 5.3 保留的控制能力

缓存播放继续使用原播放页的布局和控制，按媒体本身及终端界面能力提供：

- 播放/暂停、进度跳转、倍速、缩放与循环等常用播放操作。
- 集数选择和已完成缓存之间的切换。
- 原有解码切换；重建播放器时保留进度、速度及播放/暂停状态。
- 手机端横屏/竖屏控制。
- 片头、片尾等播放历史设置。
- 已缓存媒体包含的音视频轨道选择。
- 原有弹幕选择、导入、刷新，以及手机端弹控设置。

复用的原则是尽量保留现有功能，同时适配缓存数据。网络站点切换、在线画质重新解析等功能不能直接等同于本地已下载媒体，缓存模式禁用相应的在线解析播放和画质选择流程。

本次没有承诺投屏、外部播放器或所有网络字幕服务都能直接读取应用私有缓存，也没有把播放器现有全部功能声明为离线可用。

### 5.4 播放历史独立保存

缓存播放历史保存在 `offline_playback` 中，使用 `history:<分组>` 保存进度、时长、速度、缩放和片头片尾等设置。首次进入可以继承缓存时保存的原播放历史；切换到不同集时，避免错误沿用另一集的进度。

缓存历史不会直接写入或删除在线历史数据库。无痕模式下不保存缓存播放历史。退出页面、处理新播放 Intent 时进行相应的保存和清理，异步查询使用生命周期和代次检查，防止已经退出页面后继续更新界面。

## 6. 弹幕保存、导入和刷新

### 6.1 保存已经加载过的弹幕

`player/danmaku/Loader` 支持读取本地路径和 `file:` 地址。网络弹幕加载成功后，会在应用内部 `filesDir/danmaku_saved` 保存 XML；下次网络读取失败时，尝试使用此前保存的文件。

文件写入使用临时文件和完成后的重命名，避免将不完整响应当作已保存弹幕。保存目录与普通临时缓存分离。

这不是把全部弹幕源预先下载到本地：只有实际加载并保存过的弹幕才具备文件回退能力。网络地址仍会先尝试网络读取，失败后才回退；本地导入则可以直接读取本地文件。

### 6.2 缓存任务关联弹幕

任务元数据记录缓存时的弹幕列表；缓存播放期间选择、导入或云搜得到的弹幕列表，另存为 `danmaku:<任务 ID>`，再次打开该集时优先恢复。

弹幕切换和刷新过程中修正了选中状态处理，避免刷新同一项时因为状态翻转而取消选择；解码切换也保持相应弹幕状态。

共享 `DanmakuDialog` 对刷新按钮采用可选查找，兼容没有该按钮的布局，同时处理空列表和选中项越界等情况。

## 7. 动态“弹幕云搜”入口及缓存后不显示的修复

### 7.1 入口从哪里来

“弹幕云搜”是配置提供的动态解析项，显示在播放页底部的解析按钮区域。它不是本次硬编码新增的固定按钮。

在线播放和缓存播放都使用配置中的解析列表。手机端底部解析栏还受全屏状态和控制栏显示状态影响；设置控制对话框也使用对应解析数据。

如果当前配置本身没有对应解析项，程序不会凭空生成一个云搜按钮。

### 7.2 原先缓存后不显示的原因

缓存播放入口曾存在两个限制：

1. 只允许名称含“弹幕”“彈幕”或 `danmaku` 的解析项出现，名称为“云搜”等配置项会被过滤。
2. 从缓存页面直接进入播放时，内存中的解析列表可能尚未初始化，虽然本地已保存配置，底部仍没有数据可显示。

最终实现移除了缓存模式下按名称过滤解析列表的规则。缓存页面直接启动时，`VodConfig.restoreParses()` 在内存解析列表为空的情况下，从已经保存的配置 JSON 恢复 `parses`，去重并恢复选中状态，再刷新播放页和控制对话框的列表。

恢复入口不需要为了显示按钮重新请求配置 URL，也不会等待网络配置加载后才能播放本地视频。扩展解析实际执行时，类型 2/3 的解析在工作线程通过 `loadParseExtensions()` 初始化所需 provider JAR。

这里只恢复动态解析所需数据，不等于重新初始化整套站点和规则配置。扩展 JAR 是否可执行，仍取决于本地资源或其加载条件。

### 7.3 云搜使用原始在线播放参数

`Players` 保存原始播放 `Result`，缓存任务将其作为 `source` 元数据保存。缓存播放调用动态解析时优先读取这个原始结果，让解析器获得与在线播放对应的 URL、站点 key、flag，以及解析流程使用的请求头和点击规则等信息。

旧任务没有 `source` 时，回退到原始历史中的集地址、站点 key 和线路；再没有这些信息时，使用已保存的媒体 URL。因此旧缓存可以继续使用，但云搜匹配信息可能不如新任务完整。

没有凭空增加一套“标题/集数”的云搜请求协议；实际参数仍沿用现有解析引擎的约定。

### 7.4 云搜不会替换正在播放的缓存视频

`ParseJob.startDanmaku()` 和独立的 `Players.danmakuJob` 执行只取弹幕的解析任务。兼容的响应可以在顶层或 `data` 中返回 `danmaku`，只含弹幕而不含视频 URL 的响应也可以处理。

通过新增的 `ParseCallback.onParseDanmaku()` 合并和选择弹幕，不调用视频播放结果回调，因此不会将缓存视频替换为在线解析视频。切集、停止和释放播放器时会取消相应弹幕解析任务，并显示搜索中或失败提示。

手动搜索通过 `Result.getDanmaku(true)` 绕过自动加载弹幕的开关；它不表示搜索服务可以在完全无网络情况下运行。

缓存模式点击动态解析项走只取弹幕的流程，不再要求名称关键词。在线播放仍通过 `Parse.isDanmaku()` 的名称识别区分弹幕解析与原有视频解析行为。

### 7.5 当前显示和成功搜索的条件

| 条件 | 说明 |
| --- | --- |
| 配置中存在解析项 | 本地保存配置或当前已加载配置需包含相应入口 |
| 手机端进入全屏并显示控制栏 | 底部动态解析栏才会出现在对应位置 |
| 从缓存直接进入时能恢复保存配置 | 无需先在线播放一次来初始化内存列表 |
| 解析服务返回兼容的弹幕数据 | 仅返回视频地址的普通解析器，不一定能完成弹幕搜索 |
| 服务与扩展资源可访问 | 联网云搜仍依赖网络和服务本身；已保存弹幕可供离线使用 |

已有缓存不必为了恢复按钮而重新下载。旧元数据缺少原始参数时，按钮可以出现，但服务匹配效果可能受影响。

## 8. 构建兼容及其他一并纳入的改动

### 8.1 Python 依赖修复

`chaquo/requirements.txt` 将 `pyquery` 固定为 `2.0.1`。原因是构建所用 Python 3.8 环境无法满足新版 pyquery 引入的 `cssselect>=1.5.0` 依赖，曾导致 pip 安装失败。

其他既有 Python 依赖继续保留；这次固定的是 pyquery 版本，并非为解决该错误升级整套 Python 运行环境。

### 8.2 弹控绑定类的 source set 修复

`DanmakuControlDialog.java` 从共享 `main` source set 移到 `mobile` source set，因为它依赖的 `DialogDanmakuControlBinding` 由手机端布局生成。这样电视端编译不会再引用不存在的绑定类。

共享刷新 ID 在 `main/res/values/ids.xml` 声明，配合可选按钮查找，让共享弹幕对话框适配两端布局。

### 8.3 测试基础与构建脚本

`app/build.gradle` 增加或配置测试 runner、JUnit、JSON 和 AndroidX 测试依赖。`gradlew` 增加可执行权限，可直接使用 `./gradlew` 运行。

### 8.4 自动更新设置的实际变化

最新提交还包含 `Setting.getUpdate()` 固定返回 `false` 的改动，原读取更新偏好项的代码被注释。这会使调用该 getter 的更新逻辑读到关闭状态，即使偏好项另有保存值。

这属于此次提交一并包含的行为变化，与缓存下载本身没有直接关系，后续维护不能只关注缓存类而忽略它。

## 9. 主要代码位置及职责

以下路径均相对于项目根目录。

| 文件或目录 | 职责 |
| --- | --- |
| [`OfflineVideo.java`](../app/src/main/java/com/fongmi/android/tv/offline/OfflineVideo.java) | 不可变任务元数据、稳定身份、JSON 兼容读取、列表分组 key |
| [`OfflineCache.java`](../app/src/main/java/com/fongmi/android/tv/offline/OfflineCache.java) | 缓存实例、下载索引、任务准备、请求隔离、缓存播放数据源 |
| [`OfflineDownloadService.java`](../app/src/main/java/com/fongmi/android/tv/offline/OfflineDownloadService.java) | 前台下载、通知和任务调度 |
| [`OfflineIntegration.java`](../app/src/main/java/com/fongmi/android/tv/offline/OfflineIntegration.java) | 当前剧集缓存、入口调用、进入原播放器 |
| [`OfflinePlayback.java`](../app/src/main/java/com/fongmi/android/tv/offline/OfflinePlayback.java) | 下载记录到播放详情/集列表的适配、离线历史、弹幕恢复 |
| [`OfflineCacheActivity.java`](../app/src/main/java/com/fongmi/android/tv/offline/OfflineCacheActivity.java) | 完整缓存页面及系统边距处理 |
| [`OfflineCacheDialog.java`](../app/src/main/java/com/fongmi/android/tv/offline/OfflineCacheDialog.java) | 播放页右侧缓存面板 |
| [`OfflineCacheList.java`](../app/src/main/java/com/fongmi/android/tv/offline/OfflineCacheList.java) | 共用列表、折叠分组、刷新及任务操作 |
| [`Players.java`](../app/src/main/java/com/fongmi/android/tv/player/Players.java) | 缓存媒体播放、解码重建、原始结果快照、独立弹幕解析任务 |
| [`ParseJob.java`](../app/src/main/java/com/fongmi/android/tv/player/ParseJob.java) | 原解析引擎中的只取弹幕流程 |
| [`VodConfig.java`](../app/src/main/java/com/fongmi/android/tv/api/config/VodConfig.java) | 保存配置中的动态解析恢复和扩展加载 |
| [`Parse.java`](../app/src/main/java/com/fongmi/android/tv/bean/Parse.java)、[`Result.java`](../app/src/main/java/com/fongmi/android/tv/bean/Result.java)、[`ParseCallback.java`](../app/src/main/java/com/fongmi/android/tv/impl/ParseCallback.java) | 弹幕解析识别、手动提取及回调接口 |
| [`Loader.java`](../app/src/main/java/com/fongmi/android/tv/player/danmaku/Loader.java) | 本地弹幕、网络 XML 保存及失败回退 |
| [`DanmakuDialog.java`](../app/src/main/java/com/fongmi/android/tv/ui/dialog/DanmakuDialog.java) | 两端共享弹幕选择和刷新兼容 |
| [`DanmakuControlDialog.java`](../app/src/mobile/java/com/fongmi/android/tv/ui/dialog/DanmakuControlDialog.java) | 手机端弹控，迁移到正确 source set |
| [`mobile VideoActivity`](../app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java)、[`leanback VideoActivity`](../app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java) | 原播放页接入缓存、弹幕解析和生命周期适配 |
| 手机/电视 `HomeActivity`、电视 `Func` | 下载服务接入和首页缓存入口 |
| 手机 `SettingFragment`、`fragment_setting.xml` | 缓存设置入口及卡片高度一致性 |
| 手机 `ParseAdapter`、`ControlDialog` | 动态解析列表刷新与显示 |
| `main/res/layout/offline_cache.xml`、`offline_group.xml`、`offline_row.xml` | 共用缓存页、分组和紧凑单集布局 |
| 手机 `view_control_vod.xml`、`view_control_vod_action.xml`，电视 `activity_video.xml` | 播放页缓存按钮及列表入口 |
| `main/res/drawable/offline_*.xml`、`offline_colors.xml`、各语言 `offline_strings.xml` | 图标、卡片、焦点/按压状态、颜色与文案 |
| 三个 source set 的 `AndroidManifest.xml` | 下载服务、调度、页面注册及方向设置 |
| [`Setting.java`](../app/src/main/java/com/fongmi/android/tv/Setting.java) | 一并包含的自动更新 getter 变化 |
| [`requirements.txt`](../chaquo/requirements.txt)、[`app/build.gradle`](../app/build.gradle)、[`gradlew`](../gradlew) | 依赖修复、测试配置及脚本权限 |

## 10. 测试覆盖与已执行结果

### 10.1 六项本地单元测试

[`OfflineVideoTest.java`](../app/src/test/java/com/fongmi/android/tv/offline/OfflineVideoTest.java) 覆盖：

1. 调用方后续修改集信息和请求头，不影响已创建的任务快照。
2. 临时 URL 变化不改变稳定任务身份。
3. 播放设置和弹幕元数据能够往返保存，不改变旧身份规则。
4. 旧下载记录缺少新增元数据仍可读取。
5. 按原始剧集跨线路分组，并兼容旧数据。
6. 损坏元数据被拒绝。

### 10.2 十三项 Android 设备测试

[`OfflineCacheTest.java`](../app/src/androidTest/java/com/fongmi/android/tv/offline/OfflineCacheTest.java) 使用本地测试 HTTP 服务及 [`offline` 测试资源](../app/src/androidTest/assets/offline)，覆盖以下场景：

| 场景 | 检查内容 |
| --- | --- |
| 缓存侧面板与删除 | 已完成/暂停任务显示、折叠保持、条目布局、确认删除 |
| 共享播放器控制 | 解码切换和旋转后继续从缓存读取 |
| 云搜原始参数 | 传入原始播放参数，弹幕结果不替换缓存视频 |
| 冷启动云搜入口 | 从保存配置恢复，不依赖名称含“弹幕”关键词 |
| 弹幕离线读取 | 已保存 XML 和本地导入在无网络时可读 |
| MP4 离线播放 | 普通缓存清理后仍可播放 |
| HLS 下载 | 分离音轨和视频分片完整缓存 |
| DASH 下载 | 音视频下载后支持离线跳转 |
| 并行下载 | 请求头和缓存命名空间互不干扰 |
| 暂停恢复 | 服务恢复后保留下载进度 |
| 相同媒体 URL 删除隔离 | 删除一项不影响另一项 |
| 中断失败重试 | 网络中断后任务保留并可重试 |
| 直播拒绝 | 直播 HLS 在创建任务前被拒绝 |

测试资源包含小体积 MP4、HLS 播放列表及音视频分片、DASH 清单和分片、直播拒绝样例以及弹幕 XML。这些二进制媒体文件用于可重复测试，不是业务内置视频。

测试中为配合模拟器本地服务放宽网络要求；生产代码仍要求网络可用，不能据此认为生产下载会绕过网络约束。

### 10.3 本次开发期间的实际验证结果

- 手机端与电视端 ARM64 Debug 构建成功。
- 本地单元测试 6 项通过。
- 手机端 Android 测试 13 项通过。
- 电视端完整 Android 测试首轮 12 项通过，“中断下载后重试”一项等待完成超时，单独重跑后通过。
- 云搜冷启动入口曾通过测试复现“不显示”，修复后对应回归通过。

电视端首轮整套测试并非一次全部通过，单项重跑通过后仍应留意中断重试场景的时序稳定性。

设备测试基于模拟器和本地服务，未验证所有第三方真实云搜接口、真实电视遥控体验、所有编码媒体或各厂商后台策略。云搜测试验证的是入口、参数及缓存播放隔离，不代表任意解析服务都返回兼容弹幕数据。

本次编写文档没有重新执行构建和设备测试；上述为功能开发期间已经执行的验证记录。

### 10.4 后续复现命令

在项目根目录运行，设备测试需要已连接且可用的 Android 设备或模拟器：

```sh
./gradlew :app:assembleMobileArm64_v8aDebug :app:assembleLeanbackArm64_v8aDebug
./gradlew :app:testMobileArm64_v8aDebugUnitTest
./gradlew :app:connectedMobileArm64_v8aDebugAndroidTest :app:connectedLeanbackArm64_v8aDebugAndroidTest
```

单独复测冷启动云搜入口：

```sh
./gradlew :app:connectedMobileArm64_v8aDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fongmi.android.tv.offline.OfflineCacheTest#cloudDanmakuRestoresStoredConfigurationAndDoesNotRequireANameKeyword
```

单独复测电视端中断重试：

```sh
./gradlew :app:connectedLeanbackArm64_v8aDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fongmi.android.tv.offline.OfflineCacheTest#interruptedDownloadCanFailAndRetryWithoutLosingItsTask
```

## 11. 使用和维护时需要保留的边界

1. **只有已完成任务可播放。** 暂停、排队、下载中和失败任务需要先完成下载。
2. **缓存视频播放不走网络回退。** 缺少缓存数据时应提示异常，不应自动替换为在线媒体。
3. **云搜入口来自配置。** 不应再次增加仅凭按钮名称筛选缓存解析项的限制。
4. **联网云搜和离线弹幕是两种数据来源。** 前者需要服务可访问，后者需要已保存文件或本地导入。
5. **分组目录是 UI 结构。** 当前缓存不是供文件管理器直接浏览的一套剧集文件目录。
6. **旧数据保持兼容。** `history`、`danmaku`、`source` 缺失时允许回退，不能因为新增字段让旧缓存失效。
7. **下载 URL 不自动续签。** 重试失败时应考虑地址或鉴权过期，而不只是重复重试。
8. **缓存容量需要用户管理。** 当前没有自动淘汰策略，普通临时缓存清理也不会删除离线媒体。
9. **播放器继续共用。** 后续补充功能优先适配原 `VideoActivity` 和 `Players`，避免再次维护一套功能缩水的缓存播放器。

