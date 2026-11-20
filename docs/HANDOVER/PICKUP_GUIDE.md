# 接手指南：第一周该做什么

按优先级排序。每项都写清了「做完的标志」，避免"看起来做完了其实没验证"。

---

## 第 0 步（10 分钟）· 建立安全网

**先 `git init`**，理由见 `KNOWN_ISSUES.md` §3——本轮真的发生过一次改坏无法回滚的事故。

```bash
cd <项目根目录>
git init && git add -A && git commit -m "NasLink 交接基线"
```

⚠️ 提交前先确认 `local.properties`（含本机 SDK 路径）和 `.venv/` 在 `.gitignore` 里。

---

## 第 1 步（30 分钟）· 跑通测试，建立基线

**在动任何代码之前**，先知道自己接手的是个什么状态的工程。

```bash
JAVA_HOME=$HOME/.workbuddy/binaries/jdk/17/jdk-17.0.20.1+1 ./gradlew :stream:test :app:testDebugUnitTest
```

期望：`:stream` **74 项全绿**。若不是，先排查环境（JDK 版本、SDK 路径）。

然后把 `STATUS.md` 里的集成测试跑一遍（需起 SMB 服务端，命令见 `README.md` §4），
确认基线是「5 通过 2 失败」而不是别的样子。

---

## 第 2 步（30 分钟）· 在模拟器上亲眼看一次主链路

**这一步不能省。** 代码全绿不等于产品能用。

1. 起 impacket SMB 服务端，按 `README.md` §4 造好 mp4 夹具
2. 装 APK 到模拟器
3. 新建一个指向 `10.0.2.2:1445` 的 SMB 连接
4. 拖动进度条到文件**末尾**——验证 `moov` 在尾部的 MP4 能正常起播
   （这是整个项目最有价值的一条验证，能抓到"顺序读能播但 seek 就死"的经典 bug）

看懂了这条链路，再读 `ARCHITECTURE.md` §2。

---

## 第 3 步（1 小时）· 收尾 M5

把唯一的失败测试清掉。具体步骤和现成的 PNG 生成代码在 `KNOWN_ISSUES.md` §1。

**做完的标志**：
- [ ] `SmbStreamIntegrationTest` **7 项全通过**
- [ ] PNG 夹具生成**固化成脚本**（如 `tools/smbtest/make_image_fixture.py`），
      而不是留在某个人电脑的临时目录里
- [ ] 在模拟器上真的点开一张 SMB 里的图片，双指缩放、前后翻页都正常

⚠️ 如果造好夹具后**仍然 502**，那是真产品缺陷，
重点查 `SmbPathResolver.resolveFile`（见 `ARCHITECTURE.md` §5，那里的语义很容易搞反）。

---

## 第 4 步（半天）· 补 UI 层测试

目前 `ImageViewerActivity` 等界面**完全没有自动化覆盖**，这是接手后最容易出回归的地方。

至少给查看器补一条仪器化测试：打开 → 显示正确图片 → 翻页 → 关闭后 token 被释放。
（`tokens` 是实例字段而非 companion 静态，注意别测成静态状态。）

---

## 第 5 步（按兴趣选）· 挑一个里程碑

此时你对代码已经熟了，可以挑方向。**推荐顺序**：

### 选项 A：M8 发布准备（风险最低，见效最快）

项目已经能用了，缺的只是文档与分发：

- [ ] 更新 `README.md`（当前还写着「M1–M2 阶段」，见 `KNOWN_ISSUES.md` §5）
- [ ] 写路线图文档，明确 M6/M4 的取舍
- [ ] 核对 `metadata/` 的 20 条 changelog 与 `fastlane/metadata/android/en-US/`
- [ ] 配置签名密钥并出第一个 GitHub Release

**适合**：想尽快拿到一个能给别人装的东西。

### 选项 B：M6 整理能力（用户诉求里明确提过"整理"）

⚠️ **必须先做前置项**：

1. **先补 5 个 provider 的重名检查**（`KNOWN_ISSUES.md` §2）。
   批量重命名会把"静默覆盖一个文件"变成"静默覆盖一批文件"。
2. **先决定聚类的远程策略**（`KNOWN_ISSUES.md` §8）。
   SMB 没有通用哈希 API，这个取舍没人做过决定。
3. 打开多选工具栏的 RENAME 入口（现在只在 `isSingle` 时出现，`BrowserScreen.kt:1638-1658`）。
4. 照 `FileBrowserViewModel.deleteSelected`（`:670-721`）的范式写循环 +
   failed 计数 + `OperationMessages.partial` + `clearSelection` + `refreshFiles`。
5. ⚠️ 注意 `TransferOperationController.launch` 是**全局单任务**（`:35`），
   且 `operation_rename` **不在可取消白名单**里（`:1336`）。

**适合**：想啃有真实风险的活。

### 选项 C：M4 播放体验（四项增强）

四项**都没开始**：播放进度记忆、外挂字幕、倍速、外抛播放器。

其中**播放进度记忆的性价比最高**——不做的话看长片每次从头开始。
注意需要自己建 Room 表（目前只有 2 张表）。

⚠️ 别把 `FileUtils.kt` 里现成的 `ACTION_VIEW` 当成"外抛播放器"已完成，
那只是上游的「用外部应用打开文件」。

**适合**：想改善用户体验，且不太愿意碰协议层。

---

## 几条给你的提醒

1. **先读 `ARCHITECTURE.md` §4（回环服务的错误处理）和
   `KNOWN_ISSUES.md` §6（不能改坏的技术取舍），再动流式播放的代码。**
   这两处的 bug 都很隐蔽，改坏了表现为"偶发失败"，极难排查。

2. **这个项目最大的资产不是功能清单，是测试。**
   `:stream` 的 74 项单测 + 真实 SMB2 集成测试是前人留下的。
   改代码请保持测试全绿；如果某个 bug 很难测，
   那说明它是真 bug，值得为它写一个测试。

3. **GPL-3.0 是硬约束**（派生自 Voyager）。新增功能时同步更新 `ATTRIBUTION.md`。

4. **每修一个 bug，把它的成因补进 `KNOWN_ISSUES.md`。**
   下一位接手的人会感谢你——尤其是那些"看起来像 feature 需求、其实是 bug"的坑。
