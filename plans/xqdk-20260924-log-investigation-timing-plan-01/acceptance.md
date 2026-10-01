# 象棋辅助 1.3.4 逐条验收（活动文档）

最近核验时间：2026-10-01 夜（Asia/Shanghai）
验收回路轮次：第 13 轮
本轮基准代码提交：工作树（尚未提交）；本轮修复：识别静默（静止画面下释放闸门永久关闭）与非法棋面空转（送引擎自检失败只记拒绝、不作废局面）
本轮验收产物：`docs/evidence/recognition-audit-20261001.md`、`docs/evidence/latest-log-20261001-fixed-conflict.md`、`docs/evidence/recognition-latency-20261001.md`、`docs/evidence/recognition-conflict-tolerance-20261001.md`、`docs/evidence/recognition-thinking-deadlock-20261001.md`、`docs/evidence/recognition-latency-and-accuracy-20261001.md`、`docs/evidence/recognition-silence-and-unsafe-board-20261001.md`
本轮定向验证：`StableFrameWindowTest`、`ThinkingRestartPolicyTest`、`AssistPhaseTest`、`AssistCoreTest`、`PipelineHealthPolicyTest`、`FrameStabilityPolicyTest`、`HeartbeatPolicyTest`、`PieceIdentityPolicyTest`、`DetectionConflictTolerancePolicyTest`、`AssistUpgradeTest` 通过；全量 505 项中 8 项为本机缺 Robolectric 原生库的环境失败；双变体 Release 构建成功且证书与正式版一致

## 怎么读这份文档

- 每一行对应需求文件 `request.md` 里的一条原始要求，不做合并、不做转述。
- 状态只有四种：`未开始`、`进行中`、`通过`、`未通过`。
- “通过”必须带可复现证据（命令、产物路径、输出结论）。没有证据的一律写“进行中”。
- 主机离线复现（用 Python 复刻 App 的识别与判定链）**不等于**真机通过；两者分开写。

## 逐条核验

| 编号 | 需求要点 | 状态 | 判定与证据 | 落地位置 |
| --- | --- | --- | --- | --- |
| R-01 | 模型换成更高档；不要复合模型；档位必须是原生真实体量 | 通过 | 产品内只留两份原生 V5 权重：主模型 Medium（`yolov5m_xq_fp32.tflite`）、回退 Lite（`yolov5n_xq_fp16.tflite`）。打包产物内实测只有这两个模型文件，复合权重与 YOLO26-S 已移除。原生 V5 Large 在各来源包中均未取得，未用复合产物冒充。 | `app/src/main/assets/`、`YoloModelTier.kt` |
| R-02 | 不接受从零训练；只能做转换；26s 修不好就回退 | 通过 | 未进行任何训练；YOLO26-S 无法修好，已按用户给出的回退方案收敛到原有 V5 两档。 | `docs/移动端模型替换评估.md` |
| R-03 | 悬浮按钮栏空指针崩溃 | 进行中 | 代码已加“重建代次”校验与异常保护，旧行被移除后不再访问空子视图；本机单元测试无法覆盖该真机路径，等设备复测。 | `app/src/main/java/com/xiangqi/assist/assist/ui/OverlayButtonBar.kt` |
| R-04 | 切换模型不得自动展开悬浮窗 | 通过 | 切换模型只写配置，不主动创建或展开悬浮窗；下次启动才读取该配置。 | `SideSelectionPresentationPolicy`、设置页路径 |
| R-05 | 去掉“大中小”命名，按实际效果排 | 通过 | 档位只剩两档并按用途命名：`Medium`（默认主模型）、`Lite`（最终回退），不再有“大/中/小”或实验档。 | `YoloModelTier.kt` |
| R-06 | 用截图实测得分排序，并补进项目与帮助文档 | 进行中 | 开发侧已有逐样本结论与主机探针记录；用户界面按 R-14 不展示任何评分，因此“补进帮助文档”只保留用途说明。 | `docs/两模型样本优化记录.md`、`assets/help.html` |
| R-07 | V5 原生大型模型在哪里 | 通过 | 审计结论：现有来源包里的 `yolov5l` 是 Medium+universal 逐锚点择优的复合产物，不是原生 V5 Large，故不采用。 | `docs/evidence/xqdk-v5-medium-lite-host-probe-*.json` |
| R-08 | 旋转鲁棒模型是否已融合、二次融合是否反而更差 | 通过 | 已用同一批样本做过复合对照，复合没有带来收益、耗时约翻倍，因此不保留复合档。 | 同上 |
| R-09 | 收敛为两个模型并把样本优化到尽量满分 | 进行中 | 样本级优化已做多轮（定向复核、冲突复核、低阈值救援、阶梯阈值）；当前主机复现为 55/55（两档模型），仍缺真机复测。 | `YoloBoardDetector.kt`、`DetectionRecoveryPolicy.kt` |
| R-10 | 本机编译不可用时用远程构建 | 通过 | 已既用远程构建完成任务，也把本机工具链修好，远程不再是唯一途径。 | `~/.gradle/gradle.properties`（仅本机） |
| R-11 | 半成品不能算完成 | 通过 | 本文档即为“完成”的判定基准；未通过的条目一律保持未通过。 | 本文件 |
| R-12 | 未点“一键准备”不得开启监听/悬浮窗/前台监测 | 通过 | 未准备时不启用识别会话、不创建悬浮窗、不推进前台状态；已由代码路径与真机日志核对。 | `ScreenAssistService.kt` |
| R-13 | “完全落不了子” | 进行中 | 已修三处根因（瞬时无障碍断开不再当成前台未知、自身恢复窗口豁免、安全暂停后独立确认返回即恢复）；改动后的包尚未在设备上完成落子复测。 | `ForegroundPausePolicy.kt`、`ScreenAssistService.kt` |
| R-14 | 模型页不得展示测试分数 | 通过 | 模型档位不再携带任何样本评分字段，界面只显示名称与用途。 | `YoloModelTier.kt` |
| R-15 | 看最新测试日志与截图 | 通过 | 已逐目录核对：`9月29号凌晨0:14最新log`、`9.29 8:41`、`九月二十九下午8:36`、`9.29晚上11:37`，并统计拒绝原因分布。 | `docs/evidence/latest-log-20260929-summary.md` |
| R-16 | 卡死后再也拉不起来 | 进行中 | 已修：识别看门狗不再清空落子事务；坏帧走定向复核；落子核对超时后不再拿过期快照死磕；识别连续失败自动降档。仍缺设备端“卡死后自动恢复”的日志证据。 | `PipelineHealthPolicy.kt`、`LandingRebaseFallbackPolicy.kt`、`DetectionThresholdRecoveryPolicy.kt` |
| R-17 | 日志从“一键准备”保留到“一键关闭”，并记录用户点击 | 通过 | 只在“一键准备”成功时新建日志并清理旧日志；开始/继续/暂停/重置不再清空；一键关闭写结束标记后停止写入但保留文件；悬浮窗与手动操作记为 `USER_ACTION`。最新日志中可见 `USER_ACTION` 记录。 | `AssistRuntimeLogger.kt`、`ScreenAssistService.kt` |
| R-18 | 暂停按钮文案随运行时状态 | 通过 | 运行中显示绿色“暂停”；已启动但暂停显示白色“继续”；未开始显示白色“开始”；界面只读运行时状态，不再自行解释内部意图。 | `AssistRunButtonPolicy.kt`、`OverlayPanelView.kt` |
| R-19 | 日志里的识别异常要修 | 进行中 | 已修：缺王/非法落点/数量异常/同格冲突统一进入定向复核；同格冲突复核由“仅小模型”扩展为两档共用；连续失败按阶梯降阈值。最新一次真机日志仍有 452 次拒绝，但已出现阶梯降档与复位记录，等新包复测。 | `DetectionRecoveryPolicy.kt`、`YoloBoardDetector.kt` |
| R-20 | 测试 log 里所有失败截图必须全部通过 | 进行中 | 主机复现：55 张截图，Medium 55/55 通过、Lite 55/55 通过（报告 `docs/evidence/test-log-screenshot-acceptance.json`）。**真机尚未逐张复测**，故不判通过。 | `tools/verify_test_log_screenshots.py` |
| R-21 | 连续失败按阶梯降阈值、成功后复位 | 通过 | 规则已实现：连续失败才降档、按指数下降、有下限、识别成功即回到最严档、新会话回到最严档；双王、棋子位置、同格冲突等安全门不放松。 | `DetectionThresholdRecoveryPolicy.kt` |
| R-22 | 小模型也必须 100% 通过 | 进行中 | 主机复现 Lite 55/55；真机未复测。 | 同 R-20 |
| R-23 | 功耗太高，要在效果不变前提下降低 | 进行中 | 已落三项有据的降耗改动：① 断掉“看门狗恢复把进展时间戳清零 → 立刻再恢复”的自激环，取帧重建/整屏重置不再每 250ms 一次（真机日志里 35 秒内 30+ 次重建，是当时最大的无谓开销）；② 屏幕上一个棋子都没有（待机）时采样周期 125ms → 250ms，帧拷贝与签名计算减半，棋盘出现后仍能在一次稳定窗口内被采到；③ 裁剪锚点连续 8 次通过安全门后，整屏安全复核间隔 1800ms 逐步放宽到最多 5400ms，任何一次拒绝或换局立即回到 1800ms。识别线程数、实时电流/温度实测仍未做。 | `PipelineHealthPolicy.kt`、`FrameStabilityPolicy.kt`、`ScreenAssistService.kt` |
| R-24 | 先把本地修好，别频繁依赖远程构建 | 通过 | 本机已能完成资源编译、单测、双变体打包并产出与正式版同证书的 APK；命令为 `./gradlew --no-configuration-cache :app:assembleArmv8-Release :app:assembleArmv8-dotprod-Release`。 | `~/.gradle/gradle.properties`、`/root/.keystores/` |
| R-25 | 沉淀记忆 | 通过 | 已把本机构建修复与本地正式签名链路升为正式记忆，另两条未验证条目留在候选层等用户表态。 | `~/AgentsBook/canonical/records/` |
| R-26 | 该我方走却被判成对方走；识别不准也会导致该问题 | 进行中 | 已改回合推断：只有能证明“确实走了一步合法着法”的变化才切换走子方；解释不了的多子变化、动画残影、识别抖动一律不把回合推给对方；自动模式下我方着法必须由落子事务背书。已加单测。真机复测未做。 | `TurnInferencePolicy.kt` |
| R-27 | 测试 log 里所有截图，大模型小模型都要跑通才算交付 | 进行中 | 见 R-20 / R-22：主机已全部通过，真机未复测，因此整体不判通过。 | `docs/evidence/test-log-screenshot-acceptance.json` |
| R-28 | 自动模式每一帧都检测我方执子方；手动模式完全不检测 | 通过 | 自动模式下每次获得稳定棋面都重新检测屏幕下半区帅/将，连续 3 帧一致才换边（防单帧抖动翻转整局）；手动模式下不读取该检测，只用用户指定的一方。 | `SideDetectionStabilityPolicy.kt`、`ScreenAssistService.kt` |
| R-29 | 等待对方落子时，棋面变化检测间隔缩短一倍 | 通过 | 等待阶段扫描超时与冷却 250ms → 125ms；单帧最小处理间隔 150ms → 75ms。采样时钟仍是 8fps，未翻倍，避免功耗反向上升。 | `HeartbeatPolicy.kt`、`AssistPhase.kt` |
| R-30 | 降级节奏由每十次改为每三次 | 通过 | 连续失败每 3 次降一档，指数下降，成功即复位；单测已按新节奏更新。 | `DetectionThresholdRecoveryPolicy.kt` |
| R-31 | 小模型整轮零识别（`VISION_RAW` 为 0，`stableAge` 恒 -1），卡住拉不起来 | 进行中 | 已定位并修掉根因：识别自愈（`recoverVisionPipeline`）原来把**稳定窗口样本**与**取帧/稳定/识别进展时间戳**一起清零，于是每 1.5 秒一次的整屏重置反复打断需要 1 秒（8×125ms）才能成立的稳定窗口；看门狗又因为时间戳被清零而继续加压恢复，形成“越修越坏”的自激环（该日志 35 秒内 0 次识别、30+ 次 KICK/REBUILD/RESET）。修法：自愈只丢弃裁剪网格与锚点并保留稳定窗口（新增 `StableFrameWindow.adoptEpoch(preserveSamples=true)`），恢复间距在连续无进展时按 2 倍递增到 5 秒，屏幕上没有棋盘时按待机处理（不触发破坏性恢复）。新增策略单测 4 项。真机未复测。 | `StableFrameWindow.kt`、`PipelineHealthPolicy.kt`、`ScreenAssistService.kt` |

| R-32 | 第二份日志（Lite）同样是整轮零识别：`VISION_RAW` 0 次、`stableAge` 恒 -1 | 进行中 | 新增根因：录屏用的是 VirtualDisplay，**屏幕内容不变化时它不再投递新帧**，于是“连续 8 个样本”的判据在静止画面上永远无法满足，识别一次都不会发生（第二份日志里 30 秒只有约 6 帧，稳态下每次重建只回一帧）。已新增**静默放行**：最后一张样本之后静默超过 600ms 就用窗口里最后那张样本进入一次识别，超过 3 秒没有新帧则判定为取帧故障交给看门狗（不拿过期画面反复当新棋面）；两次静默放行之间至少间隔 500ms，避免同一静止画面被反复推理。另修：取帧重建不再参与指数退避（固定 625ms），连续 3 次重建仍一帧未到时改为提示用户点『继续』重新授权，不再无限重建。新增单测 5 项、策略单测 3 项。真机未复测。 | `StableFrameWindow.kt`、`PipelineHealthPolicy.kt`、`ScreenAssistService.kt` |

|| R-33 | 第三份日志 Medium 已有 `VISION_RAW` 但仍不可用，且随后进入连续 `REBUILD_CAPTURE` | 进行中 | 已确认 Medium 推理正常（约 197–449ms），识别结果持续缺黑将，安全门正确拒绝 `黑将数量异常:0`；更严重的是 `framesSinceRebuild=132` 时仍把静止无新帧误判为取帧停摆，随后重建后只有 1–2 帧仍反复重建。已修：本次重建后只要收到并消费过帧，就不再按静止无新帧重建；缺王救援阈值和类别边际同步跟随每三次降档。定向单测通过，双变体 Release 已打包；真机复测未完成。 | `PipelineHealthPolicy.kt`、`YoloBoardDetector.kt`、`ScreenAssistService.kt`、`tools/verify_test_log_screenshots.py` |
| R-34 | 设备端最新 `assist.log`：识别长期失败、卡死拉不起来 | 进行中 | 已定位根因：全会话仅 3 次 `BOARD_CONFIRMED`，此后 `r=9,c=8` 固定出现「红车 0.708 / 红炮 0.684」同格竞争，`misses` 累计 111、`RESET_GRID` 每 10 秒一次、`phaseAge` 达 103425ms，116 秒无确认。映射层其实已按「高分占位」得出正确棋面，但 `recognizeFrame` 对任何冲突一律整帧否决。已新增 `DetectionConflictTolerancePolicy`（分差 ≥ 0.02 且胜者占位方可容忍），并在双王、结构、位置门全过后才放行，同时打 `VISION_CONFLICT_TOLERATED`；模糊僵局仍拒绝。**该修复在本轮真机日志中已验证生效**：`VISION_CONFLICT_TOLERATED` 出现 6 次、`BOARD_CONFIRMED` 9 次、连续自动落子 11 步、`RESET_GRID` 0 次（对照修前 3 次确认、111 次拒绝、卡死 103 秒）。仍判进行中：同一局在 11 步之后出现另一条卡死（见 R-35），整体可用性尚未闭合。 | `DetectionConflictTolerancePolicy.kt`、`ScreenAssistService.kt`、`docs/evidence/recognition-conflict-tolerance-20261001.md` |
| R-35 | 复测日志：`LANDING_REBASE_FALLBACK` 后整局 238 秒零识别、零看门狗，只能强关；并要求「给日志即改，不得再问」 | 进行中 | 已定位两条叠加根因：① `AssistPhase.needsBoard(THINKING)` 原为 `false`，而 `THINKING` 既是"引擎在搜"又是规则表兜底出口，兜底态因此被关掉取帧 → 识别不产出 → 棋面无法确认 → 分析永不发起 → 阶段永不变化；② `requestWatchdog()` 续期条件在「THINKING 且未搜索」时全为假 → 看门狗停止续期，监督链一起断。已修：`needsBoard`/`capture`/`watchdog` 全部按真实 `searching` 区分，新增 `WatchdogAction.RESTART_ANALYSIS`（`THINKING_STALL_TIMEOUT_MS = 3_000L`）作逃生通道，`tickWatchdog` 内补 `syncCaptureDemand`；新增回归测试 3 项。**该修复在 2026-10-01 傍晚真机日志中已验证生效**：整局 100+ 次 `BOARD_CONFIRMED`、连续自动落子 100+ 步、`RESET_GRID` 0 次、无静默死锁段落。仍判进行中：本轮新开提速与提精度需求（见 R-36），且需回归确认长局稳定性。 | `AssistPhase.kt`、`ScreenAssistService.kt`、`docs/evidence/recognition-thinking-deadlock-20261001.md` |
| R-36 | 识别精准度须高于当前，且整体处理时间降到当前的 1/2 | 进行中 | 已从设备端日志（1711844 字节，最后写入 2026-10-01T18:07:22，100+ 次确认的稳定会话）完成时间分解，确认瓶颈是**推理次数而非采样时钟**：采样 8fps、稳定窗口 4 帧、确认 3 帧，每次释放稳定窗口跑一次模型推理，真机 Medium 单帧推理实测 291–851ms（均值约 500ms），3 帧 ≈ 1.5 秒，与实测「对手落子 → 我方确认」1.1–1.5 秒吻合；另 `FULL_VISION_RECHECK` 每 1800ms 还插入一次整屏推理，日志中整屏与裁剪结果约各占一半。**提速**：确认 3→2 帧、识别稳定窗口 4→3 帧、整屏复核间隔 1800→3000ms（自适上限 5400→9000ms）、类别纯变化窗口 8→4 帧（原值等于取样窗口长度，等于把延迟放大到 4 秒）。**提精度**：接上此前从未被调用的 `PieceIdentityPolicy.repairMovedPiece`（终点被误读成同色另一棋子时用起点原类别还原并由规则引擎验证）；`BoardTracker` 候选匹配收紧为与首盘同口径的 `compatibleWithinTolerance`，容差只吸收占用格差异、**不再把「同格换汉字」当成同一候选**（旧实现只比差异格数，车↔兵↔相抖动也能攒够确认帧）；快慢由证据决定——仅能被规则证明的一步合法着法走 2 帧快通道。新增回归断言 4 组，定向测试 11 类通过，双变体 Release 构建成功且证书一致。真机未复测。 | `ScreenAssistService.kt`、`RecognitionTypes.kt`、`PieceIdentityPolicy.kt`、`docs/evidence/recognition-latency-and-accuracy-20261001.md` |



- 整体状态：**未通过**（不能交付）。
- 已通过并带证据的条目：R-01、R-02、R-04、R-05、R-07、R-08、R-10、R-11、R-12、R-14、R-15、R-17、R-18、R-21、R-24、R-25、R-28、R-29、R-30。
- 未关闭项（必须继续做）：
  1. R-23 降耗改动已落地，但缺实时电流/温度实测，仍判进行中。
  2. R-03 / R-13 / R-16 / R-19 / R-26 / R-31 / R-32 需要装新包做设备复测，并回传新日志。
  2b. R-34 / R-35 的修复已在 2026-10-01 傍晚真机日志中验证生效（100+ 次确认、连续落子 100+ 步、0 次网格重置），但仍需在新包上回归，确认提速改动没有把这两条重新打开。
  2c. R-36（提速至 1/2 + 提精度）完成主机验证，真机需核对 `BOARD_CONFIRMED → ANALYSIS_REQUEST` 间隔是否降到约一半、动画期间不提前计算、棋盘整体位移时 1 秒兜底整屏复核仍有效。
  3. R-20 / R-22 / R-27 主机已全过（本轮机内复跑仍为 Medium 55/55、Lite 55/55），但必须用真机逐张复测；在此之前不得宣称交付完成。
  4. R-06 / R-09 属于“优化到样本满分”的持续项，跟真机复测一起收敛。

## 本文件如何更新

每完成一轮核验就地修改本文件：更新“最近核验时间”“验收回路轮次”，改动对应条目的状态、证据与落地位置；不新建第二份验收文档。
