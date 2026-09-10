# VLM 相机第一版

## 交付状态

本次提交的是 Android 客户端、MNN JNI 桥接、程序化 Filament 人偶及可本地启动的 Python 网关代码。自动化测试已编写，**未运行 Gradle、Android 构建或自动化测试；未完成真实模型、云端密钥和设备验证**。编译兼容性、GPU 显示、模型输出成功率、延迟与内存都需要按下文在本地验收，不能将代码接入等同于模型验收通过。

主 App 拍照入口为 `feature-camera-vlm`，包名 `com.framer.sense.feature.camera.vlm`。保留三个旧相机模块源码，但 App 不再依赖 ONNX v2，也不再在 Application 启动时预加载 ONNX。本次不创建 iOS 工程；JSON 协议、坐标、姿态编号和关节配置可作为后续 iOS 实现依据，Kotlin/Android 渲染和 JNI 本身不是跨平台二进制。

## 从哪里开始

1. 执行下方 MNN 源码准备脚本。Android 构建需要本地 Android SDK 36、JDK 17、NDK 和 CMake 3.22.1。首次源码准备和依赖解析需要网络。
2. 先启动网关，配置至少一个所在区域的图文模型。App 模型设置选择“指定云端”，保存网关地址及**网关访问令牌**。
3. 本地构建安装 Debug 包。在拍照页面授予相机权限，点击“开始构图”；也可使用 Debug 的“测试图片”入口固定输入。
4. 确认冻结、三方案、姿态预览和应用流程后，再按下文转换并导入离线模型包，选择“强制离线”进行断网验收。
5. 最后启用“自动切换”，人为关闭首选供应商或模拟限流，检查实际供应商记录。真实模型成功率需人工检查，不能用测试固定结果替代。

## 目录与责任

| 路径 | 责任 |
| --- | --- |
| `feature-camera-vlm/.../camera` | CameraX 绑定、冻结复制、转正裁剪、JPEG 缩略图、MediaStore 保存 |
| `.../model` | JSON 协议、坐标换算、模型配置和会话输入 |
| `.../data` | 网关适配、区域路由、本地 ZIP 导入、MNN 串行推理、加密设置 |
| `.../agent` | 提示词、结构与几何校验、两轮导演循环、Repository |
| `.../avatar` | 12 个姿态、4 个表情、父子关节、Filament 按需离屏渲染和合成缓存 |
| `.../ui` | MVI 状态、Intent、Effect、ViewModel、相机和模型设置面板 |
| `.../di` | Hilt 进程级依赖装配 |
| `feature-camera-vlm/src/main/cpp` | CMake 与 MNN JNI 桥接 |
| `feature-camera-vlm/scripts` | 固定版本源码准备、模型 ZIP 打包 |
| `services/vlm-gateway` | FastAPI/HTTPX/Pydantic 单步网关及模拟上游测试 |

## 相机与页面行为

状态为 `LIVE → FREEZING → FROZEN → GENERATING → READY → MODIFYING → LIVE`。冻结使用 CameraX 内存拍摄，得到独立 Bitmap 后立即关闭 ImageProxy；预览和 ImageCapture 使用同一 ViewPort，并统一裁剪及旋转。显示图 JPEG 质量 95，模型输入长边最多 768 像素、JPEG 质量 85。

三方案卡片显示预览、景别、倍率、姿态、表情。选中后可拖动人物、调整大小、重新生成或发送文字修改。修改只允许选中方案变化。基础校验和实际投影范围都通过后才允许应用；应用恢复实时预览、设置设备支持的倍率并叠加透明人偶参考，真实照片仍由 CameraX 单独保存到 `Pictures/FramerSense`，不合入人偶。

保留底部蓝色“拍摄”和横屏侧栏导航。冻结、生成、修改、模型管理和保存期间禁止拍摄；不排队补拍。相机默认自动曝光、白平衡、对焦，支持点击对焦测光。`1×/2×/3×` 是设备 zoom ratio，不承诺对应独立光学镜头，也不能由单张冻结图推断精确焦距、人物距离或安全站位。

ViewModel 持有冻结会话；横竖屏切换保留图片和方案。界面被移除时取消平台关联工作，正在生成的会话保留冻结图供重试。画面编号、请求令牌和方案 revision 防止迟到结果覆盖。进程重建只恢复“需要重新构图”提示，不恢复不存在的 Bitmap 或旧任务。

Debug 原始输出仅在“调试详情”中展开。正式产品界面只显示模型与耗时、方案解释和错误提示，不展示原始对话。

## 公共协议与坐标

导演决策外层：

```json
{
  "version": 1,
  "imageId": "当前冻结画面的 UUID",
  "action": "FINAL",
  "plans": [
    {
      "id": "p1", "title": "环境人像", "shot": "ENVIRONMENT",
      "crop": {"left": 0, "top": 0, "right": 1, "bottom": 1},
      "zoom": 1,
      "avatar": {
        "foot": {"x": 0.33, "y": 0.92}, "height": 0.35, "yaw": 0,
        "pose": "RELAXED", "expression": "SMILE", "expressionIntensity": 0.6
      },
      "guidance": "站到画面左下方，保留建筑主体",
      "reason": "人物与环境形成比例对比", "needsRetake": false,
      "uncertainties": ["单张图片无法确认脚下支撑条件"], "revision": 0
    }
  ]
}
```

上例仅解释一项字段；生成和最终提交必须恰好三个方案。`CAPABILITIES` 使用空 plans。景别包括 `ENVIRONMENT/FULL/HALF/CLOSE_UP`，其余枚举以 `CompositionModels.kt` 与网关 `models.py` 为准。

- 原点在左上角，x 向右、y 向下。crop 使用冻结原图归一化坐标。
- avatar 使用**裁剪后的方案画面**坐标。foot 是脚底，height 是整个人偶的投影高度；半身和特写通过真实裁切实现，脚可以在画面外。
- 原图坐标：`originalX = crop.left + planX × crop.width`，y 同理。`PlanCoordinates` 提供双向换算。
- 首版保持原画幅，所以归一化 crop 的宽高跨度相同。同机位居中变焦满足 `zoom = currentZoom / crop.width`。
- 偏心构图、向外扩大视场或移动机位需标记 `needsRetake=true`。冻结图只能展示已有图像范围，不能预览画面外新增内容。
- 校验有限数值、裁剪范围、设备倍率、姿态枚举、景别对应的人体裁切和修改范围。Filament 工具额外校验真实头脚、手臂等投影是否出画。上述检查不等同于审美或地面安全检查。

## 人偶渲染

固定依赖 `filament-android:1.76.0` 和 `filamat-android:1.76.0`，无需外部 glTF、VRM、蒙皮或人物资产。球体、椭球体和胶囊体通过父子关节组合，固定比例，支持整体旋转和脚底定位。

姿态：自然站立、侧身、回眸、身前交叠、单手叉腰、双手叉腰、挥手、指向、扶帽、抬头、低头、双臂展开。表情：平静、微笑、开心、惊讶。扶帽只是抬手动作模板，首版没有帽子资产。

Filament 由专用 HandlerThread 使用，几何体和材质复用，单次创建场景、渲染、读回透明 Bitmap 后释放临时资源。最多缓存六张方案预览；没有三个持续运行的渲染循环。光照使用背景稀疏像素估计的亮度/暖色倾向，以及柔和环境光和方向光。实际阴影遮挡、人体与地面空间锚定、VR 和个人形象重建均不在首版内。

## 准备 MNN 和模型包

### 固定原生依赖

在仓库根目录运行：

```bash
bash feature-camera-vlm/scripts/prepare_mnn.sh
```

脚本只克隆官方 tag `3.6.1` 到忽略目录 `feature-camera-vlm/third_party/MNN`，不运行构建，不下载权重。已有目录必须为该 tag，不覆盖用户源码。

CMake 将 MNN 合并构建为共享库，打开 LLM、OpenCV 视觉输入和 IMGCODECS，关闭 HTTP 资源读取及音频。JNI 构建目标为 `arm64-v8a`，使用 CPU 四线程。MNN 原生代码与桥接使用 16 KiB 页对齐链接参数；最终 APK 的所有第三方库与目标设备兼容性仍需本地验证。

### 转换模型

使用官方 `Qwen/Qwen3-VL-2B-Instruct` 原始权重或可信的、确实由 MNN 3.6.1 转换的对应模型。不要仅修改其他模型清单中的名称。原始权重需自行获取并遵守其许可证。

在工作站按 MNN 3.6.1 导出工具说明准备 PyTorch/Transformers/ONNX 等 Python 环境，以及同版本 `MNNConvert` 或 PyMNN。以下为已按该版本导出 CLI 参数编写的转换示例，**本次没有实际执行模型转换**：

```bash
python feature-camera-vlm/third_party/MNN/transformers/llm/export/llmexport.py \
  --path /absolute/path/Qwen3-VL-2B-Instruct \
  --dst_path /absolute/path/qwen3-vl-2b-mnn \
  --export mnn --quant_bit 4 --visual_quant_bit 8 \
  --mnnconvert /absolute/path/MNNConvert
```

导出耗时与内存取决于工作站。不要使用 `--skip_weight`；导出目录必须包含完整视觉模型、语言模型及分词器。必要时先在工作站使用该版本 `llm_demo` 对一张图片验证导出结果，再打包。

### 打包与导入

Python 3.10+，无额外打包脚本依赖：

```bash
python3 feature-camera-vlm/scripts/package_model.py \
  /absolute/path/qwen3-vl-2b-mnn \
  /absolute/path/qwen3-vl-2b-mnn.zip
```

脚本不改变导出目录，只收录运行所需文件并生成 SHA-256 清单，不包含 ONNX 中间文件或原始 PyTorch 权重。ZIP 根目录包含：

- `manifest.json`：`model=Qwen3-VL-2B-Instruct`、`runtime=MNN-3.6.1`、每个文件的相对路径/大小/SHA-256。
- `config.json`、`llm_config.json`，后者包含 `model_type=qwen3_vl`、`is_visual=true`。
- 语言网络与权重，默认 `llm.mnn`、`llm.mnn.weight`。
- 分词器，支持配置中的 `tokenizer_file`（新导出可能是 `tokenizer.mtok`，旧默认 `tokenizer.txt`）。
- `visual_model` 对应网络（默认 `visual.mnn`）及存在的独立 `.weight` 文件。
- 非共享词嵌入时需要 `embedding_file`，默认 `embeddings_bf16.bin`。

首版拒绝外部目录覆盖、临时路径配置、非 CPU 后端及推测解码。标准导出配置可直接使用；如果手工配置过路径，先在导出副本中恢复包内相对路径。

在 App“模型设置 → 导入 ZIP”选择文件。解包到新的私有暂存目录，检查路径穿越、重复路径、最多 4096 项、最多 12 GiB、剩余空间、清单、SHA-256 和视觉配置。全部通过后提交激活指针，再删除旧模型；失败保留旧模型。导入同时保留新旧包，设备应有足够暂存空间，不能只留一个新包的大小。

加载、卸载、删除均通过串行原生执行顺序协调。取消发生在可检查的 token 边界；模型加载和图像预填充期间无法保证即时中断，取消后也不会并发释放句柄。最长输出 4096 token，生成阶段有 120 秒预算，预填充的实际可中断时间取决于 MNN。

JNI 使用 MNN Omni 的真实 `<img>私有图片路径</img>` 视觉解析入口；该入口会读取图片并运行视觉编码器。用户提示词中的额外媒体标签会被转义。强制离线只有 `LOCAL` 候选，不创建网关请求；缺模型、错误 ABI、加载或推理失败均返回明确错误，不生成假方案。

## 网关与云端

运行说明和配置示例见 [`services/vlm-gateway/README.md`](../services/vlm-gateway/README.md)。接口为：

| 接口 | 行为 |
| --- | --- |
| `GET /health` | 存活检查，不需令牌 |
| `GET /v1/capabilities` | 已配置供应商及服务端模型 ID，需要网关令牌 |
| `POST /v1/director/step` | 单步图文请求，返回 model/raw/decision/elapsedMs，需要网关令牌 |

Android 不保存供应商 API key，也不写死云端模型 ID。设置中的令牌用 Android Keystore AES-GCM 加密保存。Debug manifest 允许本地 HTTP；Release 客户端拒绝 HTTP，部署时使用 HTTPS。

四家适配使用各自实际图像通道：千问/Seed Chat Completions `image_url`，GPT Responses `input_image` 和严格 JSON Schema，Gemini `inlineData` 和 `responseJsonSchema`。千问启用 JSON Object；Seed 按提示词生成 JSON，由公共协议校验，不能假定所有模型支持同一种严格结构化输出。

## 导演与自动切换

每次生成/修改最多两次取得模型输出的决策。首轮可以请求能力、校验或渲染；最后一轮必须 FINAL。协议错误、几何错误和实际渲染边界会写入工具观察供一次修正，不进行渲染图二次审美评审。

- 强制离线：LOCAL。
- 指定云端：只尝试所选供应商，失败直接显示错误。
- 自动大陆：千问 → Seed → 离线；自动海外：GPT → Gemini → 离线。首选设置只能重排区域内顺序。
- 每个失败候选只尝试一次，不做传输重试；成功供应商可以接收第二轮修正。切换不会重置决策预算。
- 网络故障、超时、限流、未配置及服务故障允许切换；取消、参数错误和内容拒绝不触发规避式切换。
- 所有候选失败或两轮内未提交合法最终方案时保留冻结图和错误，供用户主动重试。

## 本地验收

以下命令仅供用户执行，本次没有代为运行：

```bash
# 先准备 MNN 源码，再构建
./gradlew :app:assembleDebug
# 协议、路由、导演预算与坐标测试
./gradlew :feature-camera-vlm:testDebugUnitTest
# 包导入、会话门控测试：连接设备后执行
./gradlew :feature-camera-vlm:connectedDebugAndroidTest
# 主导航不排队补拍回归
./gradlew :app:connectedDebugAndroidTest
# 网关使用模拟上游，不需要真实供应商密钥
cd services/vlm-gateway
python -m pytest tests
```

设备验收记录至少包含：机型/Android/ABI、模型包与量化设置、实际供应商与模型 ID、冻结到三方案耗时、进程峰值内存、错误与修正记录。建议固定同一古建筑、室内、街景三张图，分别测试离线与所在区域两家云端。

必须人工检查三方案是否有区别、动作是否容易模仿、人物比例是否合理，以及地面/遮挡等不确定项是否诚实说明。另检查横竖屏、后台返回、取消后迟到结果、连续点击、全身头脚完整、半身裁切、参考应用后倍率、系统相册保存无虚拟人偶。离线真机验收应实际断网，并确认未出现云端调用。

## 实现依据

- [MNN 3.6.1 发布与源码](https://github.com/alibaba/MNN/releases/tag/3.6.1)
- [MNN 3.6.1 LLM 接口](https://github.com/alibaba/MNN/blob/3.6.1/transformers/llm/engine/include/llm/llm.hpp)
- [MNN Omni 图像解析](https://github.com/alibaba/MNN/blob/3.6.1/transformers/llm/engine/src/omni.cpp)
- [MNN 3.6.1 导出工具](https://github.com/alibaba/MNN/blob/3.6.1/transformers/llm/export/llmexport.py)
- [Filament 1.76.0](https://github.com/google/filament/tree/v1.76.0)
- [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)
- [Gemini 结构化输出](https://ai.google.dev/gemini-api/docs/structured-output)
