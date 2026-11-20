# NasLink 交接说明

> 生成时间：2026-10-10 · 接手人：你
>
> 本目录是**唯一的交接入口**。先读 `STATUS.md`（做了什么/没做什么），再读
> `ARCHITECTURE.md`（代码怎么串起来的），最后按 `PICKUP_GUIDE.md` 起步。
> 已知坑和"别改坏"的约束在 `KNOWN_ISSUES.md`。

---

## 1. 源代码在哪里

**项目根目录（整个仓库就是它）：**

```
<project-root>
```

接手时请**整目录拷走**，不要只拷子目录。下面这张表是目录职责：

| 路径 | 是什么 | 接手时能删吗 |
| --- | --- | --- |
| `app/` | 主应用模块（`:app`），全部 UI + 业务逻辑 | 不能 |
| `stream/` | 流式播放模块（`:stream`），无 Android 依赖，可单测 | 不能 |
| `tools/smbtest/` | 真实 SMB2 测试服务端 + 夹具生成脚本（Python） | 不能，删了集成测试就跑不了 |
| `scripts/` | Crowdin 翻译流水线脚本（Python） | 上游遗留，可暂不动 |
| `docs_upstream/`、`README_UPSTREAM.md` | 上游 Voyager 的原始文档 | 不能，GPL 要求的溯源 |
| `docs/AUDIT_M4.5.md` | 上一轮审计记录 | 参考用 |
| **`docs/HANDOVER/`** | **本交接文件夹** | 不能 |
| `fastlane/`、`metadata/` | F-Droid / GitHub 发布素材 | 不能（M8 要用） |
| `.venv/` | impacket 虚拟环境（Python） | 能，但重建麻烦，建议留 |
| `local.properties` | 本机 Android SDK 路径 | **换机器必须改** |

代码规模（不含 `build/`）：

| 目录 | 文件数 | 行数 |
| --- | --- | --- |
| `app/src/main` | 127 kt + 36 xml | ~27,500 |
| `app/src/test`（单测） | 77 kt | ~9,300 |
| `app/src/androidTest`（仪器化） | 61 kt | ~6,900 |
| `stream/src/main` | 6 kt | ~1,120 |
| `stream/src/test` | 3 kt | ~730 |

---

## 2. 这个项目是什么

**NasLink** = 局域网 SMB NAS 的 Android 客户端。核心主张是**视频和图片直接流式看，不落盘下载**。

### 关键：它是 GPL-3.0 派生工程，不是从零写的

派生自 **[Voyager](https://github.com/AlanHuang99/Voyager)**（原作者 AlanHuang99）。
所以：

- **必须保持 GPL-3.0**，不能改成闭源或非 GPL 协议
- 上游源文件的版权头与 GPL 声明**全部保留、不得删改**（GPL-3.0 §5）
- 已做的修改清单在 `ATTRIBUTION.md`，**你新增功能时要同步补这份文件**
- 上游原始文档在 `README_UPSTREAM.md` 与 `docs_upstream/`，别删

也就是说，"已完成的功能"里**绝大部分是上游带来的**（文件管理、上传下载、压缩包、SAF、SFTP、FTP、WebDAV 等）。我们这轮做的增量只有流式播放、图片浏览、SMB 性能层这几块。交接时别把上游的东西当成自己写的去邀功，也别把上游的东西当成新增功能重写。

---

## 3. 技术栈（照抄 README，需要时查 `app/build.gradle.kts`）

```
Gradle 8.14.3 · AGP 8.7.3 · Kotlin 2.1.0 · KSP 2.1.0-1.0.29
compileSdk 35 · minSdk 26 (Android 8.0) · targetSdk 35
applicationId com.naslink · versionCode 1 · versionName 0.1.0
smbj 0.13.0 (SMB2/SMB3) + com.rapid7.client:dcerpc 0.12.13 (共享枚举)
bcprov-jdk18on 1.85 · Room 2.6.1 · DataStore 1.1.1 · Coil 2.7.0
Compose + Material 3 · Navigation Compose 2.8.5 · Media3 (ExoPlayer) 1.5.1
```

---

## 4. 构建与测试命令（**本机有坑，务必照抄**）

### JDK 必须显式指定

```bash
JAVA_HOME=$HOME/.workbuddy/binaries/jdk/17/jdk-17.0.20.1+1 ./gradlew <任务>
```

本机默认 `JAVA_HOME` 指向 Android Studio 的 jbr（JDK 25），**Gradle 8.14.3 不支持 JDK 25**，
不显式指定就会报 `25.0.2` 之类的版本错误。这是本机最高频的构建失败原因。

### 单测（不需要设备）

```bash
JAVA_HOME=$HOME/.workbuddy/binaries/jdk/17/jdk-17.0.20.1+1 ./gradlew :stream:test :app:testDebugUnitTest
```

### 真实 SMB 集成测试（需要模拟器 + Python 服务端）

**这是本项目最有价值的测试**，别跳过——它用真实的 negotiate / 认证 / tree connect /
query info / 分段读，能抓到只在真实协议下才暴露的问题。

```bash
# ① 装依赖（只需一次）
python -m venv .venv && .venv/Scripts/pip install impacket

# ② 生成夹具（40MB mp4，moov 紧贴文件末尾——模拟真实录制）
python tools/smbtest/make_fixture.py <共享根>/media/probe.mp4 40

# ③ 起 SMB2 服务端（匿名认证；端口 445 常被占用，用 1445）
.venv/Scripts/python.exe tools/smbtest/smb_test_server.py <共享根>/media 1445

# ④ 模拟器补默认路由（本机模拟器路由表缺默认路由，否则出站 Network is unreachable）
adb shell su 0 ip route add default via 10.0.2.2 dev eth0

# ⑤ 跑测试
JAVA_HOME=$HOME/.workbuddy/binaries/jdk/17/jdk-17.0.20.1+1 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.naslink.playback.SmbStreamIntegrationTest \
  -Pandroid.testInstrumentationRunnerArguments.smbHost=10.0.2.2 \
  -Pandroid.testInstrumentationRunnerArguments.smbPort=1445
```

**不传 `smbHost` 时全部用例 `assumeTrue` 跳过**，所以 CI 里跑是安全的。
`tools/smbtest/README.md` 有完整说明。

⚠️ **图片集成测试需要额外的 `red.png` 夹具，且没有现成脚本生成**——
`make_fixture.py` 只产 mp4。这两个用例目前需要手工造 PNG，见 `KNOWN_ISSUES.md` 第 1 条。

---

## 5. 本目录各文件

| 文件 | 什么时候看 |
| --- | --- |
| `STATUS.md` | **先看这个**。M0–M8 逐项完成状态 |
| `ARCHITECTURE.md` | 要改流式播放/图片/连接池前必看 |
| `KNOWN_ISSUES.md` | **动手前必看**，含"别改坏"的隐含约束 |
| `PICKUP_GUIDE.md` | 按顺序列出第一天该做什么 |

外部文档：
- `../AUDIT_M4.5.md` — 上一轮深度审计记录
- `../../README.md` — 项目主页（**注意：其中的阶段描述已过期**，见 `KNOWN_ISSUES.md` 第 5 条）
- `../../ATTRIBUTION.md` — GPL 署名与修改清单
- `../../tools/smbtest/README.md` — SMB 测试脚手架详解
