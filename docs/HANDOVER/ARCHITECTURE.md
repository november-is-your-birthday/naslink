# 架构：代码怎么串起来的

面向「接手后要改功能」的人。只讲**我们这轮新增/修改的部分**，上游 Voyager 的文件管理架构请自行阅读上游文档。

---

## 1. 两种远程协议栈（务必先分清）

项目支持本地 / SAF / SFTP / FTP / **SMB** / WebDAV 六种 provider。
**只有 SMB 有会话池**，这决定了后面所有设计取舍：

| 协议 | 有连接池？ | 流式播放走什么机制 |
| --- | --- | --- |
| SMB | ✅ `SmbSessionPool` | 回环 HTTP（`:stream`） |
| WebDAV | ❌ | 自己的 `content://` 发布路径（上游已有） |
| SFTP / FTP | ❌ | 无流式播放 |
| 本地 / SAF | 不需要 | 直接 `java.io.File` / `content://` |

---

## 2. 流式播放数据流（核心链路）

这是整个项目最重要的链路。**看视频不下载**就是这么实现的：

```
用户在 SMB 目录点 mkv
        │
        ▼
StreamPlaybackCoordinator.publish()          app/.../playback/
        │  ·解析路径（SmbPathResolver）
        │  ·查 mimeType
        │  ·向 StreamSessionStore 注册 token
        ▼
LoopbackStreamServer.publish()
        │  256bit 随机 token，URL 不含主机/共享/路径/凭据
        ▼
播放器（Media3 / ExoPlayer）拿到：
        http://127.0.0.1:<随机端口>/s/<token>/<文件名>
        │
        │ ExoPlayer 发 HTTP GET，seek 时带 Range 头
        ▼
LoopbackStreamServer.serve()                 stream/.../LoopbackStreamServer.kt
        │  ·只接受回环来源，否则 403
        │  ·解析 Range → 206 + Content-Range
        ▼
StreamSessionStore.Session.beginTransfer()  引用计数，会话不被回收
        │
        ▼
SmbRandomAccessSource.readAt(offset, ...)    跨 Range 保持 SMB 句柄
        │  ·惰性建连，失败重连一次
        ▼
SmbSessionPool.acquire(identity, password)  复用会话（忙则建私有连接）
        │
        ▼
SmbClient (smbj 0.13.0) ──真实 SMB2/3──► NAS
```

**为什么绕回环 HTTP，而不是把文件整体下载？**
播放器（ExoPlayer）只接受一个 URL，而 SMB 没有可直接喂给它的协议。
回环 HTTP 让"随机访问"这件事对播放器变成标准 HTTP Range，
真正的 SMB 复杂度全部收在服务端侧。这个设计参考了 LocalViewer（见 `ATTRIBUTION.md`）。

---

## 3. `SessionReuseCache` 与 `SmbSessionPool`（M7）

**分层是刻意的**：复用逻辑抽成不依赖 smbj 的泛型缓存，因此**没有服务器也能测**。

| 文件 | 职责 |
| --- | --- |
| `stream/.../SessionReuseCache.kt` | 纯逻辑泛型缓存。`acquire(key) { connect() }` → `Lease`。**无任何 I/O**，13 项单测 |
| `stream/.../SmbSessionPool.kt` | smbj 适配层。`Identity(host, port, username, domain, anonymous, encryptData)` → `Session` |

四条不可违背的规则（都写进了测试，改动时注意别破坏）：

1. **忙的资源绝不复用、也绝不阻塞**。`borrowExisting` 命中忙的会话就返回 `null`，
   让调用方建**私有连接**。原因：播放会话会被持有几十分钟，任何等待都会卡死另一个线程。
2. **密码只在握手时用，不保留**。`acquire(identity, password)` 的 password 参数仅用于建连，
   不进 map key、不进日志。（早期版本把它丢了，导致所有非匿名登录失败。）
3. **`encryptData` 必须在 `Identity` 里**。SMB3 加密按连接协商，不能混用。
4. **租约 `broken` 后丢弃**。读失败标记 `lease.broken = true`，归还时不入缓存。

加密后端的选择（已用 5 项单测钉住，别凭直觉改）：

| 场景 | 用 | 原因 |
| --- | --- | --- |
| 不加密 | `JceSecurityProvider` | 走平台 JCE，更快 |
| 加密 | `BCSecurityProvider` | 实测平台 JCE **拿不到 AES-CCM**（AEAD 接口也拿不到），只有 BC 能满足 SMB3 |

**缓冲不要"统一"**：读缓冲与 SO 超时在不同场景需要不同值，
现在统一引用 `SmbSessionPool` 的常量只是为了消除魔法数字，不代表值应该相同。

---

## 4. 回环 HTTP 服务的错误处理（踩过坑，改之前先读）

`LoopbackStreamServer` 最难的不是实现，是**失败时必须让客户端收到响应**。

**核心规则：连接被关闭时如果一个字节都没写出去，客户端只会看到
`Unexpected end of file from server`，真实原因被完全吞掉。**

现在的三层兜底：

| 层 | 职责 |
| --- | --- |
| `serve()` 的 catch (`Throwable`) | 请求解析阶段失败 → 补发 502 |
| `handleRange()` 的 `committed` 标志 | 状态行**写出去之前**失败 → 补发 502；写出去之后失败 → 只能记日志 + 断连（状态行已用掉，没法再说什么） |
| `beginTransfer()` | 见下 |

⚠️ **catch 必须是 `Throwable` 而不是 `IOException`**：smbj 抛的是自己的异常类型，
用 `IOException` 接不住，会直接逃出去把连接关成零字节。

⚠️ **`beginTransfer()` 的计数器顺序不能改**（本轮刚修的 bug）：

```kotlin
// 正确：拿到源之后才自增
val source = warm ?: factory.open().also { warm = it }
activeTransfers++

// 错误（曾经这样写）：open() 抛异常时 activeTransfers 永不回退，
// session 永久停留在 busy 状态，sweep() 再也不回收它
activeTransfers++
warm ?: factory.open().also { warm = it }
```

---

## 5. `SmbPathResolver`（消除重复）

之前**同一段路径解析逻辑复制了 3 处**，改一处忘另两处是必然的坑。现在收敛到
`app/.../data/remote/smb/SmbPathResolver.kt`。

**语义关键（容易搞反）**：

- 连接**固定了 share** → 整个 path 都是**相对路径**
- 连接**没固定 share** → path 的**首段就是 share**

`validateShare` 参数的语义（这里我曾写过严重 bug，改之前务必确认理解）：

| 情况 | 含义 | 行为 |
| --- | --- | --- |
| `validateShare == null` | 调用方**没有枚举能力** | 照单全收 |
| lambda 返回 `String?` | 调用方**能枚举** | 返回 `null` 则抛 `IllegalArgumentException` |

⚠️ 曾经把「lambda 本身是 null」和「lambda 返回 null」混为一谈，
结果**播放路径（`validateShare = null`）会拒绝所有文件**——一个严重回归。
写这类"两种语义共用一个参数"的代码时，务必显式判空而不是用 `?.invoke() ?: throw`。

---

## 6. 图片链路（M5）

```
FileItem (mimeType = image/*)
        │
        ├─ 列表/网格缩略图 → RemoteImagePublisher.publish() → loopback URL → Coil
        │                    app/.../playback/RemoteImagePublisher.kt
        │                    app/.../ui/components/RemoteImageLoaderFactory.kt
        │
        └─ 点击 → ImageViewerActivity（全屏，可缩放翻页）
                 app/.../playback/ImageViewerActivity.kt
```

三个设计决定：

1. **图片不走 `getInputStream()`**。图片要整份取，绕回环后在设备端用 Coil/BitmapFactory 解码，
   避免"为了显示缩略图先建完整 HTTP 会话"。
2. **不注册自定义 Coil Fetcher**。远程图片已经是 `http://127.0.0.1` URL，用内建 fetcher 即可，
   自定义 fetcher 只会多一层出错可能。
3. **缓存键含 size + mtime**。文件被覆盖后不能还显示旧图。

⚠️ **`RemoteImagePublisher.supports()` 的过滤条件**（少一个就有 bug）：
非目录、`source == FileSource.SMB`、mime 以 `image/` 开头、且**排除 `image/svg+xml`**。

---

## 7. 数据流之外的两个横切关注点

**Room 数据库目前只有 2 张表**：`remote_connections`、`bookmarks`。
M5/M7 都**没有**引入新表——播放进度记忆（M4）如果要做，需要自己建表。

**凭据存在 Keystore**（上游能力），加密的。但注意：**密码不能进内存里的长生命周期结构**，
这正是 `SmbSessionPool` 只在握手时接收密码的原因（见 §3 规则 2）。

---

## 8. 想加新协议时的检查清单

如果你要给它加流式播放或图片浏览：

- [ ] 有连接池吗？没有的话参考 `SessionReuseCache` 建一个，否则并发会打爆连接
- [ ] 有安全的随机读吗？需要 `RandomAccessSource` 实现，且**跨 Range 保持句柄**
- [ ] 枚举 share 的能力有多强？决定 `validateShare` 传 `null` 还是传 lambda
- [ ] mimeType 判断够不够全？（`gif`/`bmp` 曾漏掉，导致永远进不了查看器）
- [ ] 有没有和现有机制重复？（WebDAV 已有 `content://` 路径，别再造一套）
