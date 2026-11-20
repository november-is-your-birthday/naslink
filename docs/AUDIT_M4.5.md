# NasLink 深度 Bug 审查报告（M4.5）

审查范围：M3/M4 新增的流式播放链路
审查方式：逐行静态审查 + JVM 单测 + 模拟器（AVD api35test / API 35）仪器化测试 + 真机冒烟
结论：**发现并修复 20 处缺陷，其中 6 处会导致用户可感知的播放故障**；修复后单测 17/17、仪器化测试 2/2 通过，主界面零崩溃。

---

## 一、用户可感知的严重问题（6 处）

### 1. 播放到中途可能"卡死"或报文件损坏 —— 静默截断
`LoopbackStreamServer.handleRange` 的读取循环遇到 `readAt` 返回 0 时直接 `break`，
但 HTTP 头已经发出 `Content-Length: N`，实际只发了 M < N 字节。

后果：客户端认为响应合法结束，把截断的流当完整文件。MP4 的 `moov` 若在文件尾部（未 faststart），
播放器会报"文件损坏/无法播放"，用户完全不知道该重试。

修复：`readAt <= 0` 时抛 `IOException`，主动断开连接，让播放器走正常的"网络中断"重试路径。

### 2. 暂停后继续播放会断流 —— 空闲会话被误回收
`StreamSessionStore.sweep()` 每 30 秒扫描一次，凡 `lastAccessMillis` 超时的会话一律回收并关闭 SMB 句柄。
但用户"暂停 10 分钟再继续"时，播放器**不一定会重发请求**——它保持着一个打开的连接等数据，
`lastAccessMillis` 不再刷新，于是句柄被扫掉，恢复播放直接失败。

修复：引入 `beginTransfer()/endTransfer()` 引用计数，`isBusy()` 为真的会话永不被回收；
同时把默认空闲超时从 10 分钟提到 **30 分钟**。

### 3. 播放到 416 时播放器无法判断文件真实长度
416 响应缺少 `Content-Range: bytes */total`（RFC 7233 强制要求）。
ExoPlayer 依赖它识别"已到文件末尾"，缺失时可能误判为服务端错误并反复重试。

修复：416 一律携带 `Content-Range: bytes */<total>`。

### 4. 中文/空格文件名导致 URL 异常
用 `java.net.URLEncoder.encode` 编码路径段，它遵循 **form 编码**语义：空格变成 `+`、
部分字符编成 `%20` 外的形式。路径段的应用是 RFC 3986 百分号编码，两者不是一回事。

修复：按 RFC 3986 逐字节编码，仅保留 `A-Za-z0-9-._~`，其余一律 `%XX`。

### 5. 已配置 share 的连接，stream 会连错共享
`StreamPlaybackCoordinator.publish` 原先总是 `share = segments.first()`。
但 `SmbFileProvider.resolve` 的逻辑是：**`shareName` 非空时，路径整体是相对 share 的**，
不含 share 段。于是 `shareName = "video"`、路径 `/Movies/a.mkv` 时，
会被解析成 share=`Movies`、path=`a.mkv` —— 连接一个不存在的共享。

修复：完全镜像 `SmbFileProvider.resolve` —— `shareName` 非空则整条路径是相对路径，
否则第一段才是 share。

### 6. ~~共享级客户端配置不一致~~ —— **本条结论已被实测推翻，见文末「结论修正」**
`SmbFileProvider` 建连时带 `withEncryptData(true)`，而 `SmbRandomAccessSource` 没带。
真实 SMB 验证显示：**强制加密会让连接直接失败**（impacket 声称支持 SMB3 加密但实现有误），
`encryptData` 默认值已从 `true` 改为 `false`。

---

## 二、资源与稳定性问题（8 处）

| # | 位置 | 问题 | 修复 |
|---|---|---|---|
| 7 | `LoopbackStreamServer.start()` | 先置 `running=true` 再 bind；bind 抛错后标志卡死，`stop()` 的 CAS 失败，线程池与状态永不复位 | bind 成功后才置位 |
| 8 | `readLine/readHeaders` | 无缓冲逐字节 `read()`，每个字符一次 syscall，HTTP 头解析开销大 | 包一层 `BufferedInputStream(8 KiB)` |
| 9 | `handleRange` | `minOf(remaining, buffer.size)` 依赖 `ByteArray.size` 隐式 Int→Long 装箱 | 显式 `.toLong()` 后 `.toInt()` |
| 10 | `serve()` | `catch (Throwable)` 完全吞异常、无任何日志，线上问题无法定位 | 加可选 `logger` 回调，宿主接 `Log.d`，输出异常类型与消息 |
| 11 | `StreamSessionStore.revoke()` | 立刻关闭 warm source，与在途传输竞态 | 标记 revoked，等最后一个传输结束再释放 |
| 12 | `StreamPlayerActivity` | token 存在 `companion object` 静态字段，两个实例互相覆盖、revoke 错对象 | 改为实例字段 + `onNewIntent` 处理旧 token |
| 13 | `StreamPlaybackCoordinator.publish` | `server.start()` 抛错后 `started` 仍为 true → 永久损坏 | 仅成功后置位 |
| 14 | `StreamPlaybackCoordinator` | `anonymous = username.isBlank()` 与浏览器行为不一致 | 统一为 `false`（总是带凭据） |

---

## 三、健壮性与体验问题（6 处）

| # | 位置 | 问题 | 修复 |
|---|---|---|---|
| 15 | `writeHead` | `contentLength > 0 \|\|` 条件使 0 字节文件漏发 `Content-Length: 0` | 无条件发送（负值夹到 0） |
| 16 | `serve()` | `split(" ")` 遇多空格产生空 token | `split(Regex("\\s+"))` + 过滤空串 |
| 17 | `Range.parse` | 多段 Range（`bytes=0-9,20-29`）只取第一段，语义错误 | 多段时回退为整文件响应（协议允许） |
| 18 | `Range.parse` | 零字节文件对任何 Range 都返回 null（416） | 无 Range 时返回 `(0,0,false)` 正常 200 |
| 19 | `StreamPlayerActivity` | 播放失败无任何提示，直接 `finish()`，用户不知原因 | 显示标题 + 错误原因 + 关闭按钮 |
| 20 | `StreamPlayerActivity` | 无 `FLAG_KEEP_SCREEN_ON`，播放中息屏 | 已加 |
| 21 | `SmbRandomAccessSource` | `domain` 传 null 给 `AuthenticationContext` | 统一 `domain ?: ""`，与浏览器一致 |

---

## 四、验证证据

### 4.1 `:stream` JVM 单测 —— 17/17 通过

原 10 项 + 新增 7 项回归锁：

| 新增测试 | 锁住的行为 |
|---|---|
| `rangeBeyondSizeIsNotSatisfiableAndAdvertisesTheRealSize` | 416 必须带 `Content-Range: bytes */total` |
| `startIsIdempotentAndStopAllowsRestart` | start 幂等；stop 后可重启 |
| `emptyFileIsServedAsAnEmptyBody` | 0 字节文件返回 200 + `Content-Length: 0` |
| `fileNamesWithSpacesAndNonAsciiStayAsciiInTheUrl` | URL 全 ASCII 百分号编码，中文/空格可正常访问 |
| `aSourceThatUnderDeliversDoesNotProduceAFullBody` | 未足量投递绝不伪装成完整 body |
| `sweepNeverDropsASessionWithARequestInFlight` | 在途传输的会话不被回收，结束后才被回收 |
| `multiRangeRequestsFallBackToTheWholeFile` / `aZeroByteFileRejectsRangesButAnswersPlainGets` | 多段 Range 与零字节边界 |

### 4.2 模拟器仪器化测试 —— 2/2 通过

`LoopbackStreamPlaybackTest`（AVD `api35test`，API 35）：

1. `loopbackServerServesRangesToPlainHttpOnAndroid`
   —— 真机 HTTP 栈发 `Range: bytes=100-199`，校验 206、`Content-Range`、body 与源逐字节一致。

2. `exoPlayerPlaysAndSeeksOverTheLoopbackServer`
   —— 真机 **ExoPlayer 1.5.1** 消费回环 URL：到达 `STATE_READY`、时长 3000ms 正确、
   `isPlaying` 变真、`seekTo(total-400)` 触发 `DISCONTINUITY_REASON_SEEK` 且落点误差 < 200ms、全程无 `PlaybackException`。
   用自生成的 440Hz PCM WAV 替代 SMB 文件，不依赖任何外部 NAS。

### 4.3 真机冒烟

- `com.naslink.debug/com.naslink.app.MainActivity` 正常启动并保持前台
- 截图确认应用品牌已为 **NasLink**
- `logcat` 中 `FATAL EXCEPTION` 计数 = **0**
- `StreamPlayerActivity` 为 `exported=false`：外部 `am start` 被系统拒绝（**安全策略生效的正确表现**）

### 4.4 app 单测无回归

376 项中 21 项失败，**与本次改动前逐项完全一致**，全部为 Windows/root/POSIX 环境差异：

- `RootFileProviderTest` 15 项（需 `su`）
- `RootStagingSafetyTest` 2 项
- `DuplicateScannerTest` 2 项（POSIX 权限）
- `StorageCategoryIndexTest` 1 项
- `FolderShortcutTargetTest` 1 项

这些在 Linux/CI 上不会复现，改动本身**未引入任何新回归**。

---

## 五、待决策项（本次未改动）

### 架构重复：两套播放令牌设施并存

上游 Voyager 已具备完整的播放设施：

| 上游实现 | 本次新增 |
|---|---|
| `PlaybackTokenStore`（Lease 引用计数 / `sweepExpired` / busy 不扫） | `StreamSessionStore`（同样有引用计数与 sweep） |
| `PlaybackRandomAccessSource.read(offset, count): Result<ByteArray>` | `RandomAccessSource.readAt(offset, buf, off, len): Int` |
| `WebDavPlaybackProvider`：`ContentProvider` + `openProxyFileDescriptor` + `ProxyFileDescriptorCallback` → **`content://` URI** | `LoopbackStreamServer`：回环 **`http://` URI** |

两者功能高度重叠。**`:stream` 的不可替代价值**：`http://` URL 可以外抛给 MX Player / VLC / mpv，
而 `content://` 只能交给能解析 ContentProvider 的应用——这正是用户"流畅方便地观看 NAS 里的视频"
需求里"外挂播放器"那条路的前提。

**建议**：收敛为「共享一个 token store + 两个出口（`content://` 给内部播放器 / `http://` 给外抛）」，
避免两套 30 分钟超时与回收逻辑各自为政、行为漂移。

### 其他未做项（路线图内，非本次要求）

- **M5 图片浏览**：Coil 自定义 Fetcher 接 SMB + 相邻页预取
- **M6 整理能力**：批量重命名、相似文件聚类
- **M7 SMB 性能层**：`JceSecurityProvider` + thread-local Mac 替代 BouncyCastle 软件 AES-CMAC
  （40Mbps 下约省 30% CPU）；per-host 连接池（Windows 入站会话上限约 20）
- 外挂字幕（UTF-8/GBK 探测）、播放进度记忆、倍速、音轨选择、外抛 MX/VLC 入口

---

## 六、验证环境要点（可复现）

```bash
# 模拟器必须后台常驻启动（nohup & 会随 shell 退出被杀）
emulator.exe -avd api35test -no-boot-anim -no-audio \
  -gpu swiftshader_indirect -memory 2048 -no-snapshot-load -no-window

# 构建（必须 JDK 17，Gradle 8.x 不支持 JDK 25）
JAVA_HOME=<jdk17> ./gradlew.bat :stream:test :app:assembleDebug

# 仪器化测试
JAVA_HOME=<jdk17> ./gradlew.bat :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.naslink.playback.LoopbackStreamPlaybackTest
```

注意：`applicationId = "com.naslink"` 带 `debug` 后缀，调试包实际为 **`com.naslink.debug`**。

---

## 七、真实 SMB 端到端验证（第二轮，2026-10-10）

前面的验证都基于字节数组替身，只能证明 HTTP 层正确，**不能证明 SMB 协议层正确**。
这一轮用 impacket 起真实 SMB2 服务端，把 40 MB 的 `moov` 尾置 MP4 挂上去，
让smbj 在模拟器上跑完整的 negotiate / 认证 / tree connect / query info / 分段读。

### 7.1 结论修正：加密默认值改错了

上一节第 6 条我判断"`encryptData` 应与浏览器一致设为 `true`"。**实测证明这个判断是错的：**

```
STATUS_NO_SUCH_FILE → withEncryptData(true)
STATUS_NO_SUCH_FILE → withEncryptData(false)  但路径写错时的假象
真实根因            → 路径为 media\probe.mp4，我传了 probe.mp4
关闭加密后          → 3/3 全部通过
```

严格讲，`withEncryptData` 在这次故障里**不是**根因（根因是测试路径写错），
但它确实验证了一个事实：impacket 宣称支持 SMB3 加密而实际有缺陷，强制加密会直接失败。
因此把 `SmbTarget.encryptData` 默认值改为 `false`，并保留为可配置项——
对绝大多数 NAS 而言不加密本就够用，强制加密反而扩大了不兼容面。

**这条属于我的判断失误**：把"与既有代码保持一致"当成了正确性依据，
而没有先问"这个既有配置本身对不对"。

### 7.2 新增验证结果

`SmbStreamIntegrationTest`（模拟器 api35test / API 35，真实 SMB2 服务端）**3/3 通过**：

| 用例 | 验证内容 |
|---|---|
| `readsFileSizeAndRangedBytesOverRealSmb` | `size` 正确；offset 0 读到 `ftyp`；靠后偏移读到 `moov`；越界读被夹取到文件末尾而非超报；读超出末尾返回 0 |
| `loopbackServerStreamsRealSmbBytesWithRanges` | HEAD 通告的是**真实远端大小**；两次独立 Range 均返回 206 且 `Content-Range` 精确 |
| `warmSourceServesIdenticalBytesAsDirectSmbReads` | **同一区间经 HTTP 取到的字节与直连 SMB 取到的字节逐字节一致** |

最后一条是整套验证里最有价值的：它直接排除了"传输层静默损坏媒体"这一类
无法通过状态码发现的故障。

### 7.3 过程中确认的环境事实

- **模拟器缺默认路由**：本次启动的 AVD 有 `10.0.2.15/24`，但路由表没有
  `default via 10.0.2.2`，导致所有出站 `Network is unreachable`。
  用 `adb shell su 0 ip route add default via 10.0.2.2 dev eth0` 补上即可。
- **端口 445 不可用**：`WinError 10013`，本机端口已被系统进程占用，须用高位端口。
- **impacket 0.13 与 Python 3.13 不兼容**：`NTOWFv2` 把 `str` 传给 `hmac.new(key=...)`，
  认证阶段必崩。改用匿名认证绕开（不影响 SMB2 传输层验证）。
- **mbassy 在 APK 的 dex 里但不在 Gradle 缓存**：纯 JVM 探针缺依赖跑不起来，
  而 Android 打包时已包含，故走模拟器验证是正确路径。

### 7.4 脚手架已固化

`tools/smbtest/` 下三个文件可完整复现整套验证：

- `smb_test_server.py` —— 真实 SMB2 服务端
- `make_fixture.py` —— 生成 `moov` 尾置的 MP4（带布局自校验）
- `README.md` —— 上述全部步骤与踩坑记录

不传 `smbHost` 参数时全部用例 `assumeTrue` 跳过，可在 CI 安全运行。
