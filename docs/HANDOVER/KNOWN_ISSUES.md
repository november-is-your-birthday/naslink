# 已知问题与不能改坏的约束

按「先看最要紧的」排序。**动手前请读完本文件。**

> **2026-10-14 更新**：新增第 10 条「图片缓存键用了易失 URL」——这是本轮修掉的最严重缺陷，
> 根因和踩坑细节都记在那里，改缓存相关代码前必读。
>
> **2026-10-17 更新**：第 10 条收尾 —— 清除的第二段等待从固定 1.5s 改为「fetch 排空」
> （`ImageFetchTracker` + `EventListener`），并修复查看器 cacheKey 未随 Intent 传递
> （空 key 全图撞缓存）的问题。两处细节都补在该条内。

---

## 1. 🔴 2 项 SMB 图片集成测试失败（唯一遗留的失败测试）

**状态**：`:stream:test` 74 项全绿 ✅，但 `SmbStreamIntegrationTest` **7 项中 2 项失败**：

```
realSmbImageDecodesFromTheLoopbackServer       expected:<200> but was:<502>
aRangeRequestForAnImageHeaderReturnsPartialContent  expected:<206> but was:<502>
```

### 我的判断：大概率是**夹具准备问题，不是产品缺陷**

但请**自己复核一遍再下结论**——我没有把根因彻底钉死，交接时不能装作已查清。

证据链：

1. 502 是我在 `LoopbackStreamServer.handleRange()` 里**主动写出的正确响应**，
   表示"打开数据源失败"。回环服务本身工作正常（另外 5 项 mp4 用例全部通过，
   其中包含真实的 SMB 读取与 Range 验证）。
2. 失败的 2 项与通过的 5 项请求**同一种路径形态**（`media\xxx`），
   唯一区别只是文件类型。
3. ⚠️ **已知的一处不一致**：测试断言 `IMAGE_EDGE = 64`（`SmbStreamIntegrationTest.kt:343`），
   即期望一张 **64×64** 的 PNG。而 `tools/smbtest/make_fixture.py` **只会生成 mp4**，
   仓库里**没有生成 PNG 夹具的脚本**。

### 你需要做的

1. 造一张 **64×64 的真 PNG** 放到共享根下对应位置（不能只改文件大小，要能真的被解码）：

   ```python
   import struct, zlib
   n = 64                      # 必须等于 IMAGE_EDGE
   raw = b''.join(
       b'\x00' + b''.join(bytes([(x*4) % 256, (y*4) % 256, ((x+y)*2) % 256])
                          for x in range(n))
       for y in range(n)
   )
   def chunk(tag, data):
       body = tag + data
       return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body))
   png = (b'\x89PNG\r\n\x1a\n'
          + chunk(b'IHDR', struct.pack('>IIBBBBB', n, n, 8, 2, 0, 0, 0))
          + chunk(b'IDAT', zlib.compress(raw))
          + chunk(b'IEND', b''))
   open('<共享根>/red.png', 'wb').write(png)
   ```

2. 确认**共享根目录层级**：测试请求的路径是 `media\red.png`，
   而 `make_fixture.py` 的 mp4 用法是 `<共享根目录>/media/probe.mp4`。
   注意服务端脚本 `smb_test_server.py` 接收的 root 是**直接映射为共享**的，
   所以 root 应该是**含有 `media/` 子目录的那一层**，不是 `media` 本身。
   **这一点我本轮没完全理清，是当前最可疑的一处。**
3. 重跑（命令见 `README.md` §4）。若造好夹具后**仍然 502**，
   那就是产品缺陷，此时重点查 `SmbPathResolver.resolveFile` 对 PNG 路径的解析。

**建议顺手把这段固化成脚本**（比如 `tools/smbtest/make_image_fixture.py`），
现在的状况是"测试隐含依赖一个手工步骤"，任何人接手都会踩。

---

## 2. 🔴 5 个 provider 的重命名会静默覆盖

`SMB / SFTP / FTP / WebDAV / SAF` **都没有重名检查**，
只有 `LocalFileProvider` 和 `RootFileProvider` 做了。
也就是说**重命名到一个已存在的名字会直接覆盖，不提示**。

现状下这只是"手滑一次丢一个文件"，但 **M6 要做的批量重命名会把它放大成"一次误操作批量丢文件"**。

👉 **做 M6 之前必须先补上重名检查**，这是前置依赖，不是可选项。

---

## 3. 🟡 项目不是 git 仓库 —— 改之前先备份

**这是最需要立刻处理的一件事。**

本项目当前**没有 `.git` 目录**，所以：

- **没有 `git diff` 可看，改坏了无法回滚**
- 本轮就发生过一次真实事故：用 Python 做全局 `replace` 修一条法语字符串时，
  把文件里原有 **78 处 `\'` 转义一起改掉了**，导致 4 个既有字符串编译报错。
  因为不是 git 仓库，**无法 `git checkout` 还原**，只能写脚本把所有
  `<string>`/`<item>` 值里的裸撇号重新转义回去。

**接手第一天就该做**：

```bash
cd <项目根>
git init && git add -A && git commit -m "NasLink 交接基线"
```

并且 Git 身份已配置好（若丢失）：

```
user.name  = <你的名字>
user.email = <你的 GitHub noreply 邮箱>
```

**用 noreply 邮箱**，避免在公开仓库泄露真实邮箱。

⚠️ 另外一条教训：**批量改资源文件前，先确认能不能回滚。**
本机 `git` 的 HTTP(S) 操作不可用（走代理会 502，清代理会 connection reset），
所以远程同步暂时别指望，本地 git 操作（init/add/log/commit）正常。

---

## 4. 🟡 构建必须显式指定 JDK 17

```bash
JAVA_HOME=$HOME/.workbuddy/binaries/jdk/17/jdk-17.0.20.1+1 ./gradlew <任务>
```

本机默认 `JAVA_HOME` 指向 Android Studio 的 jbr（JDK 25），
**Gradle 8.14.3 不支持 JDK 25**，不指定就报版本错误。
这是本机最高频的构建失败原因，换机器时 JDK 路径要相应调整。

---

## 5. 🟡 README 已过期，别照它判断项目状态

`README.md` 仍写着「当前阶段为 **M1–M2（Fork 骨架 + 构建验证）)**」，
能力表里「图片浏览」「SMB 性能层」还标着**待实现**，流媒体写着「M3+M4 已实现」。

实际状态见 `STATUS.md`。**README 属于 M8 待办，接手后应更新。**

---

## 6. 🟡 已知的技术取舍（别"顺手优化"掉）

| 取舍 | 现状 | 为什么别改 |
| --- | --- | --- |
| 图片只支持 SMB | SFTP/FTP/WebDAV 不做图片查看器 | SFTP/FTP 无连接池；WebDAV 已有 `content://` 路径，再套回环 = 同一件事两套机制 |
| 缓冲不"统一" | 读缓冲与 SO 超时是独立常量 | 消除魔法数字 ≠ 值应该相同 |
| 不加密用 JCE / 加密用 BC | `SmbSecurityProviderTest` 钉住 | 实测平台 JCE **拿不到 AES-CCM**，只有 BC 满足 SMB3 |
| 回环服务器只接回环来源 | 其他一律 403 | 这是安全边界，不是可选项 |

---

## 7. 🟡 已踩过的 API 坑（改 Coil/Media3 前先看）

| 坑 | 实际情况 |
| --- | --- |
| Coil 2.7 没有 `Size.SCREEN` | 只有 `Size.ORIGINAL`；不调 `.size()` 即按 composable 约束自适应 |
| Coil 2.7 `ImageRequest.Builder` 没有 `imageLoader()` | 那是 Coil 3.x。2.7 要用 `AsyncImage(imageLoader = ...)` |
| Coil 2.7 `enqueue()` 返回 `Disposable` | **没有 `await()`**。预取要用 suspend 的 `imageLoader.execute(request)` |
| `coil.imageLoader` 扩展在 coil-base | 不在 coil-compose |
| Intent 没有 `getBundleArrayListExtra` | 本项目用 3 个并行 `putStringArrayListExtra`（url/title/token），以避开 kotlin-parcelize 插件 |
| 资源文件里的裸撇号 | 上游惯例是 `\'`，aapt 接受 XML 原生的裸撇号会报 `Invalid unicode escape sequence`。⚠️ **改多处时别用全局 replace**（见 §3 事故） |

---

## 8. 协议层硬限制（M6 聚类范围已定）

**SMB 没有通用的文件哈希 API。** 所以"按内容聚类/查重"在远程只能拉流到本地算哈希——
对 40GB 的视频目录不可接受。

**✅ 已做决定（2026-10-11）：聚类只做本地。** `DuplicateScanner`（两级：先按 size 分组，
再 SHA-256，9 项单测）维持 `state.source == FileSource.LOCAL` 门控，远程协议不做内容级
查重，UI 上不承诺远程支持。理由：远程流式哈希对大目录不可接受，而"诚实标注只支持本地"
远好于一个点开就卡死的远程聚类入口。如果未来要做远程，正确路径是先给 SMB 加增量哈希
或按 size+mtime 做弱去重，那是一个独立立项，不是顺手能加的。

排序是纯客户端操作（`listFiles` 不接受 sort 参数），聚类视图天然只能在客户端做。

---

## 9. 本轮接手后新踩的坑（2026-10-11）

| 坑 | 现象与成因 | 处置 |
| --- | --- | --- |
| smbj 的 `fileExists/folderExists` 不认 `STATUS_NO_SUCH_FILE` | impacket 类服务器对"不存在"回 NO_SUCH_FILE，真实 NAS 回 OBJECT_NAME_NOT_FOUND；smbj 的状态处理器只认后者 → 存在性检查直接抛异常 | `SmbFileProvider.existsInShare/isDirectoryInShare` 统一把 NO_SUCH_FILE 折叠为 false |
| impacket 打开文件时忽略目录标志 | `folderExists(文件)` 在 impacket 上"成功打开"→ smbj 内部 File→Directory 强转崩溃 | 目录判定改走 `getFileInformation().standardInformation.isDirectory`（query-info） |
| **impacket-on-Windows 无法验证重命名** | SMB2 要求文件打开时发 SET_INFO 重命名；Windows 文件锁让 impacket 内部的 `os.rename` 必败（`STATUS_ACCESS_DENIED`）。Linux 宿主机无此问题 | 已验证到 SET_INFO 前的每一步；详见 `tools/smbtest/README.md` |
| 重装 APK 会重置所有文件访问权限与 SAF 引导 | `install -r` 后 `MANAGE_ALL_FILES` 授权失效，存储引导页重现（远程连接等 DataStore 数据保留） | 每次重装后重走一次引导即可 |
| Compose edge-to-edge 下悬浮 chip 被状态栏压住 | 播放器角落按钮一半在状态栏区域内，点击丢失 | 已加 `statusBarsPadding()`（`StreamPlayerActivity`） |
| 外抛到系统播放器时，部分应用收不到回环 URL | Google Photos 禁明文 HTTP（Android 9+ 默认），直接失败；VLC/MX Player 等专业播放器放行 | 属目标应用策略，非本应用可解；回环服务跨应用可达已用独立进程验证（206 响应） |

---

## 10. 🔴 图片缓存键用了易失 URL / 清除被在途写盘绕过 —— 已修（2026-10-14 起，2026-10-17 收尾）

这是外部审查指出的问题，**已确认为真缺陷并修掉**。根因比"清除不生效"更深一层，
且是缓存完全不工作的原因，因此单独列条。

### 根因

`RemoteImageLoaderFactory.modelFor()` 只设了 `memoryCacheKey`，**没有设 `diskCacheKey`**。
于是 Coil 的 `HttpUriFetcher` 走 fallback，拿 **URL** 当磁盘 key。而 URL 是：

```kotlin
// LoopbackStreamServer.publish
val url = "http://$LOOPBACK_ADDRESS:$currentPort/s/$token/$safeName"
```

- `currentPort`：每次 `server.start()` 随机
- `token`：每次 `publish()` 32 字节全新随机值

**所以同一个 NAS 图片，每次展示都是全新的 disk cache key。** 后果：

1. 旧 entry 永远不被命中，只会在 128 MB 上限内堆积 → 缓存涨到几十上百 MB，
   其中绝大部分是**永远不会被再次命中的死数据**。
2. **每次冷启动都重新走一遍 SMB** 下载所有缩略图（内存缓存随进程消失）——
   这本来正是磁盘缓存要解决的问题。
3. 清除后只要再浏览一次目录，就用一批新 key 重新写满 → "清除完全没变化"。
   审查说的"在途请求绕过 evictAll"是真实但次要的叠加因素。

同时 `cacheKey(file)` 原本只含 `path|size|mtime`，**不含连接身份**，
而 `FileItem.path` 只带 share 与 share 内路径 → 两台导出同名 share 的 NAS 会互相撞键。

### 修法

- `RemoteImagePublisher.cacheKey(file)` / `cacheKey(connection, file)`：
  稳定身份 = `protocol://user@host:port/share/path|size|mtime`。
  含 size+mtime 保证文件被覆盖后重新拉取，而不是继续显示旧图。
- `modelFor()` 同时设 `memoryCacheKey` 与 `diskCacheKey`（原来只有前者）。
- `ImageViewerActivity`：`PublishedImage` 新增 `cacheKey` 字段，
  主图请求与邻页预热请求都用它，且 key 随 `EXTRA_CACHE_KEYS` 过 Intent ——
  **全尺寸照片才是几十 MB 的大头**。
- `RemoteVideoThumbnailLoader`：内存 LruCache 的 `Key` 去掉 `url`，
  改用稳定的 `version`（= `RemoteImagePublisher.cacheKey(file)`，连接域身份）+ 尺寸。

### 关于"清除"本身（2026-10-17 重做，机理已定死）

审查指出的第二个问题（在途写盘绕过 `evictAll`）确认为真，重查时把机理钉在了 Coil 2.7
源码上：`HttpUriFetcher.writeToDiskCache` 把响应**整段写进 disk cache 再 commit**，
这一切发生在 `Fetcher.fetch()` 返回之前；`evictAll()` 只能给已有 editor 的 entry 打
`zombie` 标记，**拦不住"清除之后才开 editor"的写入**。取消也拦不住：协程取消只在挂起点
生效，而"读 socket → 写缓存文件 → commit"是一段不响应取消的阻塞拷贝。

上一轮评估过 Interceptor 路线并放弃（`componentRegistry(Function1)` 是废弃桩，评估没错），
但**遗漏了正确的公开 API：`ImageLoader.Builder.eventListenerFactory`**（Coil 2.0 起稳定）。
EventListener 的 `onFetchStart` / `onFetchEnd` 精确包夹 `Fetcher.fetch()`；既然磁盘
commit 在 fetch 返回前发生，那么**"没有 fetch 在途"就等于"不会再有 commit 落盘"**。
于是第二段等待从固定延时改为排空：

```kotlin
sweep()                              // 清 memory + disk + 4 个专用 loader
_imageCacheGeneration.value++        // 首页卡片先显示 ~0
ImageFetchTracker.awaitIdle(6_000)   // 等最后一个 fetch 结束（有上限）
sweep()                              // 此刻的清扫是终态
_imageCacheGeneration.value++
```

`ImageFetchTracker`（`app/…/app/ImageFetchTracker.kt`）只计数、不改请求行为：
`fetchStart` 加一，`fetchEnd`/`onSuccess`/`onError`/`onCancel` 里取"最先到的一次"减一
（fetch 抛异常时 `fetchEnd` 不会触发，由终止事件兜底；CAS 保证恰一次）。
测试：`ImageFetchTrackerTest`、既有 `RemoteImageCacheKeyTest`。

### 2026-10-17 追加修复：查看器 cacheKey 曾断在 Intent 边界

10-14 改动给 `PublishedImage` 加了 `cacheKey`、查看器请求也开始用它——**但 key 没进 Intent**：
`intent()` 只传 url/title/token，`render()` 重建 entry 时 cacheKey 落回空串。Coil 对显式
空 key **不做兜底**（`MemoryCacheService.newCacheKey` 对显式 key 原样返回 → `Key("")`），
后果是所有全尺寸照片共用同一个空 key 的缓存位，翻页可能拿到上一张的字节。
修复：`EXTRA_CACHE_KEYS` 随 Intent 传递；请求处 `takeIf { it.isNotEmpty() }`，
空 key 回退 Coil 默认键而不是空串（`ImageViewerActivity`）。

### ⚠️ 残留（比上一轮收窄）

- 第二段清扫后**只有"新发起的请求"能再写缓存**——即用户真的又去看图了，这是缓存的本职
  工作，不再是"清完自己长回来"。
- `CACHE_DRAIN_TIMEOUT_MILLIS = 6s` 仅为兜底（事件丢失/卡死）；超时后第二段清扫照常执行。
- 曾经列出的两条"彻底方案"（shutdown+重建 / Coil 3.x）**不再需要**。

**当前状态：Coil 磁盘缓存已整体移除（2026-11-14）。** 下面的 journal 修复曾让"清除后
数字不回升"，但它修的是症状；根本问题是**缓存里存的是原图**。新架构见本节末「2026-11-14」。

### 2026-10-18 根因定案：清除后的数字回升 = journal 幽灵条目（真 bug，已修）

用户的关键观察推翻了"重缓存"假设：**清除 → 卡片显示 0 MB → 直接退出应用 → 重开，
中间没有任何浏览 → 数字回到 100+ MB**。重缓存不可能凭空发生，说明 0 与 100+ 之间有个
持久化漏洞。用 Gradle 缓存里的 coil-base-2.7.0 `runtime.jar` 反编译 `DiskLruCache`
字节码，机制确认如下：

**`evictAll()` 的 REMOVE 行只写入内存缓冲，不落盘。** `removeEntry()` 的普通删除路径
（文件已删、`size` 已减）只执行 `writeUtf8(REMOVE)`，**没有 `flush()`**；唯一的 flush
在 zombie 分支（写 DIRTY 之后）。`evictAll()` 自身也没有 flush。于是：

1. 清除 → 文件真删、内存 `size` 真降到 0 → 卡片诚实地显示 0 MB ✓
2. 用户立即退出 → 进程被杀 → **缓冲里的 REMOVE 行随进程丢失**
3. 重开 → 新 `DiskLruCache.initialize()` 读 journal **文件** → 旧 CLEAN 条目全部复活
   → `size()` 报旧值 100+ MB ✓

这些"复活"的条目没有对应文件（文件已删），是**幽灵**：数字虚高、不占空间；被访问时
读文件失败 → 回退网络重取 → 条目自愈。这也解释了为什么 v0.1.7/v0.1.8 的稳定 key 与
fetch 排空"看起来没修好"——它们修的是真问题，但不是这一个。同一漏洞也解释最早
v0.1.6 时代"清除后退出重进数字回来"的主因（当时被误判为在途写盘）。

**修法**：`DiskCache.flushJournal()`（`app/src/main/java/com/naslink/app/
DiskCacheJournalSync.kt`）——`edit()` 成功路径在返回前必然 `flush()`（字节码
指令 133 实证），所以开一个 scratch editor 再 `abort()`，就能把缓冲中的 REMOVE 行
连同新写的 DIRTY/REMOVE 一起落盘（`completeEdit` 也有 flush）。scratch key 经
`RealDiskCache.hash` 处理，不可能与真实图片键冲突。`clearImageCache` 的两段清扫
都在 `clear()` 后立即调用它。

**回归测试**：`DiskCacheJournalFlushTest`（纯 JVM，无需模拟器）——同一进程中在同一
目录上新建 `DiskCache` 实例来模拟"重启读 journal"：不 flush 时断言幽灵复活
（300 B 回来），flush 后断言为 0。两组断言分别钉住缺陷与修复。

### 2026-11-14 根因定案：预览缓存存的是原图，且加载不看滚动状态

用户的新观察把方向彻底改变了：**打开一个几百张图片的文件夹，什么都没点开，缓存就到了
几百 MB**。这不是 key 或 journal 的问题，而是数据模型的问题：

- Coil 的磁盘缓存存的是 **fetcher 产出的字节**。远程图片走 `HttpUriFetcher`，产出就是
  **整个原图文件**；一张 5 MB 的图只用来画一个 300 px 的格子，磁盘上就是 5 MB。
  300 张 = 1.5 GB（被 512 MB 上限截断，所以"几百 MB"）。
- 缓存条目按**请求**（url + size + transformations）存，没有"把原图换成缩略图"这条路，
  所以只能整体清空、无法降级。
- 网格的载荷是 `AsyncImage` 在组合时立即发请求，快速滑动时一屏外被复用的格子也会发，
  等于"路过就下载"。

**改法（同一天完成）：**

| 组件 | 作用 |
| --- | --- |
| `ui/components/ThumbnailDiskCache.kt` | 纯 `java.io.File` 的 LRU，存**生成后的预览字节**；按 SHA-256(key) 命名，写入先临时文件再 rename |
| `ui/components/ThumbnailCaches.kt` | 两个预算：图片 48 MB / 视频 24 MB；`sizeBytes()` 每次真实扫描目录 |
| `ui/components/RemoteImageThumbnailLoader.kt` | 内存 LRU → 磁盘预览 → **有界读取源文件**（≤16 MB 全读；更大的只读 256 KB 取 EXIF 预览）→ 采样解码 → WebP 落盘 |
| `ui/components/JpegExifThumbnail.kt` | 手写 EXIF 预览提取（不依赖 `ExifInterface`，后者对非 JPEG 会读完整流） |
| `StreamPlaybackCoordinator.openRandomAccess` | 直接借池化会话开 `RandomAccessSource`，不 publish、不起 HTTP 服务 |
| `ThumbnailLoadingGate.setScrolling` + `awaitReady()` | 滚动中**挂起**（不是取消）预览生成；离开屏幕的格子协程被销毁，读取根本不会发生 |
| `ImageViewerActivity` | 删除邻页预热；用内存里的预览当 `placeholder`；不再写任何磁盘缓存 |

结果：一个 300 张的文件夹全部看一遍，缓存约 **9 MB**（300 × ~30 KB），且只有真正滑到、
停留过的图才会被读取。代价：首次预览仍然是"整图读一次"（换来清晰度），超大图走 EXIF
预览（160–320 px）。

**已删除的旧机制（别照着旧文档找它们）：** `DiskCacheJournalSync.kt`（`flushJournal`）、
`ImageFetchTracker.kt`（fetch 排空）、`RemoteImageLoaderFactory.kt`（Coil 模型构造），
以及 `MaterialBrowserApp` 里的 512 MB 磁盘缓存配置。清除缓存不再需要"两次清扫 + 排空"：
现在所有写入都经过 `ThumbnailDiskCache.put`，持同一把锁，没有第三方写手与清除竞争。

---

## 11. 🔴 会话与发布泄漏（4 处）—— 已修（2026-10-17）

一次静态审查（未用模拟器）查出四个真实泄漏。共同主题：**会话只创建不回收**。

### 11.1 `onCleared()` 在已取消的 scope 上 launch，断连永远不执行 —— 高

`FileBrowserViewModel.kt:1610`

```kotlin
override fun onCleared() {
    ...
    viewModelScope.launch {              // ← scope 此刻已取消
        sessionProviders.values.forEach { it.disconnect() }
    }
}
```

反编译 `androidx.lifecycle.ViewModel` 2.11.0 确认 `clear$lifecycle_viewmodel()` 的顺序是
先 `ViewModelImpl.clear()`（取消全部 closeable，含 viewModelScope 的 job）**再**调 `onCleared()`。
所以这里 launch 拿回的是已取消的 Job，**函数体一行都不会跑**——每次 ViewModel 销毁
（退出浏览器 / Activity 重建）都必然发生。后果是所有 SMB/SFTP/WebDAV 会话连同其中的凭据
泄漏到进程结束。

> 讽刺的是上一行注释已经写明"viewModelScope is cancelled at this point"，
> 作者察觉了资源问题（所以先同步 `close()` 了特权管道），却漏了其余 provider。

**修法**：`NasLinkApp` 新增 `teardownScope`（`SupervisorJob() + Dispatchers.IO`），
断连改在它上面启动——它比 ViewModel 活得久，正是这份工作需要的。
沿用项目既有惯例（`WatchProgressRepository.persistScope`）。

### 11.2 `SmbSessionPool` 每次未命中泄漏一整条连接 —— 高

`stream/SmbSessionPool.kt`

缓存里只放 `Session`，而 smbj 的 `Session.close()` 字节码只有一条 `invokevirtual logoff()`
——**它不碰 `Connection`，也不碰 `SMBClient`**。两者在 connector 内部创建，成功路径上从未关闭。
每次缓存未命中（首播、过期重连、并发竞争）就漏一条 socket + client + connection。

这直接反转了该类自己的设计目的：其 KDoc 写着"入站会话上限约 20，漏几个句柄就足以让服务器
拒绝新连接"——泄漏方向恰好是它要防的那个。

**修法**：缓存改持 `PooledConnection`（session + connection + client 三元组），
`closeResource` 三者依次关闭；每步独立 `runCatching`，一处失败不会卡住其余。
`SmbRandomAccessSource.openHandle()` 相应改取 `lease.resource.session`。

### 11.3 缩略图 publication 只增不减 —— 中高

`FileThumbnail.kt:200`、`RemoteImageLoaderFactory.kt:30` 只 `publish` 从不 `revoke`
（全仓 `revoke()` 调用点只在 viewer / player 的整批回滚路径上）。
滚动一个 5000 张的目录就会留下数千个 publication，每个都**常驻一个已打开的 SMB source**
（`Session.warm`），直到 30 分钟空闲清扫——而 30 分钟内的累积足以击穿会话池的意义。

**修法**：`RemoteImagePublisher` 按 `cacheKey` 复用 token，access-ordered LRU 上限
`PUBLISHED_CACHE_LIMIT = 64`（高于一屏+回滚缓冲，远低于 ~20 的会话上限），
淘汰时 `revoke`。为安全起见新增 `StreamPlaybackCoordinator.isPublished/urlFor`
与 store 的 `lookup/isPublished`——**探测刻意不刷新空闲时钟**，否则"问一下还在不在"
会把废弃的 publication 永久钉住，让空闲清扫失效。

### 11.4 FTP / WebDAV 的连接竞态 —— 中

`FtpFileProvider.ensureConnected()`、`WebDavFileProvider.ensureConnected()`
都是无锁的 check-then-act。两个协程同时看到空连接、各自建连，后者覆盖前者，
先建的那条**既不复用也不关闭**（WebDAV 侧漏的是整个 `OkHttpClient`，含线程池）。
字段也非 `@Volatile`。

FTP 还把**单个 `FTPClient` 共享给所有调用**，而 FTP 控制协议非线程安全：
`getInputStream` 的流要靠 `close()` 才 `completePendingCommand()` 收尾，此期间别的命令会
打乱待决应答；`isDirectory()` 靠改工作目录判断，是有副作用的状态变更，并发调用会互相踩。

> 与之相对，`SmbFileProvider` 有 `stateLock`、`SftpFileProvider` 有 `Mutex`——
> **只有 FTP 和 WebDAV 两个 provider 完全没有保护**。

**修法**：`ensureConnected` 加锁（FTP 用 `Mutex`、WebDAV 用 `synchronized`），
改为**返回**已校验的 client 而非让调用方重新读字段——这样并发 `disconnect()` 只会让命令
以 IOException 失败（真实的失败），而不是让 `!!` 抛 NPE。`ftpClient` 标 `@Volatile`。
`exists()` 不再 `catch (_: Exception)`（会连协程取消一起吞掉），改为放行 `CancellationException`。

**残留**：`FTPClient` 被多协程共享导致的控制协议失步**未修**。彻底修需要让所有 FTP 命令
串行化，但 `getInputStream` 返回的流生命周期长于任何锁的作用域，无法用单锁覆盖——
需要改成"每次调用各自建连接"或引入连接池，属架构级改动，**留给接手人决策**。

### 顺手修掉的一个小问题

- **FTP 递归删除/复制路径重复拼接**（ProFTPD / Pure-FTPd）：`LIST` 可能回全路径，
  而 `deleteDirectoryRecursive` / `copyDirectoryRecursive` 直接拿 `file.name` 拼父路径，
  得到 `/a/b//a/b/c.txt`。现统一走 `entryName()` 取最后一段——该文件 KDoc 自己就记录了这个前提。

> 另有一处**核实后确认不是缺陷**：`gridColumns` 的 `stateIn` 初值为 0，
> 但 `BrowserScreen.kt:1283/1292` 消费时已有 `gridColumns in 1..4` 兜底，不会渲染空网格。
> **无需改动。**

---

## 12. 🔴 浏览一段时间后 NAS 整体变慢直至 source error —— 已修（2026-11-14）

**症状**：刚进应用时一切正常，网速能到几十 MB/s；用一阵子后掉到几 KB/s，之后任何
视频/图片都打不开（播放器报 source error），**退出进程重开必定恢复**，然后再复现。

这不是"网速"问题，是**连接数**问题。进程内所有 SMB 读取共享 `SmbSessionPool`，
而它每个 identity **只缓存 1 条连接**；被占用时后来者直接**另拨一条私有连接**，
用完即关。三个放大器叠在一起：

1. **网格预览**：旧路径给每张图 `publish` 一个回环 URL（LRU 上限 64，只增不减）。
   每个被读过的 publication 的 `RandomAccessSource` 会**一直持有** file handle、
   tree connect 和一条会话租约，直到 64 条后淘汰或 30 分钟空闲清扫。
   翻 300 张图的文件夹 → 同时活着的连接可达 64 条。
2. **查看器**：`openSmbImages` 把整个文件夹都 publish；每翻一页读一次，就多一条
   **永不释放**的连接（同样只等 30 分钟清扫）。看 30 张图 = 30 条会话。
3. **并发即拨号**：`SessionReuseCache` 发现缓存条目 busy 就立刻 `connect()`，
   而缩略图并发度是 2 —— 第二个请求几乎永远在"另拨一条"。一次滚动 = 上百次
   SMB session setup（每次含 NTLM 握手，慢 NAS 上百毫秒）。

消费级 NAS / Windows 的入站会话上限通常 20～60。越过之后新会话要么很慢、要么被拒，
于是吞吐塌到 KB/s，回环 HTTP 层只能回 502 → 播放器显示 source error。
进程重启清空所有连接 → 立刻恢复，完全对得上"重开必好"。

**改法（四层，缺一层都会复发）**：

| 位置 | 改动 |
| --- | --- |
| `SessionReuseCache.acquire` | 新增 `waitForMillis`：缓存条目被占用时**先等**（默认 1.5s，分片等待、release 时 `signalAll`），超时才拨私有连接。并发缩略图从此共用一条会话 |
| `SmbRandomAccessSource` | 句柄空闲 **10s 自动归还**（`releaseIfIdle`，读操作前检查，`activeReads` 保护在途读）。publication 不再钉住连接，下次读按需重开 |
| `StreamSessionStore.releaseHandle` | 查看器翻页时把**非当前页**的句柄立刻还回池子（在途请求由 `activeTransfers` 保护，不会被截断） |
| `SmbSessionPool` | `DEFAULT_SO_TIMEOUT_MILLIS` 180s → **20s**；读失败**第一次**就把租约标 broken（原来要失败两次），重试因此拿到全新连接而不是同一具尸体 |

另外 2026-11-14 的缩略图重写（见第 10 条）顺带**彻底移除了网格的 publication 路径**：
网格现在用 `openRandomAccess` + `use {}`，读完即关，不再有 64 条常驻连接。

**回归测试**：`HandleIdlePolicyTest`（6 项，纯 JVM，钉住空闲判定的符号/边界/单调时钟）、
`SessionReuseCacheTest` 新增 3 项（等待复用、超时后私有、零预算不阻塞）。

**残留**：每个缩略图仍是一次 tree connect + open/close（约 3 个往返，局域网几毫秒）。
若要进一步压缩，可把 `DiskShare` 按 (session, share) 缓存进 `PooledConnection`；
未做，因为 smbj 的 `DiskShare` 并发语义没有文档保证，静态审查无法验证。

---

## 13. 其他待办

- **Room 只有 2 张表**（`remote_connections` / `bookmarks`）。M4 的播放进度记忆需要自己建表。
- **查看器无 UI 层仪器化测试**；整个 UI 层自动化覆盖很薄。
- **签名密钥未配置**（M8 阻塞项，需要接手人自己的密钥）。
- **`metadata/` 里 20 条 changelog 沿用上游**，未核对。
- `scripts/` 下有 5 个 Crowdin 脚本，其中 4 个自带测试（`test_*.py`），
  改翻译流水线时先跑它们。
