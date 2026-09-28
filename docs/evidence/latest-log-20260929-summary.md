# 2026-09-29 最新真机日志取证摘要

- 原始文件：`/workspace/测试log/9月29号凌晨0:14最新log/assist.log`
- 原始规模：9,923 行，2,042,679 字节。原始日志未修改。
- 会话：从 `one_tap_prepare` 开始，以 `one_tap_close` 结束；中途没有新的 `SESSION` 重置。
- 主动操作：59 次 `USER_ACTION`。
- 前台安全暂停：4 次；前台恢复：4 次。
- 看门狗事件：67 次；落子重建事件：62 次；识别拒绝：784 次；落子确认：51 次。

## 关键结论

1. 前台切换链路的运行态恢复有日志证据：`FOREGROUND_OBSERVATION_PAUSE` 后出现 `FOREGROUND_RETURN_STABLE`、`FOREGROUND_RETURN` 和 `FOREGROUND_RESUME`。
2. 日志末段用户点击后记录了 `RUN_PAUSE`、`CAPTURE_SURFACE_PAUSED`、`AUTO_TICK|PAUSED`，说明内部暂停状态已经落地。
3. 之前面板代码把 `suspendedByForeground=true` 当作“运行中”处理，导致内部已经 `paused=true` 时仍显示绿色“暂停”。本轮改为按钮只读取真实 `running` 字段：运行中显示绿色“暂停”，暂停/前台安全暂停显示白色“继续”。

## 代表性原始事件

- `1790611358175|FOREGROUND_OBSERVATION_PAUSE|reason=application-window-query-failed resumePackage=cn.jj.chess.nearme.gamecenter wasRunning=true`
- `1790611383431|FOREGROUND_OBSERVATION_PAUSE|reason=application-window-query-failed resumePackage=cn.jj.chess.nearme.gamecenter wasRunning=true`
- `1790611359063|FOREGROUND_RESUME|package=cn.jj.chess.nearme.gamecenter mode=3`
- `1790611384447|FOREGROUND_RESUME|package=cn.jj.chess.nearme.gamecenter mode=3`
- `1790611800893|USER_ACTION|surface=overlay action=更新棋谱`
- `1790611806528|USER_ACTION|surface=overlay action=重置棋盘`
- `1790611923983|USER_ACTION|surface=overlay action=开始继续或暂停`
- `1790611925226|USER_ACTION|surface=overlay action=关闭按钮（等待二次确认）`
- `1790611925920|USER_ACTION|surface=overlay action=一键关闭`
- `1790611923989|CAPTURE_SURFACE_PAUSED|projectionRetained=true virtualDisplayRetained=true`
- `1790611923993|STATUS|已暂停（屏幕画面未输出；无障碍继续监听前台）回到棋盘应用后点『继续』恢复`
- `1790611923995|AUTO_TICK|PAUSED/null/false/false/false/3k5/9/4R4/9/9/9/9/4B4/4K4/9 w - - 0 1`
- `SESSION_END|reason=one_tap_close`
