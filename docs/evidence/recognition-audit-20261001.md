# XQDK 盘面识别全链路审查记录

审查日期：2026-10-01
项目：`/workspace/XQDK`
基线：`v1.3.4`，当前工作树包含本轮稳定确认修复与本轮整体审查修复

## 审查范围

本次沿着“录屏帧 → 稳定窗口 → TFLite 推理 → 后处理 → 棋盘映射 → 棋面安全门 → 多帧确认 → 引擎分析 → 自动落子 → 落子核对 → 看门狗恢复”逐段核对，并对历史真机日志、失败截图验收账本和现有纯 JVM 测试进行交叉检查。

## 真机日志证据

### 2026-09-29 23:37 日志

- 文件：`/workspace/测试log/9.29晚上11:37/assist.log`
- 10,650 行，2,752,396 字节。
- `VISION_RAW` 852 次，`VISION_RESULT` 422 次，`BOARD_CONFIRMED` 151 次。
- `VISION_REJECT` 452 次，其中 409 次为“当前画面存在同格类别冲突”。
- `PIPELINE_WATCHDOG_ACTION` 215 次，`REBASE_LANDING` 52 次，`LANDING_COMMIT` 74 次。
- 结论：主要瓶颈不是模型完全不推理，而是局部裁剪结果出现同格跨类别竞争后，整帧被拒绝；原有裁剪链没有及时回到整屏几何基准复核。

### 2026-09-29 08:41 日志

- 文件：`/workspace/测试log/9.29 8:41/assist.log`
- 5,198 行，2,291,310 字节。
- `VISION_RAW` 982 次，`VISION_RESULT` 905 次，`VISION_REJECT` 960 次。
- 其中 757 次为落子核对后棋面无法与落子前/预期局面/一步合法应手对应，126 次为“黑将数量异常:0”，74 次为同格类别冲突。
- 结论：识别失败和落子核对失败同时存在；不能只调落子重试，必须先修复稳定棋面提交和裁剪/整屏复核链。

### 2026-09-29 20:36 日志

- 文件：`/workspace/测试log/九月二十九下午8:36/assist.log`
- 14,554 行，4,344,940 字节。
- `VISION_REJECT` 1,109 次，其中 831 次为同格类别冲突，259 次为黑象非法点位。
- 结论：同格冲突和网格/类别映射异常是重复出现的主导拒绝类型。

## 已确认的代码根因

1. **等待对手变化时的看门狗误恢复**
   已有稳定棋面后，`WAITING` 阶段本来只需要等待下一次画面变化，但旧逻辑只看“最近是否产生新的视觉提交”。静止棋面没有新的提交时，约 2.5 秒后会执行 `RESET_VISION`，历史日志中表现为等待阶段周期性自我重置，增加功耗并破坏连续观察。

2. **取帧只收到一帧后永久被视为健康**
   看门狗只要看到本次重建后收到过任意一帧，就永久跳过取帧重建。若录屏随后真正停止，`capturePump` 只能不断探帧而不能触发恢复，存在“收到一帧后再死掉就永远卡住”的路径。

3. **裁剪识别失败时回整屏复核不及时**
   已有网格后使用裁剪推理；裁剪结果出现同格冲突、缺王或映射为空时，旧链路不一定及时执行整屏复核，导致同一个错误锚点被反复使用。

4. **识别失败计数过早复位**
   原逻辑在单帧通过结构安全门后立即复位阈值降级，即使 `BoardTracker` 尚未连续确认该棋面，也会清零失败计数。这会使“安全候选但未形成稳定棋面”的连续失败无法正常降级恢复。

5. **落子合法性异常时错误放行**
   `AssistBoard.isLegalMoveInModel` 在规则引擎异常时返回 `true`，会把“规则校验没有得到结果”当成“着法合法”，破坏自动落子最后一道安全门。

## 本轮已实施的修复

- `PipelineHealthPolicy` 增加“等待已确认棋面变化”状态；等待阶段不再因没有新的棋面提交而周期性 `RESET_VISION`，真实帧停摆仍可触发恢复。
- 取帧看门狗新增静止帧保护上限：重建后收到过帧并不代表永远健康；超过 3 秒没有新帧时重新按取帧停摆处理，允许 `REBUILD_CAPTURE` 或重新授权。
- `ScreenAssistService.detectVision` 在裁剪结果出现同格冲突、缺王或完全映射失败时，按最小间隔执行一次有界整屏复核；只有整屏候选更安全时才替换裁剪结果。
- 阈值恢复只在刷新成功、同一已确认棋面再次通过确认、真正新棋面确认或落子回执提交后复位；未通过 `BoardTracker` 连续确认门的单帧候选不再提前清零。
- 规则引擎异常时不再放行自动落子，改为拒绝当前着法并等待重新识别。
- 保留双王、棋盘结构、位置、同格冲突、稳定窗口和落子前棋子归属安全门，没有通过降低安全门制造“识别成功”。

## 当前验证

已通过定向 ARMv8 JVM 测试：

- `PipelineHealthPolicyTest`
- `YoloPipelineTest`
- `DetectionBoardMapperTest`
- `AssistCoreTest`
- `LegalMoveGateTest`

当前定向测试命令：

```text
./gradlew :app:testArmv8-DebugUnitTest --tests 'com.xiangqi.assist.assist.PipelineHealthPolicyTest' --tests 'com.xiangqi.assist.assist.YoloPipelineTest' --tests 'com.xiangqi.assist.assist.DetectionBoardMapperTest' --tests 'com.xiangqi.assist.assist.AssistCoreTest' --tests 'com.xiangqi.assist.assist.LegalMoveGateTest'
```

结果：`BUILD SUCCESSFUL`。

## 尚未闭合的验收项

- 尚未完成本轮全量 JVM 测试与双变体 Release 构建复验。
- 尚未在 realme RMX5200 / Android 16 真机安装本轮包，无法证明 Medium/Lite 的真实录屏、皮肤域识别、落子和功耗已经闭合。
- 仍需真机日志确认：
  - `WAITING` 阶段不再周期性 `RESET_VISION`；
  - 裁剪冲突/缺王时出现 `VISION_FULL_RECHECK` 并能恢复；
  - 录屏收到一帧后再次停摆，超过静默上限能够自动重建；
  - Medium/Lite 在同一批失败截图上均能完成安全棋面确认；
  - 落子核对拒绝不再形成连续锁死链。

在上述设备证据返回前，本轮不能声明整体交付完成。
