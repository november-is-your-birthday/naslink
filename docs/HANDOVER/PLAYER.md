# 视频播放器（2026-10-21 重写）

接管前先读这一页：播放器不再是 `PlayerView` 自带控制器 + 两个角落小按钮，
而是一层自绘的 Compose 控制面。改动集中在三个文件，边界清楚。

## 文件分工

| 文件 | 职责 |
| --- | --- |
| `playback/StreamPlayerActivity.kt` | Activity、`ExoPlayer` 生命周期、全部播放状态、菜单组装、`PlayerWindowController`（亮度/音量/方向/沉浸式/画中画） |
| `playback/player/PlayerGestures.kt` | 手势状态机（单击/双击/长按/横拖/竖拖/双指缩放）+ 可单测的纯算术 `PlayerGestureMath` |
| `playback/player/PlayerOptions.kt` | 倍速档位、时间格式化、画面比例/方向/循环枚举（纯数据，便于单测） |
| `playback/player/PlayerControls.kt` | 顶栏、中央播放键、底部进度条与工具行、HUD、通用选择对话框 |
| `res/layout/view_stream_player.xml` | `PlayerView`，`surface_type="texture_view"`、`use_controller="false"` |

## 手势一览

| 手势 | 行为 |
| --- | --- |
| 单击 | 显示/隐藏控制条（锁定时显示/隐藏解锁按钮） |
| 双击左/右半屏 | ∓10 秒，中央 HUD 显示偏移与目标时间 |
| 长按（不移动） | 临时加速到 2x（当前已 ≥2x 则 3x），松手恢复；顶部显示加速标识 |
| 横向拖动 | 拖动预览定位，松手 seek；12 dp ≈ 1 秒 |
| 左侧竖向拖动 | 窗口亮度（只改本窗口，不写系统设置） |
| 右侧竖向拖动 | 媒体音量（`setStreamVolume` flag 0，不弹系统音量条） |
| 双指捏合 | 1x–4x 缩放；拖动画面在溢出范围内平移 |
| 双指以上 | 同上（取 `calculateZoom/calculatePan`） |

### 为什么只有一个手势检测器

`detectTapGestures` 与 `detectDragGestures` 都会 `consume()` 最初的 down 事件，
叠加使用时后注册的那个永远收不到完整手势——表现为"要么单击生效、要么拖动生效"。
因此 `detectPlayerGestures` 用 `awaitEachGesture` 独占一次手势，靠位移与方向在
`DragMode` 里做一次判定。长按必须靠超时（手指不动时没有 pointer 事件），
用的是 `AwaitPointerEventScope.withTimeoutOrNull`。

## 控制条

- 顶栏：关闭、标题 + 分辨率、外部播放器、更多（画中画 / 循环 / 定时 / 画面比例 / 自动旋转）
- 中央：后退 10 秒、播放/暂停、前进 10 秒
- 底部：剩余时间、可拖动进度条、工具行（倍速 / 音轨字幕 / 旋转 / 比例 / 锁定）
- 播放中 3.5 秒无操作自动隐藏；暂停、打开菜单、锁定时不隐藏
- 控制条隐藏即进入沉浸式（隐藏状态栏与导航栏）；锁定时只留解锁按钮

## 状态与生命周期

- 位置每 250 ms 轮询一次（ExoPlayer 没有位置回调），只有底部进度条读取该状态
- 每 5 秒 checkpoint 一次进度；结束播放则清除记录；`onDispose` 补一次
- 倍速通过 `PreferencesManager.playbackSpeed` 记忆，下次打开直接沿用
- 倍速持久化写在 `rememberCoroutineScope()` 里，屏幕销毁即取消

## 已知边界

1. **未在真机/模拟器上跑过**（按项目要求只做静态验证）。手势状态机与
   `TextureView` 缩放的组合是唯一需要真机确认的部分。
2. 选 `texture_view` 是为了让 Compose 的 `graphicsLayer` 能真正缩放画面；
   SurfaceView 在多数机型上不参与该变换。代价是功耗与 HDR 支持略逊。
3. 字幕只做轨道切换，没有做延迟微调；音频同理。
4. 亮度只作用于当前窗口，退出播放器即恢复系统亮度。
