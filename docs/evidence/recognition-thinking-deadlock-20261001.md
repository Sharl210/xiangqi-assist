# 兜底「计算中」死锁：真机日志取证与修复（2026-10-01）

## 一、取证来源

- 设备文件：`/data/user/0/com.xiangqi.assist/files/logs/assist.log`
- 该次复测写入量：241,716 字节，最后写入 `2026-10-01T16:50:43+08:00`
- 会话：`一键准备 → 开始 → 一键关闭`，共约 5 分 41 秒
- 读取方式：MT 文件通道分页读取 + 定向检索（该路径不在工作区映射内，PRoot 侧不可见）

## 二、上一轮「同格冲突容忍」修复的真机效果：生效

| 事件 | 上一份日志（修前） | 本份日志（修后） |
| --- | --- | --- |
| `BOARD_CONFIRMED` | 3 次（全在开头 3 秒内） | 9 次，贯穿整局 |
| `VISION_REJECT` | 111 次仍在增长 | 7 次，有上限 |
| `VISION_CONFLICT_TOLERATED` | 未出现 | 6 次 |
| 连续自动落子 | 0 | 11 步（`MOVE_DISPATCH`） |
| `WATCHDOG_ACTION RESET_GRID` | 每 10 秒一次，卡在 FINDING 103 秒 | 0 次 |

`VISION_CONFLICT_TOLERATED` 实际命中记录：

```text
1790844346411|VISION_CONFLICT_TOLERATED|(r=9,c=8)5/6 gap=0.177
1790844368662|VISION_CONFLICT_TOLERATED|(r=9,c=8)5/6 gap=0.143
1790844389926|VISION_CONFLICT_TOLERATED|(r=9,c=1)12/10 gap=0.054
1790844395878|VISION_CONFLICT_TOLERATED|(r=9,c=1)12/10 gap=0.116
1790844397605|VISION_CONFLICT_TOLERATED|(r=9,c=1)12/10 gap=0.025
1790844398298|VISION_CONFLICT_TOLERATED|(r=9,c=1)12/10 gap=0.187
```

结论：同格类别冲突不再一票否决整帧，识别、计算、自动落子主链已跑通。

## 三、本份日志暴露的新故障：兜底「计算中」永久卡死

### 现象

从 `1790844405466`（最后一次识别）到 `1790844643656`（一键关闭），**约 238 秒**内：

- `VISION_RAW`：0 次
- `AUTO_TICK` / `PHASE` 变化：0 次
- 看门狗动作：0 次（连一条 `WATCHDOG_ACTION` 都没有）
- 取帧重建：0 次
- 只有 `FOREGROUND_OBSERVATION` 每约 1 秒刷一条

最后一条与识别有关的记录：

```text
1790844405466|VISION_REJECT|reason=识别疑似漏子：当前少识别 3 子，拒绝当前盘面 epoch=4 phase=THINKING misses=3 level=1
1790844405466|VISION_THRESHOLD_STEP|misses=3 level=1 conf=0.324 margin=0.036 fullScan=true
1790844405466|STATUS|正在确认棋面…
```

进入该状态前的关键序列：

```text
1790844404484|LANDING_REBASE_FALLBACK|streak=3 accepted=true
1790844404538|PHASE|FINDING->THINKING fen=2bakab2/… w - - 0 1 searching=false settled=false
```

### 根因（已定位到代码行）

1. `AssistPhase.RULES` 的最后一行 `search-starting` 是**兜底出口**：
   「轮到我走 + 引擎没在搜索 + 着法没定」→ `THINKING`。
   所以 `THINKING` 同时代表两种局面：真的在搜索，和兜底干等。

2. `AssistPhase.needsBoard(THINKING)` 原实现返回 `false` → `capture()` 返回 `OFF`
   → **一进入兜底 THINKING 就停止取帧与识别**。

3. `requestWatchdog()` 的续期条件是
   `needsBoard(p) || searching || (engineStarted && !engineReady)`，
   三条在「THINKING 且未搜索」时全为假 → **看门狗也停止续期**。

4. 于是形成闭环，无任何一方能打破：

```text
兜底 THINKING → 不取帧 → 识别不产出 → 棋面无法确认
             → 分析永远不会被发起 → 阶段永远停在 THINKING
             （看门狗已停 → 连自动恢复都没有）
```

这解释了日志里 238 秒「什么都没有」的现象：不是恢复失败，而是**没有任何监督在运行**。

### 修复

| 文件 | 改动 |
| --- | --- |
| `AssistPhase.kt` | `needsBoard(phase, engineSearching)`：`THINKING` 按是否真搜索区分——真搜索不读盘，兜底态必须读盘 |
| `AssistPhase.kt` | `capture(phase, autoMode, engineSearching)`：兜底态自动模式 `CONTINUOUS`、半自动 `ON_DEMAND` |
| `AssistPhase.kt` | 新增 `WatchdogAction.RESTART_ANALYSIS` 与 `THINKING_STALL_TIMEOUT_MS = 3_000L`：兜底态干等超时即作废该局面分析周期并重新发起 |
| `AssistPhase.kt` | `watchdog(..., engineSearching)`：真搜索中不误判为干等、也不因画面陈旧重置网格 |
| `ScreenAssistService.kt` | `desiredCaptureDemand` / `capturePump` / `tickWatchdog` / `requestWatchdog` 全部改为传入真实 `searching`，消除"口径不一致"这一整类隐患 |
| `ScreenAssistService.kt` | `tickWatchdog` 内新增 `syncCaptureDemand(p)`：保证取帧档位不再停留在旧阶段 |
| `ScreenAssistService.kt` | 新增 `RESTART_ANALYSIS` 处置：`analysisCycleGate.invalidate()` → 重新发起分析 → 触发自动驱动，并打 `WATCHDOG_RESTART_ANALYSIS` 便于真机核对 |

安全边界未放宽：不放宽双王、棋面结构、非法位置、稳定确认、落子核对与自动落子安全门。

## 四、验证

- 定向测试通过：`AssistPhaseTest`、`AssistCoreTest`、`PipelineHealthPolicyTest`、`FrameStabilityPolicyTest`、`HeartbeatPolicyTest`。
- 新增回归测试 3 项：
  - 兜底 `THINKING` 必须读盘（否则死锁）；
  - 兜底干等超时必须被 `RESTART_ANALYSIS` 释放，且刚进入时不得抢跑；
  - 真搜索再久也不得被判成干等、不得因画面陈旧重置网格。
- 全量单测：493 项，8 项失败，全部为本机缺 Robolectric 原生库
  `conscrypt_openjdk_jni-linux-aarch_64` 的环境问题（`AssistLaunchRobolectricTest` 2 项、
  `OverlayChessViewRenderTest` 6 项），与本轮改动无关。
- 双变体 Release 构建成功，签名证书 SHA-256 `f4dbd277973ca30798b84109735c2bc976afd177f87d361bc95574e781372dee`（与正式版一致）。

## 五、真机待验证项（本轮不可宣称完成）

1. 进入兜底 `THINKING` 后，日志应继续出现 `VISION_RAW`，不再出现长时间静默。
2. 若确实干等，应出现 `WATCHDOG_RESTART_ANALYSIS` 且随后引擎真的开始计算。
3. 真搜索（长考）期间不应出现 `WATCHDOG_RESTART_ANALYSIS`。
4. 状态行不应在长时间搜索时出现「未取帧」「画面已中断」这类误导文案。
5. Lite 模型、其他皮肤与全部失败截图的真机复测仍未完成。

## 六、残余风险（如实记录）

- `RESTART_ANALYSIS` 若长期无法让引擎开始搜索，会以 3 秒周期反复重发，而不是升级到更重的处置；
  当前判断是"先保证一定能爬出来"，是否需要在多次重发后上报用户仍待真机数据决定。
- 本轮修复的是**兜底态被关掉取帧**这一条死锁；若还有别的路径能让阶段停住而无人监督，
  需要真机日志继续暴露。
