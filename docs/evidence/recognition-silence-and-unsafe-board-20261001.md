# 真机日志取证：识别静默 81 秒与非法棋面空转（2026-10-01 晚）

## 取证对象
- 设备文件：`/data/user/0/com.xiangqi.assist/files/logs/assist.log`
- 版本：1,467,840 字节，最后写入 `2026-10-01T18:44:27+08:00`
- 单会话：`SESSION|generation=1|reason=one_tap_prepare` → `SESSION_END|reason=one_tap_close`
- 时长：`1790850782892` → `1790851467696`，约 **11 分 25 秒**
- 本次确认安装的是"提速/提精度"版：日志中 `VISION_RESULT ... stable=3`，且出现合法着法快通道的 `stable=2` / `stable=1`

## 统计（全会话）
| 事件 | 次数 |
|---|---|
| `BOARD_CONFIRMED` 棋面确认 | **88** |
| `VISION_REJECT` | 40+（其中 38 条挤在最后 11 秒的同一个环里） |
| `VISION_CONFLICT_TOLERATED` | 3 |
| `VISION_THRESHOLD_STEP` | 11 |
| `PIPELINE_WATCHDOG_ACTION` | 8（对照上一版的 215） |
| `WATCHDOG_ACTION action=RESET_GRID` | 末尾约 8 次，每 10 秒一次 |
| `STREAM_QUIET_RELEASE` | **1** |
| `WATCHDOG_RESTART_ANALYSIS` | 25（13 秒内，每 400ms 一次） |

## 时延实测（本轮安装的版本）
- `BOARD_CONFIRMED` → `ANALYSIS_SCHEDULE` → `ANALYSIS_REQUEST`：**51 ms**
  （例：`1790851458030` → `1790851458081`；对照上一版固定 100–103 ms）
- 单次确认所需推理次数：**2 次**（对照上一版 3 次）
  例：首个稳定放行 `1790851457428` → 第二次 `1790851458029` → `BOARD_CONFIRMED` `1790851458030`，**602 ms**
- 单帧推理实测：`1790851457428 infer=506ms`、`1790851458029 infer=462ms`（其余抽样 288–690ms）
- `BOARD_CONFIRMED` → 落子派发：例 `1790851362277` → `MOVE_DISPATCH` `1790851362762`，**485 ms**

## 根因一：静止画面下识别会永久静默（81 秒零 `VISION_RAW`）
- 现象：`1790851365678` 最后一次 `VISION_RAW`（`stable=3`）之后，到 `1790851457428` 之间
  **81 秒没有一条 `VISION_RAW`**；同期 `framesSinceRebuild` 从 28450 涨到 37165（帧还在进来），
  看门狗每 10 秒报一次 `RESET_GRID / 长时间未识别到棋盘`。
- 代码路径：`StableFrameWindow.releaseWhenQuiet` 开头有 `if (releasedForStableRun) return`。
  `releasedForStableRun` 只在 `accept()` 遇到不稳定样本、或调用方 `rearm()` 时清零；
  而 `ScreenAssistService` 的 `BoardTracker.Event.SAME_BOARD` 分支**只调用
  `resetVisionRecoveryAfterAccepted()`，从不 `rearm()`**。
  → 一次成功处理之后闸门永久关闭；屏幕静止时既没有新样本去清它，静默放行也被它挡掉。
- 后果不止"没识别"：看门狗误判成取帧故障，每 10 秒执行一次 `RESET_GRID`，
  把已经对好的棋盘网格锚点丢掉，下一次识别退化成整屏重找
  （`1790851457428` 的 `anchor=BOARD_BOX`、`infer=506ms`，而健康状态是
  `anchor=PREVIOUS_GRID`、约 300 ms）——**直接拖慢速度并拉低准确率**。

## 根因二：非法的已采纳棋面让状态机永久停在"计算中"
- 现象：`1790851318675` `BOARD_CONFIRMED` 得到局面
  `C2a2b1r/4ak3/1R7/p5N2/8p/9/P1P1p2cP/7C1/4A4/2B1KAB2 w`；
  紧接着 `ANALYSIS_SCHEDULE` 之后**没有 `ANALYSIS_REQUEST`**，而是
  `VISION_REJECT|reason=非当前走子方王被将军（局面识别有误）`；
  随后 13 秒内 `WATCHDOG_RESTART_ANALYSIS` 刷 25 次，每次都跟着 `ANALYSIS_SCHEDULE`，
  仍然一条 `ANALYSIS_REQUEST` 都没有。
- 代码路径：`scheduleAnalysis` 的送引擎自检
  `AssistBoard.engineUnsafeReason(canonical, redGo)` 失败后只做
  `notifyBoardProblem(unsafe)` 然后 `return`。
  `notifyBoardProblem` 只记一次拒绝、踢一帧、叫醒看门狗，
  **不清掉那份已被采纳的棋面**；局面和轮次都不变，下一次分析又被同一道自检挡回。
  阶段表里 `THINKING` 是兜底出口，于是状态机就卡在"计算中"，只能等真实棋面变化才脱身。
- 佐证：同一时段 `VISION_RAW` 一直有输出（`pieces=20/21`），说明取帧和模型都正常，
  卡住的是"错误棋面被反复拿去送引擎"这一环。

## 修复
1. `StableFrameWindow`：新增**饿死放行**（`starvationReleaseMs = 2500`）。
   - 距离上次放行超过 2.5 秒仍无新放行时，即使闸门关着、即使稳定窗口一直不闭合，
     也强制放行窗口里最新样本一次；
   - 闸门关着时的静默巡检按 2.5 秒限速（静止局面约 0.4 次/秒推理），
     闸门开着时的重试仍按 500ms，不拖慢正常路径；
   - 放行只是"送模型看一眼"，双王、棋面结构、非法位置、合法着法、多帧确认等安全门全部保留。
2. `ScreenAssistService.abandonUnsafeBoard`：送引擎自检失败时**整体作废该棋面**
   （`tracker.reset`、清 `currentFen/currentCanonical/currentPieces/adviceBoard`、
   作废分析周期），退回识盘重新识别；连续 2 次作废时额外丢弃裁剪锚点回整屏重找。
3. `ThinkingRestartPolicy`（新增，纯 JVM 可测）：兜底"计算中"的处置改为
   **限速 1.5 秒重试 → 最多 3 次 → 用尽后作废局面回识盘**，不再每 400ms 空转。
   `AssistPhase.THINKING_STALL_TIMEOUT_MS` 改为引用该策略，避免两处常量分叉。

## 验证
- 定向测试通过：`StableFrameWindowTest`（新增 3 项）、`ThinkingRestartPolicyTest`（新增 5 项）、
  `AssistPhaseTest`、`AssistCoreTest`、`PipelineHealthPolicyTest`、`HeartbeatPolicyTest`、
  `FrameStabilityPolicyTest`、`PieceIdentityPolicyTest`、`DetectionConflictTolerancePolicyTest`、
  `AssistUpgradeTest`。
- 全量单测 505 项，8 项失败，全部为本机缺 Robolectric 图形原生库
  （`conscrypt_openjdk_jni-linux_aarch_64`）的环境问题，无逻辑失败。
- 双变体 Release 构建成功，证书 `f4dbd277…` 与正式版一致。

## 真机未验证项
- 静止局面下是否稳定出现周期性复核（预期 `VISION_RAW` 不再出现 10 秒以上空档）；
- `RESET_GRID` 是否不再因"没有变化"而误触发；
- 出现非法棋面时是否打出 `BOARD_ABANDONED_UNSAFE` 并退回 `FINDING`；
- 兜底"计算中"是否最多重试 3 次后转为作废局面（`WATCHDOG_RESTART_ANALYSIS_GIVEUP`）；
- 改动后确认延迟与准确率是否仍符合预期，且功耗没有回升。
