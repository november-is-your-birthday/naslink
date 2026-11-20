# NasLink

Android 局域网 NAS 客户端。在一个应用里完成**文件管理**、**在线视频/音频播放**、**图片浏览**——视频与图片通过网络流式读取，无需先下载到设备。

派生自 [Voyager](https://github.com/AlanHuang99/Voyager)（GPL-3.0），在其本地/SMB 文件管理基础上扩展了多协议支持与流媒体播放能力。

---

## 功能特性

### 文件管理
- **多协议远程连接**：SMB、SFTP、FTP、WebDAV；SMB 支持共享发现（smbj-rpc）
- **凭据安全**：远程密码使用设备绑定的 Android Keystore 加密；保存的连接与 SSH 密钥排除在 Android 备份与设备迁移之外
- **完整文件操作**：复制 / 移动 / 重命名 / 删除，跨协议协调，冲突处理，可取消的上传下载（前台服务）
- **压缩包**：ZIP / TAR / GZ / BZ2 创建与解压
- **批量重命名**：`##` 序号模板，带同名覆盖保护（SMB/SFTP/FTP/WebDAV/SAF）
- **本地存储**：分类索引、书签、回收站、重复文件扫描
- **SSH 密钥**：支持私钥导入与密钥对生成（含 post-quantum 混合密钥交换测试）

### 在线播放（不落盘）
- 点击 SMB 目录中的 mkv / mp4 / mp3 等文件即直接在线播放
- 进度记忆、外挂字幕（srt / ass / ssa / vtt）、倍速播放
- 可外抛给其他播放器继续观看
- SFTP / FTP / WebDAV 播放支持

### 图片浏览
- 全屏查看器：手势缩放、翻页、邻页预取
- SMB 列表缩略图 + 磁盘缓存

### 隐私
- 无任何分析统计或追踪；本地浏览无需联网权限，网络连接均由用户发起

## 安全设计

流媒体通过绑定 `127.0.0.1` 的回环 HTTP 服务实现（`:stream` 模块）：

```
SMB 文件 → RandomAccessSource（句柄保活、断线重连）
        → StreamSessionStore（256bit 随机 token，10 分钟空闲回收）
        → LoopbackStreamServer（支持 Range 206）
        → Media3 播放器
```

播放地址形如 `http://127.0.0.1:<随机端口>/s/<token>/<文件名>`，URL 中不含主机、共享名、路径或凭据；非回环来源的请求一律 403。

## 技术栈

```
Gradle 8.14.3  ·  AGP 8.7.3  ·  Kotlin 2.1.0  ·  JDK 17
compileSdk 35  ·  minSdk 26 (Android 8.0)  ·  targetSdk 35
Jetpack Compose + Material 3  ·  Room 2.6.1  ·  DataStore 1.1.1  ·  Coil 2.7.0
Media3 (ExoPlayer) 1.5.1
smbj 0.13.0 + dcerpc 0.12.13  ·  bcprov-jdk18on 1.85  ·  JSch (SFTP)
```

## 从源码构建

要求：**JDK 17**、Android SDK（compileSdk 35）、Gradle wrapper 已随仓库提供。

```bash
export JAVA_HOME=<jdk17 路径>
export ANDROID_HOME=<Android SDK 路径>

./gradlew assembleDebug          # 调试包（applicationId: com.naslink.debug）
./gradlew testDebugUnitTest      # JVM 单元测试
./gradlew connectedDebugAndroidTest   # 设备/模拟器测试
./gradlew lintDebug              # 静态检查
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> 已知事项：smbj-rpc 内部引用 `java.rmi.UnmarshalException`（Android 无此类），release 构建的 proguard 规则需 `-dontwarn`（`app/proguard-rules.pro` 已包含）。

## 许可证与致谢

- 本项目以 [GPL-3.0](LICENSE) 发布
- 上游项目：[Voyager](https://github.com/AlanHuang99/Voyager)（原作者 AlanHuang99），依据 GPL-3.0 第 5 条派生
- 来源与修改声明详见 [ATTRIBUTION.md](ATTRIBUTION.md)；上游原始文档保留在 [README_UPSTREAM.md](README_UPSTREAM.md) 与 `docs_upstream/`
- 第三方组件署名见 [ATTRIBUTION.md](ATTRIBUTION.md)（smbj 等）

## 免责声明

本项目按"现状"提供，不附带任何担保。使用本项目访问你的 NAS 与网络存储时，请自行确认网络环境与凭据安全。
