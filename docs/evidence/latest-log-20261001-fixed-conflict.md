# 最新真机日志：Medium 固定同格类别冲突取证

日期：2026-10-01
会话：`SESSION|generation=1|reason=one_tap_prepare`

## 事件链

1. 一键准备完成后保持暂停，没有在用户点击开始前启动识别。
2. 用户点击开始后出现 `RUN_START|mode=3 auto=true`，录屏表面恢复，状态进入 `FINDING`。
3. Medium 正常推理，日志多次出现 `VISION_RAW|tier=MEDIUM`。
4. 推理结果长期为 `raw=34 pieces=33 mapped=32`，说明棋盘框和绝大部分棋子都已经被模型检测到。
5. 每次结果都在同一格出现：`class-conflict=[r=9,c=8,piece=5/6,...]`。
6. `VISION_FULL_RECHECK` 反复执行，仍得到同一冲突。
7. `VISION_THRESHOLD_STEP` 从 `misses=3` 持续到至少 `misses=30`，置信度从 `0.45` 降到 `0.16`，冲突仍存在。
8. 会话结束前没有 `BOARD_CONFIRMED`，随后用户点击关闭。

## 根因判断

这不是取帧停止、模型未加载、棋盘完全识别失败或单纯阈值过高。两个同格类别候选都已经超过阈值，降低阈值无法让弱候选消失。问题属于固定的同格类别竞争：强候选约 `0.80`，弱候选约 `0.53–0.56`，且两者为同一阵营类别。

## 修复

新增 `DetectionConflictPolicy`：

- 强候选至少 `0.70`；
- 弱候选不高于 `0.60`；
- 分差至少 `0.18`；
- 仅同阵营类别允许尝试；
- 删除弱候选后必须重新映射，并通过双王、棋面结构、合法位置和安全门；
- 任一条件不满足仍拒绝，禁止按置信度无条件猜类别。

## 当前证据

定向测试已通过：

- `DetectionConflictPolicyTest`
- `YoloPipelineTest`
- `DetectionBoardMapperTest`
- `PipelineHealthPolicyTest`
- `AssistCoreTest`

真机尚未复测，因此不能声明 Medium 已在该截图/棋盘上完全修好，也不能据此交付或发版。
