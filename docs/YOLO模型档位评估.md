# 移动端识别模型替换评估

**项目**：XQDK 象棋辅助
**适用版本**：1.3.2（versionCode 6）
**目标**：效果优先；非 Lite 档位必须有中国象棋专用训练/微调证据、Android 部署证据和同条件效果证据；单个模型文件不超过 `200,000,000 bytes`；原始 V5 Lite 固定作为最终最小兜底。

- **YOLO26-S**：当前优先修复原生 YOLO26-S 中型候选；若固定评测集仍不通过，回退原始 V5 Medium。
- **历史复合大型**：恢复既有 `yolov5l_xq_fp32.tflite` 作为临时可用档，不称原生大型；继续优化几何、后处理和识别重试链路。
- **后续候选**：只寻找已有中国象棋成品权重，允许本地转换/量化/输出适配；禁止从零训练。

## 运行档位

| 运行档位 | 当前资产 | 文件大小 | SHA-256 | 输入/输出 | 状态 |
| --- | --- | ---: | --- | --- | --- |
| 超大型 | 暂无合格原生工件 | — | — | 待取得 | 目标档位，真实探测失败后回退 |
| 大型（历史复合） | `app/src/main/assets/yolov5l_xq_fp32.tflite` | 56,903,044 bytes | `64c8746d458247fec62ea0347dd8a2587613b87269bad9cb0a052dfa310d65e5` | `[1,640,640,3] → [1,25200,20]` FLOAT32 | 临时可用历史复合档，不称原生大型 |
| 中型（YOLO26-S） | `app/src/main/assets/yolo26s_xq_fp32.tflite` | 38,081,200 bytes | `9dfc61756069ad232be44ab347bf5bed39e896d54e0058e4eb444bef3034dbb2` | `[1,640,640,3] → [1,19,8400]` FLOAT32 | 原生候选，先修复；效果不合格回退 V5 Medium |
| Lite（V5保底） | `app/src/main/assets/yolov5n_xq_fp16.tflite` | 3,824,480 bytes | `d7ebc6c3d79aeaf92e5407e56a9909d83214176a127073226cbf58b78fff9bc4` | `[1,640,640,3] → [1,25200,20]` FLOAT32 | 最终最小保底 |

用户选择与实际运行档位分开记录。回退顺序为：`SUPER_LARGE → LARGE → MEDIUM → LITE`。只有资产读取、Interpreter 创建、张量分配、输入输出契约、必要算子或一次受控推理失败时才回退；CPU负载、温度、功耗和速度只作提示。

## 原生 YOLO26-S 候选取证

### 来源与训练契约

- 来源：公开仓库 `DuyLeTran/DeepXiangQi` 的 `Reconstruction/weights/detect-ultra.pt`。
- 仓库 `data_detect.yaml` 声明15类：`Black_Advisor`、`Black_Bishop`、`Black_Cannon`、`Black_King`、`Black_Knight`、`Black_Pawn`、`Black_Rook`、`Red_Advisor`、`Red_Bishop`、`Red_Cannon`、`Red_King`、`Red_Knight`、`Red_Pawn`、`Red_Rook`、`board`。
- 原始权重大小：`20,376,645 bytes`。
- Ultralytics 元数据：YOLO26-S，约 `9,470,985` 参数、约 `20.8 GFLOPs`；这些是模型元数据，不是真机速度或功耗。

### Android 工件与输出适配

- Android 工件：`app/src/main/assets/yolo26s_xq_fp32.tflite`。
- 文件大小：`38,081,200 bytes`，低于200 MB上限。
- 输入：`[1,640,640,3] FLOAT32`，NHWC RGB。
- 输出：`[1,19,8400] FLOAT32`，4个框参数加15个类别分数，没有 objectness 列。
- `YoloModelFormat.YOLO26_RAW` 负责选择独立路径；`Yolo26Postprocessor`负责 raw 解码、类别边际门、类别感知 NMS、棋盘几何过滤和同格冲突前置保留。
- YOLO26原始类别顺序已映射到项目既有 `YoloDetection`/`Piece` 标签契约，避免把 Black_Advisor 等新顺序误当成旧 V5 的类别ID。
- 运行时探测按实际档位检查对应输出形状；旧 V5 路径仍检查 `[1,25200,20]`。

### 主机转换验证

- raw ONNX 与 raw TFLite 使用同一输入复核：最大绝对差约 `0.001313`，相关系数约 `1.0`。
- 主机 TFLite Interpreter 可分配输入输出张量并执行；实际 Android `tensorflow-lite:2.14.0` 设备兼容性仍须由运行时探测和真机回传确认。
- 当前4张异常截图上，YOLO26-S 主机单线程热身后推理约 `200–230ms`；这不是 Android P50/P95，也不是功耗结论。

### 当前样本观察

- YOLO26-S 在当前4张异常图上能产生真实15类检测和棋盘框。
- “错误的识别结果”样本仍存在同一棋格附近不同类别候选；因此同格类别冲突拒绝门必须保留，不能按最高分强行覆盖。
- 没有同一帧集的逐格人工标注时，检测数量、置信度或模型名称都不能证明识别准确率提升。

## 旧 Large 集成资产的边界

`yolov5l_xq_fp32.tflite` 是既有 Medium 与 universal/旋转鲁棒模型的逐检测行集成，输入输出仍为 `[1,640,640,3] → [1,25200,20]`。它不是独立训练的 YOLOv5-L，也不是本轮要求的原生大型模型；资产、来源和构建脚本继续保留，作用是可回滚历史材料，不作为原生大型结论。

## 已排除或暂不接入的公开路线

- `nrl-ai/chessai` 的公开 ONNX 约35.8 MB，但输出为12维，类别契约采用7种基础棋子再用颜色二阶段判断，不是当前14种红黑棋子加棋盘框，不能直接替换。
- `TheOne1006/chinese-chess-recognition` 的 Swin 16类布局模型可在主机 ONNX Runtime 上运行，但当前 Android TFLite 转换、动态输入布局和部署契约尚未闭合，不接入生产。
- Roboflow 页面当前受到 Cloudflare 挑战，无法完成公开权重的可追溯下载和复核；不把无法复核的候选打进 APK。
- VinXiangQi v1.4.0 归档中型/万能模型与当前对应模型随机输入输出逐元素等价，不能作为效果升级。

## 替换验收门

候选只有同时通过以下项目才可以替换对应档位：

1. 权重来源、许可证、训练类别和输入输出契约可追溯；
2. 文件实际字节数不超过 `200,000,000`；
3. 主机 ONNX/TFLite/LiteRT 数值和后处理结果可重放；
4. Android `Interpreter` 或 LiteRT 能真实创建、分配张量并完成受控推理；
5. 使用同一帧集、同一裁剪、同一预处理、同一线程数和同一稳定帧门比较；
6. 逐格类别、颜色、位置、方向和 FEN 不劣化，并对当前对应档位产生可观察效果跃迁；
7. 双王、非法棋面、低置信类别、同格冲突和自动落子前归属安全门继续有效；
8. 真机补测冷启动、热身、P50/P95/P99、PSS/native heap、温升、功耗、降频和连续运行稳定性；
9. 新模型失败可回滚到已知资产和原始 V5 Lite。

## 当前交付状态

- 已完成：四档目标、200 MB上限、V5 Lite最终保底、原生 YOLO26-S候选、TFLite工件、输出适配器、形状探测、类别顺序归一化和候选取证记录。
- 未完成：固定评测集逐格标注与效果跃迁证明；原生 Large；原生 Super-Large；目标真机性能和功耗；最终Release构建、远程推送和Release资产核验。
- 当前不能宣称：YOLO26-S已在真实设备上全面优于旧模型，或原生大型/超大型已完成替换。
