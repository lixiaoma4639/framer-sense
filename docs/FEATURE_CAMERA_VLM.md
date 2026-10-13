# VLM 相机第一版

## 交付状态

2026-10-09 沉浸式更新：拍摄页隐藏底部导航、状态栏和系统导航栏，支持临时滑动唤出；左上返回退出到进入前的首页／我的，保存期间阻止退出。比例与变焦保持，刘海／手势区域仅给按钮留安全边距。系统返回优先弹窗，随后沿用取消／返回实时页，再退出；App 统一恢复白底黑字和系统栏。补导航、保存门控、返回优先级和窗口恢复测试代码，未运行构建、测试或 APK；见 [布局与取景自测](CAMERA_UI_FRAMING_ACCEPTANCE.md)。


2026-10-09 相机布局与取景接入：默认 CameraX，AR 改为拍照设置主动开启；新增独立快门，拍摄 Tab 仅导航。黑底固定取景区支持 3:4／9:16，设备倍率和双指连续变焦，三卡片常显；角色／样片／要求移入拍照设置，动作／表情／导演修改移入构图详情。偏好与 MVI 模式／配置令牌、相机关闭屏障及 AR JPEG 裁切同步接入。仅补测试代码和静态源码检查，未运行构建、自动化测试或 APK；详见 [布局与取景自测](CAMERA_UI_FRAMING_ACCEPTANCE.md)。本条替代下文旧主页面操作位置描述。


2026-10-08 外观选择：卡通样片／卡通动作新增白种人、亚洲人、黑人三选一，本地记忆，不调用大模型；两个新 GLB 为 Rain 派生初稿。用户确认旧 v2 可以显示；v3 外观与接入按用户要求未运行编译、测试或渲染验证，待用户自测，参见 [样片说明](CAMERA_AR_PHOTO_SAMPLE.md)。

2026-10-08：写实样片未通过用户验收；用户选择动画电影风。现“人像样片验证”已替换为 Rain 卡通角色，支持平静、闭嘴微笑、露齿笑，完整表情烘焙为手机形变。自然站姿为 DRAFT，只供手动验收，未切换自动推荐。手机与摄影质量待用户验收；ARCore、Camera2、新模型协议及其他摄影姿势未实施。源工程、实际对照图与验证见 [样片说明](CAMERA_AR_PHOTO_SAMPLE.md)。

本次提交的是 Android 客户端、MNN JNI 桥接、Rocketbox 骨骼 GLB 人像及可本地启动的 Python 网关代码。已在 Android Debug 包和导入的 Qwen3-VL-2B MNN 模型上验证一次真实离线流程：模型画面分析、三方案校验、人偶投影和预览均完成；不同设备的成功率、延迟与内存仍需继续验收。

2026-10-02：P1、P2 与 P3＋P4 软件接入完成。新增开始/结束扫描、偏爱关键帧、多图选景、共享预算、扫描来源绑定和 OpenCV 背景找回；沿用四角色、动作库、表情与指导一致性。详细行为和真机自测见 [P3＋P4 验收清单](CAMERA_SCAN_P3_P4_ACCEPTANCE.md)。P5 真人实时纠偏、P6 成片评估尚未实现，五个专业样片继续待素材验收。本批未生成 APK，也未运行真机或真实供应商测试。

主 App 拍照入口为 `feature-camera-vlm`，包名 `com.framer.sense.feature.camera.vlm`。保留三个旧相机模块源码，但 App 不再依赖 ONNX v2，也不再在 Application 启动时预加载 ONNX。本次不创建 iOS 工程；JSON 协议、坐标、姿态编号和关节配置可作为后续 iOS 实现依据，Kotlin/Android 渲染和 JNI 本身不是跨平台二进制。

## 从哪里开始

1. 执行下方 MNN 源码准备脚本。Android 构建需要本地 Android SDK 36、JDK 17、NDK 和 CMake 3.22.1。首次源码准备和依赖解析需要网络。
2. 先启动网关，配置至少一个所在区域的图文模型。App 模型设置选择“指定云端”，保存网关地址及**网关访问令牌**。
3. 本地构建安装 Debug 包。在拍照页面授予相机权限，点击“开始扫描”，扫描喜欢的范围后点击“结束扫描”；“单张构图”保留兼容入口，也可使用 Debug 的“测试图片”固定输入。
4. 确认冻结、三方案、姿态预览和应用流程后，再按下文直接在 App 下载离线模型，选择“强制离线”进行断网验收。
5. 最后启用“自动切换”，人为关闭首选供应商或模拟限流，检查实际供应商记录。真实模型成功率需人工检查，不能用测试固定结果替代。

## 目录与责任

| 路径 | 责任 |
| --- | --- |
| `feature-camera-vlm/.../camera` | CameraX 绑定、连续分析、光轴姿态、冻结复制、转正裁剪、MediaStore 保存 |
| `.../scan` | 扫描窗口、关键帧与文件缓存、共享预算、OpenCV 背景验证 |
| `.../model` | JSON 协议、坐标、最终事实 ResolvedPhotoSpec、指导、模型配置和会话输入 |
| `.../data` | QWEN 官方直连、网关适配、区域路由、OkHttp 下载与前台服务、目录/ZIP 导入、MNN 串行推理、加密设置 |
| `.../agent` | 提示词、结构与几何校验、本地单次/云端两轮导演循环、Repository |
| `.../avatar` | 4 个角色、版本化动作/能力目录、受控姿势/完整快照/动画采样、独立脸部示范和预览缓存 |
| `.../ui` | MVI 状态、Intent、Effect、ViewModel、相机和模型设置面板 |
| `.../di` | Hilt 进程级依赖装配 |
| `feature-camera-vlm/src/main/cpp` | CMake 与 MNN JNI 桥接 |
| `feature-camera-vlm/scripts` | 固定版本源码准备、模型 ZIP 打包 |
| `services/vlm-gateway` | FastAPI/HTTPX/Pydantic 单步网关及模拟上游测试 |

## 相机与页面行为

状态为 `LIVE → FREEZING → FROZEN → GENERATING → READY → MODIFYING → LIVE`。冻结使用 CameraX 内存拍摄，得到独立 Bitmap 后立即关闭 ImageProxy；预览和 ImageCapture 使用同一 ViewPort，并统一裁剪及旋转。显示图 JPEG 质量 95，模型输入长边最多 768 像素、JPEG 质量 85。

首次生成前由用户选择成年女性、成年男性、女孩或男孩作为指导人像：在拍照设置中选择，作为后续生成输入；角色身份始终由用户控制，VLM 只产生构图、姿势和表情建议，不能从画面推断或替换角色。三方案卡片等宽显示预览，默认全部可见。选中后可拖动人物；在构图详情中调整大小、选择动作/表情及强度、查看脸部示范或发送文字修改，主操作区提供重新生成。修改只允许选中方案变化。基础校验和实际投影范围都通过后才允许应用；应用恢复实时预览、设置设备支持的倍率并叠加透明人偶参考，真实照片仍由 CameraX 单独保存到 `Pictures/FramerSense`，不合入人偶。生成、重新生成、修改和冻结后的自动生成期间，加载遮罩只实时展示当前真实处理步骤（请求准备、模型分析或切换、结果校验、投影检验、预览渲染），不展示原始输出或步骤历史。

操作指导由 `CompositionGuidance` 读取最终方案，用资源模板分别说明持机者取景、入镜者位置、身体/手臂/头部/站姿和表情。原始模型文字单独显示为场景观察，不再直接指挥各备选方案。去重、拖动、缩放和应用后均重新派生；零表情强度显示平静。显示的“可见参考高度”是画面内人体参考范围，半身/特写不会显示完整人体大于 100% 的高度作为画面占比。旧动作未实际改变腿部重心时，文字不会声称新增的重心动作。

动作和表情修改走 `READY → MODIFYING → READY` 的本地渲染事务，不调用大模型；成功后只替换选中方案并递增 revision，资源或渲染失败保留原方案。生成/修改期间禁用选择。手动选择在切换方案、拖动和缩放后保留；重新生成和“让导演修改”采用新的模型结果重新解析。表情强度在滑块松手后提交，零强度的指导为平静。脸部示范点击后才独立渲染同角色的头部，沿用最终动作、表情及强度，不改变方案的布局与校准。

`ResolvedPhotoSpec` 保存方案 ID/revision、最终布局、动作/角色资源版本、采样、表情及指导；`CompositionPlan.resolvedSpec` 使用 `@Transient`，不进入现有网关 JSON。无解析结果的旧方案继续兼容；布局回传同步旧字段和解析结果，并校验布局、执行数据与指导一致。预览、文字与应用参考共同读取这个最终结果。

`AvatarPreview.appliedAvatar/appliedSpec` 回传真实采用的布局和最终事实，`PlanPreview.appliedPlan` 把它传给导演、UI 和实时参考。渲染校准后再走业务校验；校准标记不进入网关 JSON。拖动/缩放时立即移除对应旧预览，按请求令牌和 revision 原子提交新图与实际布局，迟到结果或错误不能覆盖新版本。

保留底部蓝色“拍摄”和横屏侧栏导航。冻结、生成、修改、模型管理和保存期间禁止拍摄；不排队补拍。相机默认自动曝光、白平衡、对焦，支持点击对焦测光。`1×/2×/3×` 是设备 zoom ratio，不承诺对应独立光学镜头，也不能由单张冻结图推断精确焦距、人物距离或安全站位。

ViewModel 持有冻结会话；横竖屏切换保留图片和方案。界面被移除时取消平台关联工作，正在生成的会话保留冻结图供重试。画面编号、请求令牌和方案 revision 防止迟到结果覆盖。进程重建只恢复“需要重新构图”提示，不恢复不存在的 Bitmap 或旧任务。

Debug 原始输出可在“调试详情”中展开，也会按用户排障需求打印到 `VlmOffline` 的“模型原始输出”日志：包含 request、part 和 raw，raw 是 JSON 转义字符串，按分段顺序拼接可还原换行和控制字符。`VlmDirector` 的“解析诊断”包含具体解析错误；这两类可能含模型原文的日志仅在 Debug 输出。原生“MNN 生成结束”同时记录结束状态、输入/输出 token 数及视觉编码、预填充、解码耗时；status=1 为模型输出结束标记，2 为分步生成达到本次 token 数，3 为取消，4 为内部错误，5 为超时。短场景观察达到预算时会额外记录“交由离线协议解析已生成文本”，不会直接判定为推理失败。正式产品界面只显示模型与耗时、方案解释和错误提示，不展示原始对话；Release 日志不记录模型原文。日志不直接打印网关令牌、用户提示词或图片路径。

离线扫描排障按 `VlmOffline / VlmScan / VlmCamera / VlmDirector` 连续查看：`VlmOffline` 记录各编号输入的尺寸和字节数、JNI 进入与推理返回，Debug 的“离线选景模型原始输出”包含 request、part 和转义 raw；推理返回标注 `awaitingProtocolValidation=true`，不代表选景成功。`VlmScan` 记录 scan/token、编号与帧 ID 对应、解析成功或具体失败原因、迟到结果丢弃、冻结来源存在性及失败后的界面状态。`VlmCamera` 记录生成步骤、预览生成与 READY 提交。当前离线协议要求一个有效数字，兼容旧 SELECT 行及说明字段；SUBJECT/REASON 缺失可接受，不增加模型调用。排障时保留同一次任务从提交到失败或 READY 的完整日志，编号原文日志仅 Debug 输出。

离线加载和视觉编码不设固定总时限；JNI 每次实际写出 token 都通知停滞监控。取消标记在视觉编码、语言预填充及解码的计算图算子边界检查，通过官方 `CALL_BACK_STOP` 路径退出，不强行终止正在执行的单个算子，也不并发释放模型；原生推理尚未退出时，重复请求立即返回 `LOCAL_BUSY`。加载失败、推理失败和停滞超时显示不同提示，不再统一报“加载失败”。仍保留云端构图最多两轮；本地构图子任务最多一次模型调用、每次最多 96 个生成 token。扫描离线选景另执行一次真实调用；离线推理不读取或消耗云端会话预算，用户重复分析、生成及修改没有会话累计次数上限。

离线构图与自动路由中的本地构图候选都只调用一次模型，结构或投影校验失败后立即结束，保留冻结图供用户主动重试；有效的本地方案通过所有校验后直接提交，不再为了补 `FINAL` 额外调用模型。云端构图保留最多两轮。

离线默认使用一句极短的场景观察协议，模型在 50 字内描述可见环境、人物远中近、景别和位置，以及身体、手臂、头部与表情建议；客户端从中文线索生成三条彼此不同、标记为“场景分析备选”的受控姿势意图，再继续执行映射、几何和真实投影校验。首张方案优先采用模型明确提到的景别、方位和姿势，另两张在相同场景语义内安全扩展。正式界面仅展示选中方案的模型依据和实际应用后的中文构图参数。旧的逐行意图与完整数组协议仍严格兼容。达到本地输出预算时，JNI 会把已生成文本交给该回退解析，而不是把可用场景观察直接丢弃为 `LOCAL_OUTPUT_LIMIT`。空输出、提示词复述和无场景内容仍明确失败，绝不伪造与图片无关的建议。

短提示词不再携带上次输出、重复校验历史或长字段示例。原生保留模型包的 mixed sampler；达到 96 token 预算但仍有文本时，客户端先尝试按场景观察解析。取消后的输出不会再记录为成功。首 token 不限时和输出停滞 39 秒规则保留，96 token 是输出预算，不是完成时长承诺。

## 公共协议与坐标

模型输出的是摄影意图；最终 `CompositionPlan` 由客户端映射并校验。云端优先使用下列 JSON；离线默认只返回短场景观察，并由客户端生成安全意图：

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

上例仅解释一项字段；初次生成要求恰好三条意图，修改提示词要求只返回选中 ID 的一条替换意图，客户端兼容旧三条响应但保留未选中方案。景别包括 `ENVIRONMENT/FULL/HALF/CLOSE_UP`，方位为 `LEFT/CENTER/RIGHT`，朝向为 `FRONT/THREE_QUARTER_LEFT/THREE_QUARTER_RIGHT`，其余枚举以 `CompositionModels.kt` 与网关 `models.py` 为准。用户在拍摄前选择的人偶角色不属于模型协议，映射时始终保留。

- 云端意图可额外携带 `poseDirective`（身体、手臂、头部、站姿）和 `subjectDistance`（远中近）；旧响应没有它们时由旧姿态编号安全转换。
- App 根据景别、远中近、方位和设备限制生成裁剪、倍率、脚点和人偶高度；环境人像限制在画面高度约 14%–30%，全身限制在约 30%–58%，再由透明像素投影检查异常 GLB 包围盒并只向下缩小一次。模型提出的方位不会直接成为未校验的像素坐标。
- 原点在左上角，x 向右、y 向下。crop 使用冻结原图归一化坐标。
- avatar 使用**裁剪后的方案画面**坐标。foot 是脚底，height 是整个人偶的投影高度；半身和特写通过真实裁切实现，脚可以在画面外。映射按半身 40%、特写 68% 的身体裁切比例反推完整高度和脚底；渲染缩小同步调整脚底以保持裁切比例，避免方案自身无法通过景别校验。
- 原图坐标：`originalX = crop.left + planX × crop.width`，y 同理。`PlanCoordinates` 提供双向换算。
- 首版保持原画幅，所以归一化 crop 的宽高跨度相同。同机位居中变焦满足 `zoom = currentZoom / crop.width`。
- 偏心构图、向外扩大视场或移动机位需标记 `needsRetake=true`。冻结图只能展示已有图像范围，不能预览画面外新增内容。
- 若三条意图完全重复或不适合安全渲染，映射器优先保留景别、姿势和表情，只替换方位或朝向到最近未占用组合；最终卡片显示的是实际映射后的方案。校验有限数值、裁剪范围、设备倍率、姿态枚举、景别对应的人体裁切和修改范围。Filament 工具额外校验真实头脚、手臂等投影是否出画。上述检查不等同于审美或地面安全检查。

## 人偶渲染

使用版本目录中固定的 Filament 1.75.1，依赖 `filament-android`、`filamat-android` 和 `gltfio-android`。内置的 `src/main/assets/vlm_avatars/` 含四个由 Microsoft Rocketbox（MIT）导出的 GLB：成人女、成人男、女孩和男孩；资产来源与授权记录在该目录的 `NOTICE.md`。GLB 保留真实网格、纹理、蒙皮骨骼与 ARKit BlendShape，不再以球体或胶囊体拼接人形。

姿态：自然站立、侧身重心、回眸、身前交叠、单手叉腰、双手叉腰、轻抬手、前伸手势、手靠脸侧、抬头望远、低头沉思、双手轻开。细分指令由身体朝向、手臂动作、头部方向、轻微站姿和表情组成；每项都限制为 Rocketbox 骨骼局部旋转。执行时验证当前动作必需骨骼和正权重表情通道，缺失时明确报错，不再静默跳过；渲染无透明人像像素时也明确失败。表情：平静、自然微笑、开朗微笑、惊喜、沉思、自信。通道使用实际 GLB 名称，例如 `AK_44_MouthSmileLeft` 和 `AK_45_MouthSmileRight`，无 FBX 前缀且左右编号不同。生产内容目前仍以基础骨骼局部旋转和少量表情形变为主，专业摄影质量、手指和注视控制待真实素材审核；软件已支持完整姿态快照和动画静帧采样。

Filament 由专用 HandlerThread 使用，gltfio 只在首次使用角色时加载 GLB，最多缓存两个角色资源；每次渲染只临时加入当前角色到 Scene，读回透明 Bitmap 后释放场景资源。预览缓存最多六个键，请求值与校准值可共享同一张图，键包含动作版本、角色资源版本、执行采样、渲染用途及 App 内校准标记。脸部示范单独缓存最多两张；没有持续运行的动画循环。光照使用背景稀疏像素估计的亮度/暖色倾向，以及柔和环境光和方向光。实际阴影遮挡、人体与地面空间锚定、VR 和个人形象重建均不在首版内。

`PhotoActionCatalog` 为动作提供稳定 ID/版本、骨架、支持角色/景别、环境条件、执行数据和本地化步骤。实际 GLB 骨骼、形变、动画及快照参与能力筛选；资源缺失、骨架不匹配或座椅/墙面/道具条件未确认时不可选。模型只能提出结构化动作语义，不能选择任意资源；自动推荐只采用当前角色实际可执行且语义匹配的审核动作。五个专业样片登记为 `AWAITING_ASSET`，不会显示为正式动作。

渲染前恢复完整绑定局部变换并清零表情，再应用受控姿势、完整快照或动画指定时刻，随后应用整体布局与最终表情，最后更新蒙皮矩阵。动画中的面部权重由最终表情覆盖，不新增持续播放。接口和素材交付/授权/审核规范见 [动作素材接入与验收](CAMERA_POSE_ASSET_INTEGRATION.md)。测试用两关节 GLB 仅放在 `androidTest/assets`，不进入正式 App，也不作为专业样片。

## 准备 MNN 和模型包

### 固定原生依赖

在仓库根目录运行：

```bash
bash feature-camera-vlm/scripts/prepare_mnn.sh
```

脚本只克隆官方 tag `3.6.1` 到忽略目录 `feature-camera-vlm/third_party/MNN`，不运行构建，不下载权重。已有目录必须为该 tag，不覆盖用户源码。CMake 使用 Git 将 `patches/mnn-3.6.1-cancellation.patch` 应用到构建目录中的四个副本；官方 checkout 不被修改。所有 LLM 实现与 JNI 使用同一份生成头文件，两个实现源文件由 `llm` 目标替换。补丁应用通过 `GIT_CEILING_DIRECTORIES` 隔离父仓库的子目录过滤，不依赖 Git 返回码判断成功；四个副本必须与原始内容的 SHA-256 不同，并包含新增接口/实现，否则配置明确失败，不静默退回不可取消版本。

CMake 将 MNN 合并构建为共享库，打开 LLM、OpenCV 视觉输入和 IMGCODECS，关闭 HTTP 资源读取及音频。JNI 构建目标为 `arm64-v8a`，使用 CPU 四线程。MNN 原生代码与桥接使用 16 KiB 页对齐链接参数；最终 APK 的所有第三方库与目标设备兼容性仍需本地验证。

### App 内直接下载（主要入口）

1. 在拍照页面打开“模型设置”，找到“Qwen3-VL-2B 离线模型”，点击“下载模型”。无需电脑、Python、ZIP 或自行生成清单。
2. 默认源为 `https://huggingface.co`，目标仓库为 `taobao-mnn/Qwen3-VL-2B-Instruct-MNN`。大陆连接不稳定时可自行填写兼容 Hub API 的 HTTPS 镜像地址；App 不内置或静默切换第三方镜像。
3. 默认仅使用非计费网络；允许移动流量等计费网络前需要确认。通知权限拒绝时仍可在 App 内查看进度。
4. 下载使用 OkHttp 4.12.0 + Kotlin 协程和 `dataSync` 前台服务。获取分页文件清单后固定具体提交，所有文件属于同一版本；跳转由 OkHttp 处理，不允许 HTTPS 降级为 HTTP。
5. 暂停保留已下载文件，继续或重试复用原始源和版本；取消清理本次未激活文件。修改来源需先取消旧任务。页面重建不重复下载；进程被终止后需用户手动点击继续，系统数据同步预算耗尽也暂停。
6. 文件分块写入 `noBackupFilesDir/vlm-models`，不载入完整权重。检查剩余空间、文件大小、LFS SHA-256 或普通 Git blob SHA-1；无摘要的条目只检查大小及内容基础特征，不宣称哈希完整性通过。
7. 下载完成后校验 `config.json` 和所引用配置。App 启动进入首页后异步预加载已激活的本地模型，进程内只请求一次，不自动下载；`MnnProvider` 为 Hilt 单例，同一路径复用原生句柄，Tab／Activity 重建不会重复初始化。拍照页订阅进程共享加载状态，已加载则跳过页面加载；下载的新候选仍在相机页面前台空闲时加载并激活，后台完成则返回后处理。失败不因切换 Tab 循环重试，手动卸载后不会被首页再次加载。下载成功与原生加载成功是两个不同状态。
8. 显示“离线模型已加载”后选择“强制离线”，保存设置，再断网进行图片构图验收。下载操作不会替用户改变推理模式。

### 官方目录加载和备用导入

MNN 使用实际文件系统中的 `config.json` 作为入口，JNI 调用 `Llm::createLLM(configPath)`、`load()`。官方配置不需要添加 `model_type` 或专用 `manifest.json`。保持模型文件的原始目录结构；视觉外置权重也必须完整下载。

MNN 3.6.1 的 `tie_embeddings` 支持数组和对象：有效配置的 `weight_offset` 非零时从 `llm_weight` 读取共享词嵌入，即使配置含 `embedding_file` 也不必另有该文件；offset 为零或无有效共享配置时仍需独立嵌入文件。CPU、线程和生成预算由现有 JNI 运行时设置，不要求用户修改官方配置。

“导入文件夹”通过系统目录选择器复制官方目录，选择直接包含 `config.json` 的子文件夹；Android 不允许选择部分公共根目录。SAF 的 `content://` 不直接传给 MNN。“导入 ZIP”为备用兼容入口，ZIP 根目录需包含 `config.json`；有旧 `manifest.json` 时额外验证其路径、大小和 SHA-256，无清单的标准目录 ZIP 也可导入。`scripts/package_model.py` 仅保留为可选旧包工具，不是下载或加载的前提。

导入与下载共用目录校验，不在校验阶段替换当前安装。原生候选加载成功后才提交激活指针并清理旧目录；失败保留旧安装、设置和候选文件供重试。为降低峰值内存，加载新模型前卸载旧句柄，失败后旧模型可在下次推理或手动加载时恢复。文件暂存阶段需要同时容纳新旧安装，另保留至少 64 MiB 空间。加载成功仍不代表真实图文推理已验收。

加载、卸载、删除均通过串行原生执行顺序协调。取消使用官方 before/after 算子回调；加载前为视觉和语言 RuntimeManager 启用 `Session_Debug`，确保图创建时就包含回调信息。回调只在受控计算图内中止，图片解码、输入预处理及加载仍在安全阶段交接，单个正在执行的算子不被强杀。视觉层检查空输出并传播 `USER_CANCEL`；中止后清空视觉/深堆栈/位置及输出引用，设置全部 KV 移除并恢复可请求状态。CPU attention 在下一次预填充重新分配各层 KV，保留加载的权重与模型实例，不复用部分计算结果。取消后仍等待串行屏障，不并发释放句柄。最长输出 96 token，使用模型包原有的 mixed sampler；JNI 不强制 greedy，以免部分 ARM CPU 重复同一 token 至上限。

JNI 使用 MNN Omni 的真实 `<img>私有图片路径</img>` 视觉解析入口；该入口会读取图片并运行视觉编码器。用户提示词中的额外媒体标签会被转义。强制离线只有 `LOCAL` 候选，不创建网关请求；缺模型、错误 ABI、加载或推理失败均返回明确错误，不生成假方案。

强制离线扫描选景使用 `OfflineScanViewAgent → MnnProvider.compareViews → MnnNative.inferImages`，最多 4 张代表帧独立进入一次多图推理，模型选出一个最佳背景后自动冻结并生成三构图。图片先复制缩小为长边最多 448 的私有 JPEG；取消后等待原生退出才清理副本。短输出协议严格绑定图片编号，不伪造区域或规则选景。4 张为当前手机应用上限（2026-10-06 从八张恢复），并非模型硬上限；正常流程共两次推理、零上传，云端仍最多 8 张。详见联合验收清单中的离线多图接入记录。

2026-10-06 离线选景使用受限数字选择：JNI 校验模型的数字 tokenizer 映射，一次视觉预填充后只比较本次 1..N 的真实下一词分数，返回最高项的数字，不再自由生成 SELECT 字段。取消、异常分数或输出结构不兼容时明确失败，保留扫描图；不默认首帧，不自动重跑视觉推理。单行及完整未填充序列可定位最后位置，含不明确填充/分块布局的模型需另行适配。`VlmOffline` 新增 `MNN 编号输出检查` 和 `MNN 编号选择结束 method=constrained_logits`，记录各编号分数及 selected；此分支 generatedTokens=0，旧“生成结束”的 token 日志继续用于单图构图。

## 网关与云端

运行说明和配置示例见 [`services/vlm-gateway/README.md`](../services/vlm-gateway/README.md)。接口为：

| 接口 | 行为 |
| --- | --- |
| `GET /health` | 存活检查，不需令牌 |
| `GET /v1/capabilities` | 已配置供应商及服务端模型 ID，需要网关令牌 |
| `POST /v1/director/step` | 单步图文请求，返回 model/raw/decision/elapsedMs，需要网关令牌 |

网关接入模式的供应商 API Key 与模型 ID 仍由服务端配置；QWEN 新增 Android 官方直连方式，用户自行填写 API Key、官方 Base URL 和视觉模型 ID。网关令牌与 QWEN Key 分开用 Android Keystore AES-GCM 加密保存；QWEN Key 不进入序列化配置 JSON、不写入日志。Debug manifest 允许本地网关 HTTP；Release 网关和所有官方直连请求使用 HTTPS。

四家适配使用各自实际图像通道：千问/Seed Chat Completions `image_url`，GPT Responses `input_image` 和严格 JSON Schema，Gemini `inlineData` 和 `responseJsonSchema`。千问启用 JSON Object；Seed 按提示词生成 JSON，由公共协议校验，不能假定所有模型支持同一种严格结构化输出。

### 云端模型统一配置（2026-10-13）

所有云端默认地址、模型 ID、视觉请求路径及客户端/网关业务路径统一维护在 [`config/cloud-models.json`](../config/cloud-models.json)，Android 和 Python 网关共用这一份文件，调用代码不再保存这些固定值。

- `providers.qwen/seed/gpt/gemini.base_url`：供应商 Base URL；`model`：默认视觉模型 ID；`vision_path`：追加到 Base URL 的请求路径，以 `/` 开头。Gemini 使用 `{model}` 占位符，实际模型 ID 会安全编码。
- `providers.qwen.allowed_hosts/workspace_host_suffix`：官方直连的域名限制，保留百炼官方域名检查和禁止重定向。当前默认地址保留用户已填写到源码中的北京业务空间地址；可在同一配置中改为账号对应的官方地址。
- `gateway.base_url`：App 默认网关地址；其余 `*_path` 同时控制 App 请求路径和 FastAPI 路由，修改后必须同步更新 App 与网关。QWEN 和 Seed 支持 App 官方直连并保留网关入口；GPT/Gemini 仍经网关。
- QWEN 与 Seed 的默认模型沿用原配置示例；GPT/Gemini 的默认模型为空，需填账号已开通且支持视觉的模型。网关无 `*_MODEL` 环境变量时使用此文件的默认模型，存在显式空环境变量时保持未配置状态；多图能力仍需按原规则额外声明。

Android 在 Gradle 配置时读取 JSON 并生成 BuildConfig，由 `CloudModelConfig` 提供访问入口。QWEN 官方直连的 Base URL 和视觉模型 ID 输入栏只读展示当前 JSON 值；读取旧设置、打开设置和保存设置时均覆盖旧地址/模型，保留用户 API Key、网关令牌和其他选项，确保检查与实际请求使用同一配置。修改文件后同步并重新构建安装，无需清除应用数据。手机网关地址仍可修改并保存。Python 网关启动时从仓库读取同一文件，修改后重启；独立部署需保留仓库相对目录结构及该文件。部署环境 `*_BASE_URL`/`*_MODEL` 优先，已有 `.env` 中旧值会继续覆盖统一默认值。

此文件只存公开配置，不填写 API Key 或网关令牌。设备 Key 仍在界面填写并加密保存；网关 Key/令牌仍用环境变量。路径配置只适用于现有协议：QWEN/Seed 为 Chat Completions、GPT 为 Responses、Gemini 为 generateContent，不能仅将 QWEN 路径改为 DashScope 原生路径就切换协议，需要另外适配请求和响应结构。

已新增共享默认值/环境覆盖、实际请求路径和 Gemini 模型路径转义测试代码，更新 QWEN 地址检查测试；本轮只做静态检查，未运行自动化测试。

### 模型使用日志（2026-10-13）

云端选景对 1–8 张图使用同一完整 JSON 示例，明确所有必填字段、原始 scanId/frameId、空数组、非空背景主体/理由和归一化区域；尚未入镜的人物不作为必须存在的主体。协议检查支持完整 JSON 外层代码围栏，仍严格校验帧绑定及区域，不放宽非法坐标或补造缺失的选景结果。校验失败日志新增安全 `reason`（如 `MISSING_CANDIDATE_FIELD_uncertainties`、`EMPTY_VIEW_SUBJECT`、`INVALID_VIEW_FRAME`、`INVALID_VIEW_REGION`），下一轮提示使用具体原因修正，不再统一误称帧 ID 错误。现有日志无法确定历史失败属于哪种输出错误，也不存在“少于八帧不可用”的规则。

同一扫描最多两次选景、六次云端调用及 12 MiB 上传是 App 自己的调用保护，与云端账号余额或 Token 额度无关。两次选景用完后的主动重试直接返回 `SELECTION_ATTEMPTS_EXHAUSTED`，界面明确解释 App 限制，且不再查询能力或上传图片；其他预算限制统一返回 `ModelFailure`，避免裸 `IllegalStateException`。重新扫描建立新预算，人工选帧/离线仍可继续，失败不退还或重置预算。已新增 1/6/8 帧协议、围栏兼容、具体校验原因和重试限制测试代码，未执行自动化测试或真实云端调用。

在 Logcat 搜索 `模型使用`；标签 `VlmScan` 对应配置检查与选景，`VlmDirector` 对应构图调用及校验后的任务结果，`VlmCamera` 对应最终预览界面结果。统一字段 `source=云端/离线`、`provider=QWEN/SEED/GPT/GEMINI/LOCAL`、`access=官方直连/项目网关/本机`、`model` 和 `modelSource` 标明本次实际执行的模型。

`result=开始/返回成功/成功/失败/取消/切换/转人工选景` 区分调用、任务和路由结果。`返回成功` 仅表示适配器返回，仍需校验；只有合法选景、导演完整校验通过或最终预览就绪时，对应任务/界面阶段记录 `成功`。配置检查只是文本请求，日志带 `visionVerified=false`，不代表视觉任务已验收。能力声明记录 `actualCallSucceeded=false`，不能当成云端调用成功。自动模式记录切换的来源和目标，最终结果记录最后实际成功的供应商；云端选景降级当前为人工选景，不伪称已调用离线模型。

QWEN 官方直连开始时标记配置模型；网关构图开始前模型 ID 为待返回，因为部署环境可能覆盖 JSON。能力接口返回的模型标记为 `能力声明`，调用返回后的模型标记为 `实际返回`；离线也记录实际返回的模型。失败日志包含稳定 `code`、可用的 `http` 与百炼 `upstream`，不新增密钥、地址、照片、提示词、错误正文或模型原文日志；模型标识拒绝密钥形态、URL 和换行注入。日志出口可注入，JVM 测试不依赖 Android Logcat。

已补充自动切换后实际云端/离线结果、非法选景不能报成功与日志脱敏测试代码；未运行 Gradle/Android 测试。

### QWEN 官方直连配置（2026-10-13）

适用于只有百炼官方 API Key、不运行项目网关的个人设备。无需下载云端模型，不使用 LangChain 或第三方聚合平台。首次模型模式仍为强制离线；使用云端时主动切换。

1. 打开拍照页模型设置，选择“指定云端 → CN → QWEN → 官方直连”。
2. 在 `config/cloud-models.json` 的 `providers.qwen.base_url` 配置地址，App 输入栏只读展示它；当前保留北京业务空间地址。北京通用地址可设为 `https://dashscope.aliyuncs.com/compatible-mode/v1`，新加坡可设为 `https://dashscope-intl.aliyuncs.com/compatible-mode/v1`；官方业务空间地址如 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1` 也可使用。替换真实业务空间 ID，地址只到 `/compatible-mode/v1`，不能再追加 `/chat/completions`。修改 JSON 后重新构建安装，在 App 填写同地域的百炼 API Key。仅允许百炼官方 HTTPS 域名，不发送 Key 到第三方地址或重定向目标。
3. 视觉模型 ID 由 `providers.qwen.model` 配置，App 输入栏只读展示它，当前为 `qwen3-vl-plus`。需具备当前地域的模型调用权限，支持图片与非思考 JSON Object 输出。其他视觉模型由用户在 JSON 中配置并验收，不把文本模型视为已验证视觉模型。
4. 保存后重新打开设置，点击“检查已保存 QWEN 配置”。该按钮发送一次少量 Token 的文本请求，验证鉴权及当前模型调用，不上传图片，不计入扫描会话预算；成功后先验收单张构图，再验收扫描选景和应用参考。

`QwenOfficialProvider` 同时实现 `VisionModelProvider` 和 `MultiViewProvider`，由 `CloudProviders` 分配。直接调用 `/chat/completions`，设置 `enable_thinking=false`、`response_format=json_object`，图像以 JPEG Base64 的 `image_url` 通道输入；每张扫描图附带原始 frameId，不允许模型返回未提交帧。摄影参考保持 v2 意图与实际目录校验；普通构图保持原导演协议。扫描最多八张/3 MiB，最多两次选景调用，和后续构图共享每扫描六次/12 MiB 预算；官方直连不查询项目网关能力接口。每个供应商分别查询能力，网关不可用不隐藏可用的官方 QWEN 候选。取消调用实际 `Call.cancel()`，无传输自动重试；连接超时 10 秒，读取和总请求超时 180 秒。

地址、鉴权、模型权限、限流、请求参数、内容拒绝、无效结果和网络错误分别用本地化提示；上游错误正文不进入异常、日志或界面。非法选择保留原扫描并交有限修正，不伪造背景。Key 为空时不能保存指定云端的官方 QWEN 配置；自动模式可以保留未配置供应商并按既有规则降级。

403 排障：兼容嵌套 `error.code`、顶层 `code` 和 `error.type`。仅保留 HTTP 状态和白名单中已识别的百炼错误码，页面展示本地化原因，选景/构图日志增加 `http` 与 `upstream`。`Arrearage` 指向账号欠费/状态，`AllocationQuota.FreeTierOnly` 指向免费额度用完即停，`AccessDenied.Unpurchased` 指向服务开通资格，`Model.AccessDenied` 和 `Workspace.AccessDenied` 分别指向模型与业务空间授权。未知错误码不回显，不能仅凭 HTTP 403 断言欠费或 Key 错误。仍不保存/打印上游 `message`、响应正文或 Key，不自动修改计费设置或切换模型规避内容拒绝。官方解释见[百炼错误码](https://help.aliyun.com/zh/model-studio/error-code)。

已有设置若保存了网关令牌且没有 `qwenAccess` 字段，则继续使用“自有网关”；其他旧用户与新用户默认“官方直连”。手动切换接入方式保留隐藏字段；Seed 另有独立官方接入，GPT/Gemini 仍走网关。设置保存和恢复使用真实 Keystore；恢复失败清空对应密钥，需用户重新填写。

阿里云百炼控制台：[北京地域模型广场](https://bailian.console.aliyun.com/cn-beijing/model/market)。

官方资料：[获取 API Key](https://help.aliyun.com/zh/model-studio/get-api-key/)、[Base URL](https://help.aliyun.com/zh/model-studio/base-url)、[视觉理解](https://help.aliyun.com/zh/model-studio/vision)、[Chat 接口](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions/)、[JSON 输出](https://help.aliyun.com/zh/model-studio/qwen-structured-output)。

已补充官方地址与图片协议、错误脱敏、预算、取消、扫描能力混合、设置加密/迁移及界面回归测试代码；按仓库约定本轮未运行 Gradle/Android 测试，未请求真实计费模型。真实 Key 与设备视觉质量由用户验收。

### Seed 官方直连配置

1. 在方舟控制台开通视觉模型并创建 API Key。共享 JSON 的 Seed 默认模型为 `doubao-seed-2-1-lite-260915`，地址为北京方舟，路径为 Chat Completions；若使用已开通的模型/接入点，在 JSON 中修改 Seed 项后重新构建安装。
2. App 模型设置选择“指定云端 → CN → SEED → 官方直连”，地址和模型 ID 只读展示 JSON 值；填写独立的方舟 API Key，保存后重新打开，点击“检查已保存 Seed 配置”。检查仅发少量文本，不上传照片；成功后再验收单张构图及 1／6／8 帧扫描。
3. Seed 复用原云端 Agent、选景协议、校验、预算和取消；独立适配 `thinking.type=disabled` 与 JSON Object。默认离线模式、QWEN 配置及协议、MNN 推理保持原样。三个密钥分开加密，不进公开 JSON；旧设置缺少 `seedAccess` 且有网关令牌时保留 Seed 网关方式，其他默认官方直连。自动模式展示两家独立配置并保留原路由顺序。

官方资料：[模型列表](https://docs.volcengine.com/docs/ark/model-list?lang=zh)、[Base URL 与鉴权](https://docs.volcengine.com/docs/ark/base-url-and-authentication?lang=zh)、[Chat API](https://docs.volcengine.com/docs/ark/chat-api?lang=zh)、[图片理解](https://docs.volcengine.com/docs/ark/image-understanding?lang=zh)、[结构化输出](https://docs.volcengine.com/docs/ark/structured-output-beta?lang=zh)。已补充模拟协议、预算、取消、密钥及界面回归测试，未运行 Gradle/Android 测试或真实计费调用；验收时先确认既有离线与 QWEN，再确认 Seed。

## 导演与自动切换

云端每次生成/修改最多两次取得模型输出的决策。首轮可以请求能力、校验或渲染；最后一轮必须 FINAL。云端协议错误、几何错误和实际渲染边界会写入工具观察供一次修正；本地只尝试一次，校验失败即结束，不进行渲染图二次审美评审。

- 强制离线：LOCAL。
- 指定云端：只尝试所选供应商，失败直接显示错误。
- 自动大陆：千问 → Seed → 离线；自动海外：GPT → Gemini → 离线。首选设置只能重排区域内顺序。
- 每个失败候选只尝试一次，不做传输重试；成功的云端供应商可以接收第二轮修正，本地供应商不接受自动修正。切换不会重置决策预算。
- 网络故障、超时、限流、未配置及服务故障允许切换；取消、参数错误和内容拒绝不触发规避式切换。
- 所有候选失败或两轮内未提交合法最终方案时保留冻结图和错误，供用户主动重试。

## 本地验收

P1 历史验证（此前批次已执行打包）：

```bash
./gradlew :feature-camera-vlm:testDebugUnitTest :feature-camera-vlm:compileDebugAndroidTestKotlin :app:assembleDebug :feature-camera-vlm:lintDebug --offline --console=plain
```

结果：62 项 JVM 测试全部通过，设备测试编译通过，Debug APK 构建成功，模块 Lint 0 错误、16 项非阻断警告（KTX 建议、ARM64 ABI、存储空间 API 等）。已修复基线暴露的几何/修改兼容问题、设备测试导入错误和 Compose 资源读取错误；旧协议测试断言同步到现行意图/短场景观察协议。未运行设备仪器测试及真实云端/离线推理。

P2 本批按用户授权执行下列验证，未执行 APK 打包任务：

```bash
./gradlew :feature-camera-vlm:testDebugUnitTest :feature-camera-vlm:compileDebugAndroidTestKotlin :app:compileDebugJavaWithJavac :feature-camera-vlm:lintDebug --offline --console=plain
```

结果：累计 73 项 JVM 测试全部通过，无失败或跳过；主 App 与设备测试代码编译通过；模块 Lint 0 错误、16 项非阻断警告。P2 新增能力筛选、旧方案兼容、版本变化、完整快照/动画采样及最终指导一致性回归。设备测试还覆盖仅选中方案修改、无需模型调用、失败保留原方案、迟到脸部示范，以及专用最小动画/快照资产的真实 GPU 切换重置；本批只编译这些设备测试，未运行。

P2 手工验收：先生成三方案，在选中方案中切换动作、表情及强度，确认只有当前卡片变化且不触发模型请求；打开脸部示范，比对角色、表情和强度。拖动/缩放后切换到另一方案再回来，确认选择保留；快速切换与返回后台检查旧示范不会覆盖新方案。应用参考时核对动作、表情、位置与校准比例；重新生成/导演修改应采用新的模型结果。四角色与环境/全身/半身/特写分别验收。专业样片和商业摄影质量另行审核。

以下为后续本地验收命令：

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

JVM 测试覆盖协议、导演预算、坐标、下载续传和完整性、真实 GLB 蒙皮骨骼/表情通道、方案指导、所有景别与远中近映射、校准后裁切、渲染参数回传与重新校验。设备测试覆盖模型导入/加载事务/会话门控，以及本轮新增的校准缓存、迟到预览与应用同步、Compose 指导更新和四角色真实 GPU 的平静/微笑差异与表情重置；设备测试仅编译通过，尚未运行。

P1 手工验收：固定同一测试图片，分别生成四角色方案，检查姿势/表情与文字；切换备选，确认共用模型观察但动作指导不同；拖动人物和连续缩放，检查位置/大小文字与最新预览同步；文字修改只更新选中方案；应用后实时参考的比例和指导与卡片一致。全身保留头脚，半身/特写保留头部并按身体部位裁切。表情是否足够明显、动作是否自然及左右是否正确需要真机人工确认。

真机另需检查：前后台切换和通知进度、系统终止后继续、计费网络切换暂停、镜像可用性、下载后首次加载与断网图片推理。当前没有真实下载、原生加载或断网推理的验收结论。

设备验收记录至少包含：机型/Android/ABI、模型包与量化设置、实际供应商与模型 ID、冻结到三方案耗时、进程峰值内存、错误与修正记录。建议固定同一古建筑、室内、街景三张图，分别测试离线与所在区域两家云端。

必须人工检查三方案是否有区别、动作是否容易模仿、人物比例是否合理，以及地面/遮挡等不确定项是否诚实说明。另检查横竖屏、后台返回、取消后迟到结果、连续点击、全身头脚完整、半身裁切、参考应用后倍率、系统相册保存无虚拟人偶。离线真机验收应实际断网，并确认未出现云端调用。

## 实现依据

- [MNN 3.6.1 发布与源码](https://github.com/alibaba/MNN/releases/tag/3.6.1)
- [MNN 3.6.1 LLM 接口](https://github.com/alibaba/MNN/blob/3.6.1/transformers/llm/engine/include/llm/llm.hpp)
- [MNN Omni 图像解析](https://github.com/alibaba/MNN/blob/3.6.1/transformers/llm/engine/src/omni.cpp)
- [MNN 3.6.1 导出工具](https://github.com/alibaba/MNN/blob/3.6.1/transformers/llm/export/llmexport.py)
- [Filament 1.75.1](https://github.com/google/filament/tree/v1.75.1)
- [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)
- [Gemini 结构化输出](https://ai.google.dev/gemini-api/docs/structured-output)

## P3＋P4 补充

扫描状态为 `LIVE → SCANNING → SCAN_PROCESSING → VIEW_SELECTING → GENERATING → READY → LIVE`，中断或多图不可用时进入 `SCAN_REVIEW`，由用户明确继续分析或选择背景。采集每秒最多 4 帧、32 帧、3 偏爱、32 MiB、90 秒；时间/字节上限只暂停新增。旋转重绑建立配置片段，后台停止采集并保留有效帧。应用扫描方案后进入背景找回，确认背景再展示 P2 人偶和人物指导，真实手动拍照仍可用。

`PhotoSessionController` 管理文件、特征与云端共同预算，`ScanCollector` 处理窗口/资源/轨迹，`ScanViewAgent` 管理多图代表帧与有限修正；`DirectorAgent` 继续负责主视角三构图。`PhotoViewBinding` 不进入 v1 JSON，但进入最终事实和缓存，拒绝跨背景引用。每扫描云端最多 6 次模型请求、12 MiB 上传，云端选景累计最多两次；离线选景与构图不受这些累计限制，也不消耗剩余云端额度。实时背景帧只做端侧 ORB/RANSAC，不上传、不调用模型。

引入官方 OpenCV 4.12.0；根 Gradle 转换移除其重复 C++ 运行库，统一工程 NDK 28.2 的产物。App/模块保持 ARM64。验证使用 `:app:mergeDebugNativeLibs` 和 `python3 feature-camera-vlm/scripts/verify_native_alignment.py`，无需打包。网关 v2 声明必须绑定实际已验证模型，详细配置见 `services/vlm-gateway/README.md`。

2026-10-03 自动选景修订：默认由 App 精简最多 8 张关键帧，交给多图模型选 1 张并自动冻结、生成三构图。结果页不展开背景列表，备用人工选择仅由用户主动打开；多图不可用时显示具体原因和继续自动选景/模型设置入口。详见联合验收清单与网关部署说明。

### AR 黑屏／延迟诊断

启动、Camera2 出帧、输入亮度、Filament 提交、Surface／窗口位置及扫描／背景匹配增加限频日志；GPU 提交不等同于实际画面已显示。预览顶部对齐，AR 状态移到操作区之后；渲染处理时间计入帧周期，JPEG 改为暂停 AR 拍照时的独立流，避免加入实时额外输出。未构建／测试，黑屏原因及流畅性等待用户日志。具体复现及标签见 `CAMERA_AR_PHOTO_SAMPLE.md` 的诊断日志补充。

### 2026-10-08：模型摄影目录与 AR 空间参考

成年女性 VLM 构图新增三项卡通初稿动作白名单与三个真实面部配置，离线从六个完整组合选编号；四帧选景不变。扶腰／步行初稿退出自动推荐，其他角色及旧网关保留原能力，界面区分初稿、模型推荐和 App 备选。三张构图默认全部并排显示，已移除展开／收起功能。App 内最终结果含背景保护与来源，统一预览／指导／脸部／AR。

AR Optional、ARCore 1.54.0＋Filament 1.75.1：支持时用 Camera2 SharedCamera，CameraX 非 AR 回退，两者互斥。空间会话贯穿扫描与分析，实时 3D 根据地面命中或一次确认落点，环境光、软阴影和可用的软件深度参与绘制；失效隐藏、重新对齐，原始 JPEG 拍照不合成人偶。AR 及脸部示范操作仍通过拍照 MVI 状态。详细配置、局限、命令和用户验收表见 [AR 摄影样片与接入](CAMERA_AR_PHOTO_SAMPLE.md)。本轮仅实现／补测试代码，不运行构建或测试，不标记专业或真机质量完成。

### 推理取消优化（2026-10-09，待用户验证）

本次接入覆盖单图场景观察、多图编号选景及六选一摄影组合。每轮 JNI 绑定自己的取消标记，退出时解除回调捕获；新请求使用独立标记。取消输出不走协议解析，不记录为成功，不触发云端切换。`VlmOffline` 记录“原生任务取消请求”“MNN 取消已检测”（`inGraph`、`operatorChecks`）和“MNN 取消清理完成”；最后的“原生任务取消交接完成”以 `cancelToExitMs` 记录从用户取消传递到原生退出及文件清理结束的耗时。`VlmCamera` 记录“旧推理交接结束”和“新扫描窗口已开始”。`cleanupMs` 只包含检测到取消之后的退出/清理，不能用来代替完整取消延迟。

新增设备测试 `ModelDownloadRepositoryTest.visualPrefillCancellationWithoutTokensAllowsFreshRequestOnSameModel`，使用模拟运行时验证首 token 前取消、副本清理、句柄复用和新标记隔离。原生 `src/test/cpp/mnn_cancellation_test.cpp` 使用真正的微型 MNN CPU 图测试计算中止、空结果和同实例恢复；CMake 选项 `VLM_BUILD_CANCELLATION_PROBE=ON` 才构建目标 `vlm_mnn_cancellation_probe`，默认关闭，不进入 App，也不加载用户权重。补丁应用、编译、探针、设备测试和真实模型中止耗时本次均未执行，不能视为已通过。

取消补丁配置回归代码：`python3 feature-camera-vlm/scripts/test_mnn_cancellation_configure.py`。该脚本只配置宿主微型 CMake 工程，不构建 Android、不加载权重；覆盖父 Git 仓库内的 `.cxx` 子目录、重复配置，以及 Git 返回成功但没有修改内容时的拒绝。本次仅添加代码，未运行脚本、Gradle 或 Android 测试。

2026-10-09 对齐后的人像显示：背景确认后显示同一最终方案的屏幕参考；缺少 AR 地面时不再空白，自动落点每秒最多重试一次。当前背景/方案 revision 的空间人像实际提交后再撤去屏幕参考；未对齐时隐藏，旧放置回调不能切掉新参考。新增可见文案位于 strings.xml，JVM/设备回归代码未执行，详见 `CAMERA_AR_PHOTO_SAMPLE.md`。
