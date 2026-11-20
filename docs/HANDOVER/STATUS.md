# 完成状态（截至 2026-10-10）

对照最初的 M0–M8 路线图。**「上游」列标明该能力是 Voyager 带来的，还是我们这轮做的增量**——
交接时别把上游功能当成自己的成果。

图例：✅ 完成并验证 ·🟡 部分完成 ·❌ 未开始 ·⬜ 不适用（已放弃）

---

## 总览

| 里程碑 | 状态 | 说明 |
| --- | --- | --- |
| M0 Fork 骨架与构建验证 | ✅ | 包名 `com.voyagerfiles`→`com.naslink`，Gradle 提升到 8.14.3，JDK 17 构建通过 |
| M1 依赖与本地化基础 | ✅ | smbj / dcerpc / Room / Coil / Media3 接入，11 语言的 Crowdin 流水线 |
| M2 文件管理（上游能力） | ✅ | 浏览、上传下载、复制移动、重命名删除、压缩包、SAF、SFTP、FTP、WebDAV |
| M3 流式播放骨架 | ✅ | `:stream` 模块 + 回环 HTTP + Media3 播放器 |
| M4 播放体验增强 | 🟡 | **主体可用，4 项增强全未做**，见下表 |
| M5 图片浏览 | 🟡 | 主体完成，2 项集成测试待收尾，见下表 |
| M6 整理能力 | ❌ | 批量重命名、聚类视图均未开始 |
| M7 SMB 性能层 | ✅ | 会话连接池、加密后端分情况选择、配置单一真相 |
| M8 发布准备 | ❌ | README 已过期、路线图未更新、F-Droid 元数据待核、Releases 未发 |

---

## M0–M2：地基（全部 ✅）

这些**都是上游 Voyager 的能力**，我们只改了包名和构建配置。

| 能力 | 状态 | 来源 |
| --- | --- | --- |
| SMB 连接管理（用户名/密码/域、共享枚举、Keystore 凭据加密） | ✅ | 上游 |
| 文件浏览（列表/网格、排序、目录内搜索、多选） | ✅ | 上游 |
| 上传 / 下载（进度、可取消、前台服务） | ✅ | 上游 |
| 复制 / 移动 / 重命名 / 删除（跨 provider 协调、冲突处理） | ✅ | 上游 |
| 压缩包创建与解压（ZIP / TAR / GZ / BZ2） | ✅ | 上游 |
| SFTP / FTP / WebDAV / SAF 协议 | ✅ | 上游 |
| Room 数据库 | ✅ | 上游。**目前只有 2 张表**：`remote_connections`、`bookmarks` |

---

## M3：流式播放骨架 ✅

核心诉求「看 NAS 视频不下载」的主链路已经打通并验证过。

| 组件 | 状态 | 位置 |
| --- | --- | --- |
| `RandomAccessSource` 抽象 | ✅ | `stream/.../RandomAccessSource.kt` |
| `SmbRandomAccessSource`（惰性建连、失败重连一次、句柄粘性保活） | ✅ | `stream/.../SmbRandomAccessSource.kt` |
| `StreamSessionStore`（256bit token → 文件，30 分钟空闲回收） | ✅ | `stream/.../StreamSessionStore.kt` |
| `LoopbackStreamServer`（127.0.0.1 上的极简 HTTP，支持 Range 206） | ✅ | `stream/.../LoopbackStreamServer.kt` |
| `StreamPlaybackCoordinator`（进程级发布 SMB 文件为可播放 URL） | ✅ | `app/.../playback/StreamPlaybackCoordinator.kt` |
| `StreamPlayerActivity`（Media3 播放器界面） | ✅ | `app/.../playback/StreamPlayerActivity.kt` |

播放器拿到的地址形如 `http://127.0.0.1:<随机端口>/s/<token>/<文件名>`。
**URL 里不含主机、共享名、路径或凭据**；非回环来源的请求一律 403。

已验证：`readsFileSizeAndRangedBytesOverRealSmb` 等 5 项真实 SMB2 集成用例通过，
含「同一区间 HTTP 字节与直连 SMB 字节逐字节一致」——防止传输静默损坏媒体。

---

## M4：播放体验 🟡（4 项增强全部未做）

主链路可用，但**这 4 项一项都没实现**（已用代码检索逐项确认，不是遗漏而是真没写）：

| 增强项 | 状态 | 说明 |
| --- | --- | --- |
| 播放进度记忆 | ❌ | 无 `resumePosition` / `watchProgress` 相关代码。**不做的话长片每次从头看** |
| 外挂字幕（srt/ass） | ❌ | 无 `SubtitleConfiguration` / `MediaItem.Subtitle` 相关代码 |
| 倍速播放 | ❌ | 无 `setPlaybackSpeed` / `PlaybackParameters` 相关代码 |
| 外抛到系统播放器 | ❌ | ⚠️ 注意区分：`util/FileUtils.kt` 里的 `ACTION_VIEW` 是上游的「用外部应用打开文件」，**不等于**外抛到系统播放器 |

---

## M5：图片浏览 🟡（主体完成，收尾未做）

| 能力 | 状态 | 位置 |
| --- | --- | --- |
| 远程缩略图（列表/网格，loopback URL 走 Coil 内建 fetcher） | ✅ | `app/.../ui/components/RemoteImageLoaderFactory.kt` |
| 128MB Coil 磁盘缓存（远程缩略图每次都要网络往返，磁盘缓存是「秒开」与「卡顿」的分界） | ✅ | `app/.../app/MaterialBrowserApp.kt` |
| 全屏查看器（手写 pinch / 双击缩放、前后翻页、邻页预取） | ✅ | `app/.../playback/ImageViewerActivity.kt` |
| 路径解析去重（消除 3 处复制） | ✅ | `app/.../data/remote/smb/SmbPathResolver.kt` |
| 11 语言 i18n（新增 9 条 × 11 语言） | ✅ | 各 `values-*/strings.xml` |
| GIF/BMP 识别修复 | ✅ | `FileItem.kt` 的 `mimeTypeOverrides` |
| 真实 SMB 图片集成测试 | 🟡 | 2 项失败，见 `KNOWN_ISSUES.md` 第 1 条 |
| 查看器 UI 层仪器化测试 | ❌ | 未写 |

范围决策（已固化，别扩大）：**图片只做 SMB**。SFTP/FTP 没有等价会话池，
WebDAV 已有自己的 `content://` 发布路径，再套回环服务器就是同一件事两套机制。

---

## M6：整理能力 ❌ 完全未开始

| 能力 | 状态 |
| --- | --- |
| 批量重命名 | ❌ 不存在。`rename` 是单文件 API |
| 聚类视图（按内容去重/相似分组） | ❌ `DuplicateScanner` 已实现但**只吃 `java.io.File`**，入口用 `state.source == FileSource.LOCAL` 门控 → **无法用于 SMB/SFTP/FTP/WebDAV** |

现状里两个必须知道的坑：

1. **5 个 provider 的重命名会静默覆盖**。7 个 provider 中只有 `LocalFileProvider` 和
   `RootFileProvider` 做了重名检查，`SMB / SFTP / FTP / WebDAV / SAF` 都没有。
   批量重命名会把这个缺陷放大成「一次误操作批量丢文件」。
2. `SelectionToolbarModel` 里 RENAME **只在 `isSingle` 时出现**（`BrowserScreen.kt:1638-1658`），
   多选工具栏当前根本没有重命名入口。

可复用的现成范式：`FileBrowserViewModel.deleteSelected`（`:670-721`）——
循环 + failed 计数 + `OperationMessages.partial` + `clearSelection` + `refreshFiles`。
照它写批量重命名即可。

⚠️ 协议层限制：SMB 没有通用的文件哈希 API。所以「远程内容级聚类」只能拉流到本地算哈希，
这对 40GB 的视频目录是不可接受的。**接手时先决定：聚类只做本地（诚实标注），还是为远程做流式哈希
（慢但可用）？** 这个取舍没人替你做过决定。

---

## M7：SMB 性能层 ✅

| 能力 | 状态 | 位置 |
| --- | --- | --- |
| 泛型连接复用缓存（纯逻辑，无 I/O，可无服务器测试） | ✅ | `stream/.../SessionReuseCache.kt`（13 项单测） |
| per-host 会话连接池 | ✅ | `stream/.../SmbSessionPool.kt` |
| 加密后端分情况选择（不加密用 JCE，加密用 BC） | ✅ | 同上，5 项单测钉住取舍 |
| 超时/缓冲常量的单一真相 | ✅ | `SmbFileProvider` 与 `SmbRandomAccessSource` 均引用 `SmbSessionPool` |

修掉的 3 个真实缺陷：池化路径丢弃密码（导致非匿名登录必失败）、`leased` 时忙等、
`encryptData` 未进连接标识导致 SMB3 加密连接被错误复用。

---

## M8：发布准备 ❌

| 项 | 状态 |
| --- | --- |
| README 准确 | ❌ **已过期**——仍写着「当前阶段为 M1–M2」，能力表里图片/性能层还标着「待实现」。见 `KNOWN_ISSUES.md` 第 5 条 |
| 路线图文档 | ❌ 未更新 |
| F-Droid 元数据（`metadata/`） | 🟡 沿用上游素材，20 条 changelog，未核对 |
| GitHub Releases | ❌ 未发 |
| Crowdin 流水线 | ✅ 上游带来，可用（`scripts/*.py`） |
| 签名密钥 | ❌ 未配置（M8 阻塞项，需要接手人自己的密钥） |

---

## 测试现状（交接时的真实基线）

| 套件 | 结果 |
| --- | --- |
| `:stream:test` | ✅ **74 项全通过**（2026-10-10 验证） |
| `:app:testDebugUnitTest` | 上游 + 本轮单测，含 `SessionReuseCacheTest` 13 项、`SmbSecurityProviderTest` 5 项、`RemoteImagePublisherTest` 7 项、`SmbPathResolverTest` 12 项、**`RemoteImageCacheKeyTest` 9 项（2026-10-14 新增，钉住缓存键稳定性）**、**`ImageFetchTrackerTest` 3 项（2026-10-17 新增，钉住 fetch 排空契约）**。全量 431 项：37 项能力探针跳过，仅 2 项 Windows 宿主差异失败（`DuplicateScannerTest` / `StorageCategoryIndexTest`，与 `AUDIT_M4.5.md` §4.4 同源，Linux/CI 不复现） |
| `SmbStreamIntegrationTest` | 🟡 **7 项中 5 通过、2 失败**（均为图片用例，见 `KNOWN_ISSUES.md` 第 1 条） |
| UI 层仪器化测试 | ❌ 基本没有。查看器、上传、设置等界面无自动化覆盖 |

**接手时请先跑一遍 `:stream:test` 与 `:app:testDebugUnitTest` 建立基线**，再决定从哪项里程碑继续。

> **2026-10-14**：外部审查发现的图片缓存缺陷已修（`KNOWN_ISSUES.md` 第 10 条）——
> 磁盘缓存键原本用易失 URL，导致缓存全是死数据、清除看似无效。
> 涉及 `RemoteImagePublisher.cacheKey`、`RemoteImageLoaderFactory.modelFor`、
> `ImageViewerActivity`、`RemoteVideoThumbnailLoader`、`FileBrowserViewModel.clearImageCache`。
>
> **2026-10-17**：第 10 条收尾 —— 清除的第二段等待从固定 1.5s 改为「fetch 排空」
> （`ImageFetchTracker` + Coil `EventListener`，均为公开 API；磁盘 commit 全部发生在
> `Fetcher.fetch()` 返回前，故"无 fetch 在途"= "不会再有 commit 落盘"）；同时修复
> 查看器 cacheKey 断在 Intent 边界（空 key → 全尺寸图共用缓存位）的问题。
> 新增 `ImageFetchTrackerTest`（3 项）。
>
> **2026-10-17（第二轮）**：静态审查查出并修复 4 处会话/发布泄漏（`KNOWN_ISSUES.md` 第 11 条）：
> ① `onCleared()` 在已取消的 viewModelScope 上 launch，断连永不执行
> （javap 反编译 `ViewModel` 2.11.0 确认 `clear()` 先取消 scope 再回调）；
> ② `SmbSessionPool` 每次未命中漏掉整条连接（smbj 的 `Session.close()` 只有 `logoff()`，
> 不碰 `Connection`/`SMBClient`）→ 改持 `PooledConnection` 三元组；
> ③ 缩略图 publication 只 publish 不 revoke → 按 cacheKey 复用 + LRU 上限 64；
> ④ FTP/WebDAV 建连竞态 → 加锁并返回已校验 client，消除 `!!` NPE。
> 另修 FTP 递归删除/复制对 ProFTPD/Pure-FTPd 的路径重复拼接。
> 新修 `StreamSessionStoreTokenTest`（9 项）。
> **残留未修**：FTP 控制协议并发失步（需架构级改动，留待决策）。
>
> **2026-10-21**：播放器重写 + 远程连接常驻首页。
> - 播放器从「`PlayerView` 默认控制器 + 两个角标」改为自绘 Compose 控制面，
>   新增长按临时加速、双击左右跳转、横拖定位、左亮度/右音量、双指缩放、旋转与方向锁定、
>   画面比例、音轨字幕切换、锁定、定时关闭、循环、画中画、沉浸式全屏。
>   设计说明见 `PLAYER.md`。
> - 首页新增「常用连接」：连接页的图钉按钮把连接固定到首页，点一下直接连入文件浏览器
>   （复用 `remote_connections.isFavorite` 列，**无需 Room 迁移**）。
> - 新增 `PlayerGestureMathTest` / `PlayerOptionsTest`（纯 JVM）与
>   `ConnectionRepositoryTest` 的固定标记用例。
>
> **2026-10-21（第二轮）**：修复「点开靠后图片要等很久」。
> 根因不是查看器本身，而是预览仍在后台抢同一条 SMB 会话：Coil 抓缩略图时缓存的是
> **原图完整字节**，所以一张网格预览 = 一次整图传输；进查看器后网格请求并未取消，
> 越往后翻，在途预览越多，被点的那张排在它们后面。改法：
> ① 新增 `ThumbnailLoadingGate`（进程级开关），`MainActivity.onStop/onResume` 与
> 「点击进入全屏」处关闭/恢复，`FileThumbnailOrIcon` 关闭时直接不建请求（连带取消在途）；
> ② 删除查看器里对 ±1 邻页的预热——原来等于同时跑三次整图传输；
> ③ 查看器现在一次只取一张，翻页按需加载（下一页通常已在 Coil 磁盘缓存里）。

> **2026-11-14**：缩略图缓存重做 + SMB 连接放大修复（v0.2.2 / versionCode 13）。
> - **缩略图不再走 Coil 磁盘缓存**：`RemoteImageThumbnailLoader` 内存 LRU → 自有
>   `ThumbnailDiskCache`（~30 KB WebP）→ 有界读取源文件（≤16 MB 全读，更大只读 256 KB
>   取 EXIF 预览）。Coil 的 512 MB 磁盘缓存整体移除，`clearImageCache` 不再需要两段清扫。
> - **只加载看得到的**：`ThumbnailLoadingGate` 增加 `scrolling` 信号，滚动中挂起
>   （不是取消）预览生成；离开屏幕的格子协程被销毁，读取根本不会发出。
> - **查看器**：删除邻页预热；用内存里的预览当 placeholder；翻页时把非当前页的
>   SMB 句柄还给连接池。
> - **连接放大**（新症状「用一会儿掉到几 KB/s，之后 source error，重开必好」）：
>   `SessionReuseCache` 增加 bounded wait（1.5s）后再拨私有连接；`SmbRandomAccessSource`
>   空闲 10s 归还句柄；SMB socket 超时 180s→20s；读失败首次即标 broken。
>   根因与四层修法见 `KNOWN_ISSUES.md` 第 12 条。
> - 测试：`:app` 478 项、`:stream` 55 项，全绿（新增 `ThumbnailDiskCacheTest` 10 项、
>   `JpegExifThumbnailTest` 11 项、`HandleIdlePolicyTest` 6 项、`SessionReuseCacheTest` +3 项）。
