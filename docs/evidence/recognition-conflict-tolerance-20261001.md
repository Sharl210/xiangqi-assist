# 设备端日志取证：同格类别冲突导致 116 秒无法确认棋面

取证时间：2026-10-01（Asia/Shanghai）
取证对象：`/data/user/0/com.xiangqi.assist/files/logs/assist.log`
文件规模：408,095 字节；目录内仅此一个文件；最后写入 `2026-10-01T15:48:52+08:00`
读取方式：MT MCP 文件通道（访问策略将该目录列为可读写；PRoot 工作区看不到该沙盒路径）

## 一、会话骨架

单个会话，约 3 分 11 秒：

| 时间戳 | 事件 |
| --- | --- |
| 1790840740559 | 一键准备（会话开始） |
| 1790840770626 | 开始（进入识别） |
| 1790840906046 | 更新棋谱 |
| 1790840914244 | 重置棋盘 |
| 1790840931362 / 1790840932130 | 关闭按钮 / 一键关闭 |

## 二、识别统计（决定性对比）

| 事件 | 次数 |
| --- | --- |
| `VISION_RAW` | 约 130 次以上（失败期每约 600ms 一次） |
| `BOARD_CONFIRMED` | **仅 3 次**，全部在开头 3 秒内 |
| `VISION_REJECT` | 从 `misses=1` 一路累计到 **111** |
| `VISION_THRESHOLD_STEP` | 数十次，`level` 早早封顶在 4 |
| `WATCHDOG_ACTION action=RESET_GRID` | 每 10 秒一次，`phaseAge` 从 13221 堆到 **103425ms** |

时间线关键点：

- `1790840773406` 第 1 次 `BOARD_CONFIRMED`；
- `1790840782338` 第 2 次（`TURN_INFERENCE reason=OPPONENT_STEP`）；
- `1790840787401` 第 3 次；
- `1790840798202` 起进入永久拒绝，直到 `1790840914236`（`misses=111`）；
- 用户中途点「重置棋盘」，`epoch` 由 4 变 7、`misses` 重新计到 17，仍然全部同因失败；
- 最后用户只能一键关闭。

## 三、冲突数据的真实含义

冲突报告长这样：

```text
class-conflict=[r=9,c=8,piece=5/6,score=0.708/0.684]
```

- `piece=` 打印的是 `Piece` 常量：`5 = WJU 红车`，`6 = WPAO 红炮`；
- 检测行的 `l=` 打印的是 `labelId`：该格同时存在的检测是 `l=7 (r_che 红车)` 与 `l=12 (r_pao 红炮)`；
- 坐标 `c=619.5, y=1020.4` 经网格换算正好是 `r=9,c=8`，即屏幕右下角 = 红方底线最右（i0）；
- 该局面（`1RBAKAB1R`）在 i0 的正确棋子就是**红车**。

因此这是一个「红车 vs 红炮」的同格竞争，正确类别在候选中，而且分数更高。

## 四、根因

1. `DetectionBoardMapper.map` 处理同格冲突时执行：

   ```kotlin
   if (cells[row][col] != Piece.EMPTY && cellScore[row][col] >= p.score) { dropped++; continue }
   ```

   即**高分候选占位、低分候选丢弃**。所以冲突帧的 `mapped.screenRaw / canonical` 已经是「取高分」的正确棋面。

2. `ScreenAssistService.recognizeFrame` 却只要冲突列表非空就整帧否决：

   ```kotlin
   if (mapped.cellClassConflicts.isNotEmpty()) { ...; return null }
   ```

   **结果正确但被一票否决**，于是每 600ms 重复一次同样的失败。

3. 三条既有恢复链全部无效：

   | 机制 | 无效原因 |
   | --- | --- |
   | `VISION_THRESHOLD_STEP` | 降的是漏检阈值；本问题是类别竞争，两个候选都远高于阈值（日志已证：`level=4 conf=0.16` 无变化） |
   | `VISION_FULL_RECHECK` | 日志里它与裁剪路径几何一致（`crop=0,315,655,1047`），结果逐字相同 |
   | `WATCHDOG RESET_GRID` | 每 10 秒重建网格，重建后观测方式不变，结果依旧 |

4. 早期可确认与后期卡死的差别只在分差：

   | 时期 | 检测标记 | 分差 |
   | --- | --- | --- |
   | 开头可确认 | `dominant-conflict-recovery` | `0.797 / 0.552`，差 **0.245** → 弱候选被消解 |
   | 卡死 116 秒 | `targeted-recovery-needed` | `0.708 / 0.684`，差 **0.024** → 不满足 `MIN_SCORE_GAP=0.18` |

## 五、本轮修复

新增 `DetectionConflictTolerancePolicy`，判定单个同格冲突是否可容忍：

1. 胜者与败者分差 `>= 0.02`（胜负明确）；
2. 胜出候选置信度 `>= 0.50`；
3. `screenRaw` 该格最终确实落在胜出候选上（冲突没有改写结果）。

三条同时满足才算可容忍。`recognizeFrame` 在此基础上再要求：全部冲突可容忍、双王齐全、`AssistBoard.validate` 为空、`invalidPiecePlacement` 为 null，才放行该帧并打 `VISION_CONFLICT_TOLERATED`。

模糊僵局（分差 < 0.02）与非法棋面仍然拒绝，不放宽任何安全门、不改写任何类别。

## 六、验证与未完成项

- 定向测试：`DetectionConflictTolerancePolicyTest`、`DetectionConflictPolicyTest`、`PipelineHealthPolicyTest`、`AssistCoreTest`、`DetectionBoardMapperTest`、`YoloPipelineTest` 全部通过。
- 双变体 Release 构建：`assembleArmv8-Release`、`assembleArmv8-dotprod-Release` 成功。
- 未完成（不得写成已修好）：
  - 真机确认 `VISION_CONFLICT_TOLERATED` 出现并恢复 `BOARD_CONFIRMED`；
  - 真机确认提交类别正确（i0 为红车）；
  - 真机确认不再出现 100 秒以上的识别卡死；
  - Lite 与全部失败截图真机复测。
