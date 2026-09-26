# YOLO 模型档位与运行契约评估

**项目**：XQDK 象棋辅助
**适用版本**：1.3.3（versionCode 7，待发布）
**用户档位**：`Lite → Low → Medium → High`（按当前效果语义排序）
**实际探测顺序**：`High → Medium → Low → Lite`

## 档位注册表

| 用户档位 | Android 资产 | 输出格式 | 说明 |
| --- | --- | --- | --- |
| High | `yolov5l_xq_fp32.tflite` | YOLOv5 `[1,25200,20]` | 历史 Medium+universal/旋转鲁棒复合资源；不是原生大型权重 |
| Medium | `yolo26s_xq_fp32.tflite` | YOLO26 raw `[1,19,8400]` | 原生 YOLO26-S 中国象棋15类候选，独立 raw 解码 |
| Low | `yolov5m_xq_fp32.tflite` | YOLOv5 `[1,25200,20]` | 此前稳定的原始 V5 Medium，作为 Medium 的运行时回退 |
| Lite | `yolov5n_xq_fp16.tflite` | YOLOv5 `[1,25200,20]` | 原始 V5 Lite，最终兼容保底 |

用户只能选择以上四档；不存在的更大模型不进入用户选项。单个模型文件硬上限为 `200,000,000 bytes`。

## 选择、迁移与回退

- 默认选择为 `High`，用户选择写入 `AssistConfig`。
- 选择动作只保存配置，不创建/展开悬浮窗，不启动识别，也不热切换当前会话。
- 下一次一键准备或悬浮窗开始/继续时，才按持久化配置创建 detector 并执行兼容性探测。
- 旧值迁移：`SUPER_LARGE`、`LARGE`→`HIGH`；`MEDIUM`→`MEDIUM`；`MEDIUM_V5_FALLBACK`→`LOW`；`LITE`→`LITE`。未知值和空值使用 `HIGH`。
- 兼容性失败只由资产读取、Interpreter 创建、张量分配、输入/输出契约、必要算子或一次受控推理失败触发；CPU负载、温度、功耗和速度只作提示。
- 当前选择从 `High` 向下尝试 `Medium → Low → Lite`。成功回退后设置值和状态说明同步为实际档位；所有候选失败则明确提示且不进入识别运行态。
- 切换加载时旧 detector 的生命周期闸门必须先阻止新推理；新档位成功后释放旧 Interpreter，失败则保留原有可用 detector或进入明确不可用状态，禁止半切换。

## 资产证据

| 档位 | 文件大小 | SHA-256 | 输入/输出 |
| --- | ---: | --- | --- |
| High | 56,903,044 bytes | `64c8746d458247fec62ea0347dd8a2587613b87269bad9cb0a052dfa310d65e5` | `[1,640,640,3] → [1,25200,20]` FLOAT32 |
| Medium | 38,081,200 bytes | `9dfc61756069ad232be44ab347bf5bed39e896d54e0058e4eb444bef3034dbb2` | `[1,640,640,3] → [1,19,8400]` FLOAT32 |
| Low | 28,665,816 bytes | `900e32cfbee2cd6811c9e162988db86f20df16e21af2e34256a03b44259d3e5a` | `[1,640,640,3] → [1,25200,20]` FLOAT32 |
| Lite | 3,824,480 bytes | `d7ebc6c3d79aeaf92e5407e56a9909d83214176a127073226cbf58b78fff9bc4` | `[1,640,640,3] → [1,25200,20]` FLOAT32 |

## Medium：YOLO26-S raw 契约

- 训练来源声明15类：14种红黑棋子加 `board`。
- 输出没有 YOLOv5 objectness 列，必须使用 `YoloModelFormat.YOLO26_RAW` 和独立 `Yolo26Postprocessor`。
- 类别顺序先映射到项目既有 `YoloDetection`/`Piece` 契约，再进入棋盘几何、类别边际、类别感知 NMS 和同格冲突安全门。
- 当前样本仍有同格不同类别竞争；没有逐格人工标注和目标设备回传时，不能写成效果已经全面提升。

## High：历史复合资源边界

`yolov5l_xq_fp32.tflite` 是既有 Medium 与 universal/旋转鲁棒资源的逐检测行集成。它可以作为当前 High 的可加载历史资源，但不是独立训练的 YOLOv5-L，也不是用户要求的原生大型权重。未来如取得更强的现成中国象棋模型，必须按来源、许可证、类别契约、Android部署、固定帧集效果跃迁和真机性能证据重新替换，不能仅凭文件更大或模型名称更高宣称完成。

## 当前能力与未闭合项

- 已闭合：四档资产映射、旧配置迁移方向、输出格式区分、文件大小门、兼容性探测框架、原生 YOLO26-S Android 工件和独立解码路径。
- 待闭合：固定帧集逐格效果、复杂皮肤真机识别、Android 16 目标设备回归、冷启动和长时间性能、内存、功耗、温升以及更强原生现成模型取得。
- 不能宣称：YOLO26-S 已在真实设备全面优于旧模型；High 是原生大型；存在未取得的超大模型；主机耗时等于手机实测。
