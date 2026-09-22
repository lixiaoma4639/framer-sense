# VLM 相机第一版

## 交付状态

本次提交的是 Android 客户端、MNN JNI 桥接、Rocketbox 骨骼 GLB 人像及可本地启动的 Python 网关代码。已在 Android Debug 包和导入的 Qwen3-VL-2B MNN 模型上验证一次真实离线流程：模型画面分析、三方案校验、人偶投影和预览均完成；不同设备的成功率、延迟与内存仍需继续验收。

主 App 拍照入口为 `feature-camera-vlm`，包名 `com.framer.sense.feature.camera.vlm`。保留三个旧相机模块源码，但 App 不再依赖 ONNX v2，也不再在 Application 启动时预加载 ONNX。本次不创建 iOS 工程；JSON 协议、坐标、姿态编号和关节配置可作为后续 iOS 实现依据，Kotlin/Android 渲染和 JNI 本身不是跨平台二进制。

## 从哪里开始

1. 执行下方 MNN 源码准备脚本。Android 构建需要本地 Android SDK 36、JDK 17、NDK 和 CMake 3.22.1。首次源码准备和依赖解析需要网络。
2. 先启动网关，配置至少一个所在区域的图文模型。App 模型设置选择“指定云端”，保存网关地址及**网关访问令牌**。
3. 本地构建安装 Debug 包。在拍照页面授予相机权限，点击“开始构图”；也可使用 Debug 的“测试图片”入口固定输入。
4. 确认冻结、三方案、姿态预览和应用流程后，再按下文直接在 App 下载离线模型，选择“强制离线”进行断网验收。
5. 最后启用“自动切换”，人为关闭首选供应商或模拟限流，检查实际供应商记录。真实模型成功率需人工检查，不能用测试固定结果替代。

## 目录与责任

| 路径 | 责任 |
| --- | --- |
| `feature-camera-vlm/.../camera` | CameraX 绑定、冻结复制、转正裁剪、JPEG 缩略图、MediaStore 保存 |
| `.../model` | JSON 协议、坐标换算、模型配置和会话输入 |
| `.../data` | 网关适配、区域路由、OkHttp 下载与前台服务、目录/ZIP 导入、MNN 串行推理、加密设置 |
| `.../agent` | 提示词、结构与几何校验、本地单次/云端两轮导演循环、Repository |
| `.../avatar` | 4 个 Rocketbox GLB 角色、12 个受限姿势、6 个表情、Filament 按需离屏渲染和合成缓存 |
| `.../ui` | MVI 状态、Intent、Effect、ViewModel、相机和模型设置面板 |
| `.../di` | Hilt 进程级依赖装配 |
| `feature-camera-vlm/src/main/cpp` | CMake 与 MNN JNI 桥接 |
| `feature-camera-vlm/scripts` | 固定版本源码准备、模型 ZIP 打包 |
| `services/vlm-gateway` | FastAPI/HTTPX/Pydantic 单步网关及模拟上游测试 |

## 相机与页面行为

状态为 `LIVE → FREEZING → FROZEN → GENERATING → READY → MODIFYING → LIVE`。冻结使用 CameraX 内存拍摄，得到独立 Bitmap 后立即关闭 ImageProxy；预览和 ImageCapture 使用同一 ViewPort，并统一裁剪及旋转。显示图 JPEG 质量 95，模型输入长边最多 768 像素、JPEG 质量 85。

首次生成前由用户选择成年女性、成年男性、女孩或男孩作为指导人像：实时取景时在“开始构图”前选择，导入图片时可在冻结页选择；角色身份始终由用户控制，VLM 只产生构图、姿势和表情建议，不能从画面推断或替换角色。三方案卡片显示预览、景别、倍率、姿态、表情。选中后可拖动人物、调整大小、重新生成或发送文字修改。修改只允许选中方案变化。基础校验和实际投影范围都通过后才允许应用；应用恢复实时预览、设置设备支持的倍率并叠加透明人偶参考，真实照片仍由 CameraX 单独保存到 `Pictures/FramerSense`，不合入人偶。生成、重新生成、修改和冻结后的自动生成期间，加载遮罩只实时展示当前真实处理步骤（请求准备、模型分析或切换、结果校验、投影检验、预览渲染），不展示原始输出或步骤历史。

保留底部蓝色“拍摄”和横屏侧栏导航。冻结、生成、修改、模型管理和保存期间禁止拍摄；不排队补拍。相机默认自动曝光、白平衡、对焦，支持点击对焦测光。`1×/2×/3×` 是设备 zoom ratio，不承诺对应独立光学镜头，也不能由单张冻结图推断精确焦距、人物距离或安全站位。

ViewModel 持有冻结会话；横竖屏切换保留图片和方案。界面被移除时取消平台关联工作，正在生成的会话保留冻结图供重试。画面编号、请求令牌和方案 revision 防止迟到结果覆盖。进程重建只恢复“需要重新构图”提示，不恢复不存在的 Bitmap 或旧任务。

Debug 原始输出可在“调试详情”中展开，也会按用户排障需求打印到 `VlmOffline` 的“模型原始输出”日志：包含 request、part 和 raw，raw 是 JSON 转义字符串，按分段顺序拼接可还原换行和控制字符。`VlmDirector` 的“解析诊断”包含具体解析错误；这两类可能含模型原文的日志仅在 Debug 输出。原生“MNN 生成结束”同时记录结束状态、输入/输出 token 数及视觉编码、预填充、解码耗时；status=1 为模型输出结束标记，2 为分步生成达到本次 token 数（需结合 completeJson 和 LOCAL_OUTPUT_LIMIT 判断是否真正达到输出上限），3 为取消，4 为内部错误，5 为超时。正式产品界面只显示模型与耗时、方案解释和错误提示，不展示原始对话；Release 日志不记录模型原文。日志不直接打印网关令牌、用户提示词或图片路径。

离线加载和视觉编码不设固定总时限；JNI 每次实际写出 token 都通知停滞监控。取消标记在原生生成边界生效，不强行终止正在执行的算子，也不并发释放模型；原生推理尚未退出时，重复请求立即返回 `LOCAL_BUSY`。加载失败、推理失败和停滞超时显示不同提示，不再统一报“加载失败”。仍保留云端最多两轮；本地最多一次模型调用、最多 192 个生成 token 的预算。

离线模式与自动路由中的本地候选都只调用一次模型，结构或投影校验失败后立即结束，保留冻结图供用户主动重试；有效的本地方案通过所有校验后直接提交，不再为了补 `FINAL` 额外调用模型。云端保留最多两轮。

离线使用短构图意图协议：模型优先返回三行 `景别|方位|姿势|朝向|表情|标题|指导|理由`，不生成坐标、裁剪或倍率；客户端把三条意图安全映射为可校验的人偶布局并继续执行几何和真实投影校验。若本地模型只给出有效场景描述，客户端从描述中的空间线索生成三条彼此不同、标记为“场景分析备选”的意图；空输出、提示词复述和无场景内容会明确失败，绝不伪造与图片无关的建议。完整旧数组协议仍严格兼容。这样避免 Qwen3-VL 在低端 CPU 上因长 JSON 格式约束反复输出方括号或同一 token。

短提示词不再携带上次输出、重复校验历史或长字段示例。原生使用贪心采样，避免对必需的重复格式施加重复惩罚；完整裸 JSON 闭合后停止生成，尚未完成却达到 512 token 上限时以 `LOCAL_OUTPUT_LIMIT` 结束。取消后的输出不会再记录为成功。首 token 不限时和输出停滞 39 秒规则保留，512 token 是输出预算，不是完成时长承诺。

## 公共协议与坐标

模型输出的是摄影意图；最终 `CompositionPlan` 由客户端映射并校验。云端优先使用下列 JSON，离线使用同字段含义的三行短协议：

```json
{
  "version": 1,
  "imageId": "当前冻结画面的 UUID",
  "action": "FINAL",
  "intents": [
    {
      "id": "p1", "title": "环境人像", "shot": "ENVIRONMENT",
      "zone": "LEFT", "pose": "RELAXED", "facing": "FRONT",
      "expression": "SMILE", "expressionIntensity": 0.6,
      "guidance": "站到画面左下方，保留建筑主体",
      "reason": "人物与环境形成比例对比",
      "uncertainties": ["单张图片无法确认脚下支撑条件"]
    }
  ]
}
```

上例仅解释一项字段；模型必须给出恰好三条意图。景别包括 `ENVIRONMENT/FULL/HALF/CLOSE_UP`，方位为 `LEFT/CENTER/RIGHT`，朝向为 `FRONT/THREE_QUARTER_LEFT/THREE_QUARTER_RIGHT`，其余枚举以 `CompositionModels.kt` 与网关 `models.py` 为准。用户在拍摄前选择的人偶角色不属于模型协议，映射时始终保留。

- App 根据景别、方位和设备限制生成裁剪、倍率、脚点和人偶高度；模型提出的方位不会直接成为未校验的像素坐标。
- 原点在左上角，x 向右、y 向下。crop 使用冻结原图归一化坐标。
- avatar 使用**裁剪后的方案画面**坐标。foot 是脚底，height 是整个人偶的投影高度；半身和特写通过真实裁切实现，脚可以在画面外。
- 原图坐标：`originalX = crop.left + planX × crop.width`，y 同理。`PlanCoordinates` 提供双向换算。
- 首版保持原画幅，所以归一化 crop 的宽高跨度相同。同机位居中变焦满足 `zoom = currentZoom / crop.width`。
- 偏心构图、向外扩大视场或移动机位需标记 `needsRetake=true`。冻结图只能展示已有图像范围，不能预览画面外新增内容。
- 若三条意图完全重复或不适合安全渲染，映射器优先保留景别、姿势和表情，只替换方位或朝向到最近未占用组合；最终卡片显示的是实际映射后的方案。校验有限数值、裁剪范围、设备倍率、姿态枚举、景别对应的人体裁切和修改范围。Filament 工具额外校验真实头脚、手臂等投影是否出画。上述检查不等同于审美或地面安全检查。

## 人偶渲染

固定依赖 `filament-android`、`filamat-android` 和 `gltfio-android`。内置的 `src/main/assets/vlm_avatars/` 含四个由 Microsoft Rocketbox（MIT）导出的 GLB：成人女、成人男、女孩和男孩；资产来源与授权记录在该目录的 `NOTICE.md`。GLB 保留真实网格、纹理、蒙皮骨骼与 ARKit BlendShape，不再以球体或胶囊体拼接人形。

姿态：自然站立、侧身重心、回眸、身前交叠、单手叉腰、双手叉腰、轻抬手、前伸手势、手靠脸侧、抬头望远、低头沉思、双手轻开。表情：平静、自然微笑、开朗微笑、惊喜、沉思、自信。当前阶段以稳定的骨骼局部旋转和少量表情形变为主；后续第三阶段可在同一 GLB/骨骼接口上增加动作片段、IK、手指和注视控制，而无需改动 VLM 协议。

Filament 由专用 HandlerThread 使用，gltfio 只在首次使用角色时加载 GLB，最多缓存两个角色；每次渲染只临时加入当前角色到 Scene，读回透明 Bitmap 后释放场景资源。最多缓存六张方案预览；没有三个持续运行的渲染循环。光照使用背景稀疏像素估计的亮度/暖色倾向，以及柔和环境光和方向光。实际阴影遮挡、人体与地面空间锚定、VR 和个人形象重建均不在首版内。

## 准备 MNN 和模型包

### 固定原生依赖

在仓库根目录运行：

```bash
bash feature-camera-vlm/scripts/prepare_mnn.sh
```

脚本只克隆官方 tag `3.6.1` 到忽略目录 `feature-camera-vlm/third_party/MNN`，不运行构建，不下载权重。已有目录必须为该 tag，不覆盖用户源码。

CMake 将 MNN 合并构建为共享库，打开 LLM、OpenCV 视觉输入和 IMGCODECS，关闭 HTTP 资源读取及音频。JNI 构建目标为 `arm64-v8a`，使用 CPU 四线程。MNN 原生代码与桥接使用 16 KiB 页对齐链接参数；最终 APK 的所有第三方库与目标设备兼容性仍需本地验证。

### App 内直接下载（主要入口）

1. 在拍照页面打开“模型设置”，找到“Qwen3-VL-2B 离线模型”，点击“下载模型”。无需电脑、Python、ZIP 或自行生成清单。
2. 默认源为 `https://huggingface.co`，目标仓库为 `taobao-mnn/Qwen3-VL-2B-Instruct-MNN`。大陆连接不稳定时可自行填写兼容 Hub API 的 HTTPS 镜像地址；App 不内置或静默切换第三方镜像。
3. 默认仅使用非计费网络；允许移动流量等计费网络前需要确认。通知权限拒绝时仍可在 App 内查看进度。
4. 下载使用 OkHttp 4.12.0 + Kotlin 协程和 `dataSync` 前台服务。获取分页文件清单后固定具体提交，所有文件属于同一版本；跳转由 OkHttp 处理，不允许 HTTPS 降级为 HTTP。
5. 暂停保留已下载文件，继续或重试复用原始源和版本；取消清理本次未激活文件。修改来源需先取消旧任务。页面重建不重复下载；进程被终止后需用户手动点击继续，系统数据同步预算耗尽也暂停。
6. 文件分块写入 `noBackupFilesDir/vlm-models`，不载入完整权重。检查剩余空间、文件大小、LFS SHA-256 或普通 Git blob SHA-1；无摘要的条目只检查大小及内容基础特征，不宣称哈希完整性通过。
7. 下载完成后校验 `config.json` 和所引用配置。相机页面在前台且无构图、拍照或模型管理任务时自动加载；后台完成则返回相机页面后加载。下载成功与原生加载成功是两个不同状态。
8. 显示“离线模型已加载”后选择“强制离线”，保存设置，再断网进行图片构图验收。下载操作不会替用户改变推理模式。

### 官方目录加载和备用导入

MNN 使用实际文件系统中的 `config.json` 作为入口，JNI 调用 `Llm::createLLM(configPath)`、`load()`。官方配置不需要添加 `model_type` 或专用 `manifest.json`。保持模型文件的原始目录结构；视觉外置权重也必须完整下载。

MNN 3.6.1 的 `tie_embeddings` 支持数组和对象：有效配置的 `weight_offset` 非零时从 `llm_weight` 读取共享词嵌入，即使配置含 `embedding_file` 也不必另有该文件；offset 为零或无有效共享配置时仍需独立嵌入文件。CPU、线程和生成预算由现有 JNI 运行时设置，不要求用户修改官方配置。

“导入文件夹”通过系统目录选择器复制官方目录，选择直接包含 `config.json` 的子文件夹；Android 不允许选择部分公共根目录。SAF 的 `content://` 不直接传给 MNN。“导入 ZIP”为备用兼容入口，ZIP 根目录需包含 `config.json`；有旧 `manifest.json` 时额外验证其路径、大小和 SHA-256，无清单的标准目录 ZIP 也可导入。`scripts/package_model.py` 仅保留为可选旧包工具，不是下载或加载的前提。

导入与下载共用目录校验，不在校验阶段替换当前安装。原生候选加载成功后才提交激活指针并清理旧目录；失败保留旧安装、设置和候选文件供重试。为降低峰值内存，加载新模型前卸载旧句柄，失败后旧模型可在下次推理或手动加载时恢复。文件暂存阶段需要同时容纳新旧安装，另保留至少 64 MiB 空间。加载成功仍不代表真实图文推理已验收。

加载、卸载、删除均通过串行原生执行顺序协调。取消发生在可检查的 token 边界；模型加载和图像预填充期间无法保证即时中断，取消后也不会并发释放句柄。最长输出 192 token，使用模型包原有的 mixed sampler；JNI 不强制 greedy，以免部分 ARM CPU 重复同一 token 至上限。

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

云端每次生成/修改最多两次取得模型输出的决策。首轮可以请求能力、校验或渲染；最后一轮必须 FINAL。云端协议错误、几何错误和实际渲染边界会写入工具观察供一次修正；本地只尝试一次，校验失败即结束，不进行渲染图二次审美评审。

- 强制离线：LOCAL。
- 指定云端：只尝试所选供应商，失败直接显示错误。
- 自动大陆：千问 → Seed → 离线；自动海外：GPT → Gemini → 离线。首选设置只能重排区域内顺序。
- 每个失败候选只尝试一次，不做传输重试；成功的云端供应商可以接收第二轮修正，本地供应商不接受自动修正。切换不会重置决策预算。
- 网络故障、超时、限流、未配置及服务故障允许切换；取消、参数错误和内容拒绝不触发规避式切换。
- 所有候选失败或两轮内未提交合法最终方案时保留冻结图和错误，供用户主动重试。

## 本地验收

以下命令仅供用户执行，本次没有代为运行：

```bash
# 先准备 MNN 源码，再构建
./gradlew :app:assembleDebug
# 协议、路由、导演预算与坐标测试
./gradlew :feature-camera-vlm:testDebugUnitTest
# 模型下载恢复、导入、加载事务与会话门控测试：连接设备后执行
./gradlew :feature-camera-vlm:connectedDebugAndroidTest
# 主导航不排队补拍回归
./gradlew :app:connectedDebugAndroidTest
# 网关使用模拟上游，不需要真实供应商密钥
cd services/vlm-gateway
python -m pytest tests
```

新增自动化测试覆盖：模拟 OkHttp 响应的 Range 续传与重写、分页固定版本、取消 socket、摘要损坏；官方目录共享/独立嵌入、Git blob 摘要、路径和空间检查；进程恢复、重复继续、网络计费门控、取消保留旧安装、模拟原生加载失败和成功提交。测试均仅编写，未运行。

真机另需检查：前后台切换和通知进度、系统终止后继续、计费网络切换暂停、镜像可用性、下载后首次加载与断网图片推理。当前没有真实下载、原生加载或断网推理的验收结论。

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
