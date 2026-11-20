# 署名与来源声明

## 上游项目

本工程 **NasLink** 派生自 **[Voyager](https://github.com/AlanHuang99/Voyager)**（原作者 AlanHuang99），
基于其 `master` 分支派生。

- 上游许可协议：**GNU General Public License v3.0**（见本目录 `LICENSE`，内容未作任何修改）
- 依据 GPL-3.0 第 5 条，本派生作品同样以 **GPL-3.0** 发布

## 已做的修改

- 包名 `com.voyagerfiles` → `com.naslink`，`namespace` / `applicationId` 同步更改
- 应用显示名改为 `NasLink`
- 版本号重置为 `versionCode 1` / `versionName 0.1.0`
- 移除上游 CI 配置（`.github`）；保留 `fastlane` 与 `metadata` 素材
- Gradle wrapper 从 8.11.1 提升到 8.14.3（8.11.1 分发包在本机网络下不可达）
- **新增** `:stream` 模块（`com.naslink.stream`）：回环 HTTP 流媒体服务、`RandomAccessSource` 抽象、
  smbj 随机读实现、token 会话表
- **新增** `com.naslink.playback`：`StreamPlaybackCoordinator`、`StreamPlayerActivity`（Media3）
- **修改** `ui/screens/BrowserScreen.kt` 与 `viewmodel/FileBrowserViewModel.kt`：
  SMB 音视频文件点击改为在线播放，新增 `currentRemoteConnection()`
- 新增字符串资源 `stream_no_connection` / `stream_start_failed`

### 2026-10-11 接手轮次的增量（详见 `docs/HANDOVER/`）

- **修复** `SmbFileProvider` 身份判定：pinned-share 连接的 `/`（共享根）此前被视为
  无身份路径，导致同共享两种浏览形态互相认不出对方
- **修复** `SmbFileProvider` 会话自愈：缓存的 `DiskShare` 随底层传输死亡后，操作现在
  会丢弃缓存并重连一次；存在性/目录判定兼容 `STATUS_NO_SUCH_FILE` 类服务器
- **修复** `ImageViewerActivity`：`AsyncImage` 收到未 `build()` 的 `ImageRequest`，
  全屏查看图片必崩
- **新增** 播放进度记忆（Room v4 `watch_progress` 表 + `WatchProgressRepository`）
- **新增** 外挂字幕（同目录 srt/ass/ssa/vtt 自动挂载并自动选轨）
- **新增** 播放倍速控制（0.5x–2x，偏好持久化）与"用其他播放器打开"（回环 URL 外抛）
- **新增** 批量重命名（`##` 序号模板 + 预览）与 `RenameGuard` 重命名覆盖守卫
  （SMB/SFTP/FTP/WebDAV/SAF 五个 provider 此前会静默覆盖同名目标）
- **新增** 测试基础设施：`tools/smbtest/make_image_fixture.py`、
  `ImageViewerActivityTest`（2 项）、`BatchRenameTest`、`RenameGuardTest`、
  `SubtitleSidecarTest`、`StreamPlaybackCoordinatorMediaKeyTest`；
  POSIX/symlink 专属单测在 Windows 上以能力探针诚实跳过（`HostCapabilities`）

## 保留声明

上游源文件中的版权头与 GPL 声明全部保留，未作删改。

## 其他参考实现（未直接复制代码，仅参考设计思路）

- [LocalViewer](https://github.com/zmz125000/LocalViewer)（GPL-3.0）—— SMB 会话连接池、JCE AES-CMAC 签名加速、
  回环 HTTP 流媒体代理的设计思路
- [SambaLite](https://github.com/egdels/SambaLite)（Apache-2.0）—— 传输队列与文件夹同步的交互设计
- [hierynomus/smbj](https://github.com/hierynomus/smbj)（Apache-2.0）—— SMB2/SMB3 协议实现
