# SMB 流式播放验证脚手架

用真实 SMB2 服务端替代 NAS，对 `SmbRandomAccessSource` + `LoopbackStreamServer`
做端到端验证：真实的 negotiate、认证、tree connect、query info 与分段读。

相比用字节数组当数据源，它能抓到只在真实协议下才暴露的问题。

## 前置

```bash
python -m venv .venv && .venv/Scripts/pip install impacket
```

## 启动服务端

```bash
python tools/smbtest/smb_test_server.py <共享根目录> 1445
```

- 服务端只讲 **SMB2/SMB3**，共享名固定为 `MEDIA`
- 使用**匿名认证**：impacket 0.13 计算 NTLMv2 时会把 `str` 传给
  `hmac.new(key=...)`，Python 3.13 直接拒绝。跳过 NTLM 不影响 SMB2 传输层验证。
- **端口 445 常被系统进程占用**（本机报 `WinError 10013`），请用 1445 这类高位端口

## 生成测试文件

```bash
python tools/smbtest/make_fixture.py <共享根目录>/media/probe.mp4 40
python tools/smbtest/make_image_fixture.py <共享根目录>/media/red.png 64
```

第一条产出 40 MB MP4，`moov` box **紧贴文件末尾**（最后 116 字节）——
与大量真实录制一致，因此读取元数据必须发起一次靠后的 Range 请求，
这正是朴素 SMB 流式播放最容易写错的场景。

第二条产出 64×64 真彩色 PNG，`SmbStreamIntegrationTest` 的两个图片用例
（`IMAGE_EDGE = 64`）要求它能被 `BitmapFactory` 真实解码。

**注意共享根目录的层级**：`smb_test_server.py` 把传入的 root **直接映射为共享**，
而测试请求的是共享内 `media\...` 路径——所以 root 必须是**含有 `media/`
子目录的那一层**，不是 `media` 本身。

## 已知环境限制（Windows 宿主机）

- **重命名无法端到端验证**：SMB2 协议要求文件在打开状态下发 SET_INFO 重命名，
  而 impacket 在 Windows 上用 Python 句柄持有文件（无 `FILE_SHARE_DELETE`），
  服务端内部的 `os.rename` 会因 Windows 文件锁失败 → 返回 `STATUS_ACCESS_DENIED`。
  Linux 宿主机上的 impacket 没有此问题。应用侧行为（守卫检查、目录判定、
  失败以 Result 返回）已通过 JVM 探针验证到 SET_INFO 一步。
- **状态码差异**：impacket 对"文件不存在"返回 `STATUS_NO_SUCH_FILE`，真实
  NAS 返回 `OBJECT_NAME_NOT_FOUND`。smbj 的 `fileExists/folderExists` 只认后者，
  因此 `SmbFileProvider` 统一走 `existsInShare`/`isDirectoryInShare` 兜底。

## 模拟器侧连宿主机

Android 模拟器用 `10.0.2.2` 指向宿主机。但本机启动的模拟器**路由表缺少默认路由**，
需补一条，否则出站全部 `Network is unreachable`：

```bash
adb shell su 0 ip route add default via 10.0.2.2 dev eth0
adb shell ping -c 2 10.0.2.2   # 验证
```

## 跑测试

```bash
JAVA_HOME=<jdk17> ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.naslink.playback.SmbStreamIntegrationTest \
  -Pandroid.testInstrumentationRunnerArguments.smbHost=10.0.2.2 \
  -Pandroid.testInstrumentationRunnerArguments.smbPort=1445
```

不传 `smbHost` 时全部用例 `assumeTrue` 跳过，可在 CI 安全运行。

## 覆盖内容

| 用例 | 验证点 |
|---|---|
| `readsFileSizeAndRangedBytesOverRealSmb` | `size`、offset 0 的 `ftyp`、靠后偏移的 `moov`、越界读夹取到文件末尾、读超出末尾返回 0 |
| `loopbackServerStreamsRealSmbBytesWithRanges` | HEAD 通告真实远端大小；两次独立 Range 均返回 206 与正确 `Content-Range` |
| `warmSourceServesIdenticalBytesAsDirectSmbReads` | **同一区间的 HTTP 字节与直连 SMB 字节逐字节一致** —— 防止传输静默损坏媒体 |