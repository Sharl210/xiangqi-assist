# 真机日志摘要：Lite 档整轮零识别 + 看门狗自激恢复环（2026-09-29 23:03–23:07）

本文件是对用户粘贴的那份真机日志的**摘录与统计**，不是逐字原文（原文在用户消息中，未逐字复制入仓库）。
用途：固定本轮 R-31 / R-23 的现场证据。

## 会话骨架

```text
SESSION|generation=2|reason=one_tap_prepare
1790730229949|USER_ACTION|surface=activity action=一键准备
1790730235658|USER_ACTION|surface=overlay action=开始继续或暂停
1790730235658|STATUS|正在按已保存的Lite设置检查模型兼容性…
1790730235770|RUN_START|mode=3 auto=true
1790730263307|USER_ACTION|surface=overlay action=关闭按钮（等待二次确认）
1790730263825|USER_ACTION|surface=overlay action=一键关闭
SESSION_END|reason=one_tap_close
```

## 关键标记统计（整段会话）

| 标记 | 次数 | 说明 |
| --- | --- | --- |
| `VISION_RAW` | **0** | 整段会话一次模型推理结果都没有产出 |
| `stableAge=-1` | 全程 | 稳定窗口一次都没有成立 |
| `PIPELINE_WATCHDOG_ACTION` | 30+ | 其中 `KICK_CAPTURE` / `REBUILD_CAPTURE` / `RESET_VISION` 高频交替 |
| `PIPELINE_VISION_RESET` | 6 | 均为 `grid=false misses=0`：没有网格可清，也没有拒绝计数 |
| `CAPTURE_REBUILD` | 12 | `size=661x1440 reuseVirtualDisplay=true` |
| `FOREGROUND_OBSERVATION package=com.coloros.gallery3d` | 持续 | 会话是在相册应用里开始的（屏幕上没有棋盘） |

## 典型循环（连续片段）

```text
1790730243326|PIPELINE_WATCHDOG_ACTION|action=KICK_CAPTURE ... frameAge=453 processedAge=468 stableAge=-1 visionAge=-1 recognitionAge=-1
1790730243576|PIPELINE_WATCHDOG_ACTION|action=REBUILD_CAPTURE ... frameAge=703 processedAge=718 stableAge=-1 visionAge=-1
1790730243576|CAPTURE_REBUILD|size=661x1440 reuseVirtualDisplay=true
1790730243576|STATUS|识别画面已中断，已重新接回屏幕输出
1790730244065|FOREGROUND_OBSERVATION|package=com.coloros.gallery3d ...
1790730244082|PIPELINE_WATCHDOG_ACTION|action=KICK_CAPTURE ...
1790730244332|PIPELINE_WATCHDOG_ACTION|action=REBUILD_CAPTURE ...
```

## 根因（代码级）

1. `recoverVisionPipeline()` 同时做了三件事：清空裁剪网格、`abortCaptureWindow()`（清空稳定窗口样本）、`beginVisionEpoch()`（清零 `lastProcessedSampleAt` / `lastStableWindowAt` / `lastVisionAt` / `lastRecognitionCompletedAt`）。
2. 稳定窗口成立需要连续 8 个样本 = 8 × 125ms = 1000ms；而 `STREAM_STABLE_STALL_MS = 1500ms` 会判定“没有稳定窗口”为故障并再次 `RESET_VISION`。
3. 因为恢复动作把“进展时间戳”清零，看门狗下一拍（250ms）看到的就是“已经很久没有进展”，于是立刻再加压恢复 → 恢复越勤，进展证据越少，判定越像故障。
4. 结果：整轮会话 0 次识别，而取帧/截图侧被反复重建，既是卡死，也是当时最大的无谓功耗来源。

## 修复后的预期日志形态（用于设备复测核对）

```text
PIPELINE_VISION_RESET grid=true|false misses=N idle=false blindRecoveries=0 keepSamples=true
PIPELINE_WATCHDOG_ACTION ... idle=false blindRecoveries=1 recoverEvery=1250ms
VISION_RAW tier=LITE ...            ← 必须真的出现
VISION_REJECT ... misses=3 level=1
VISION_THRESHOLD_STEP ... misses=3 level=1 conf=0.324 fullScan=true
```
