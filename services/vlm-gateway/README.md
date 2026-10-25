# 本地 VLM 云端网关

Python 3.10+。原 VLM 接口只执行一轮供应商请求，会话、工具循环和自动切换由 Android 客户端管理；新增摄影图层任务独立执行并持久化。P3＋P4 历史已执行 35 项模拟上游测试；本轮新增摄影任务测试仅编写，未运行，也未请求真实供应商。

## 启动

```bash
cd services/vlm-gateway
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
```

编辑 `.env`：设置独立随机 `VLM_GATEWAY_TOKEN`，再填写所需供应商的 `*_API_KEY`。地址、默认模型 ID 和请求路径统一在 [`config/cloud-models.json`](../../config/cloud-models.json)；如需部署覆盖，额外设置 `*_MODEL`/`*_BASE_URL`。Key 或有效模型为空的供应商会报告未配置，不要提交 `.env`。App 的 QWEN 官方直连在设备设置中填 Key；网关接入在 App 中仅填网关令牌。

环境变量由 shell 显式加载（文件必须由你自己维护）：

```bash
set -a
source .env
set +a
uvicorn gateway.app:app --host 0.0.0.0 --port 8000
```

访问 `http://127.0.0.1:8000/health` 检查存活；OpenAPI 页面为 `/docs`。受保护接口在请求头带 `Authorization: Bearer <网关令牌>`。

Android 模拟器地址通常是 `http://10.0.2.2:8000`；真机使用开发电脑局域网 IP。也可执行 `adb reverse tcp:8000 tcp:8000` 后在真机填 `http://127.0.0.1:8000`。这些 HTTP 地址仅供 Debug；Release 使用部署好的 HTTPS 地址。

## 配置与协议

### Seedream 双候选摄影参考（2026-10-24）

仅接入 `config/cloud-models.json.image_generation` 中的 Flash／Pro，不添加 4.0，也不修改原 Seed 视觉模型。密钥读取 `SEEDREAM_API_KEY`，未设置时复用 `SEED_API_KEY`。每个候选只提交一次官方 `layer_decomposition=true` 请求，不调用 VLM 复查或追加第二次生图。姿势创作与图层拆分能否在该模式中同时满足，仍待真实 API 验收。

新增受保护接口：

| 接口 | 行为 |
|---|---|
| `POST /v1/portraits` | 提交一个候选；字段为 `requestId`、`model`、`imageBase64`（JPEG）和 `prompt` |
| `GET /v1/portraits/{requestId}` | 查询 `QUEUED/RUNNING/READY/FAILED/INTERRUPTED`；就绪后返回原生图层结构 |
| `GET /v1/portraits/{requestId}/assets/{index}` | 使用同一网关令牌下载持久化 PNG |

App 当前每次仅提交一个 `requestId` 并查询；该候选完成后才允许用户主动提交下一张，不预排第二张。相同 ID 和相同内容重复提交会复用已有状态，内容冲突返回 409；不同 ID 代表新的收费请求。服务端并发上限为 2，未决队列最多 16，不做自动重试。取消客户端等待不保证上游停止计费。

`VLM_PORTRAIT_DIR` 配置 SQLite 和图层文件目录，默认当前工作目录的 `.vlm-portraits/`。终态任务与文件默认保留七天，在服务启动时清理；需持久卷及额外磁盘监控。当前工作队列采用**单进程**，请使用 `uvicorn gateway.app:app --workers 1`；重启后未决任务标为 `INTERRUPTED`，不会不确定地重新向供应商提交。目录、资源链接和接口响应都不应公开；线上继续要求 HTTPS 和独立令牌，面向多用户部署前需接入用户身份与配额隔离。

服务端保存原生底图及全部图层；Android 按 `z_index` 升序合成全部前景，优先用 `bounding_box.absolute`，随底图解码缩放同步映射，无绝对坐标时才用 `normalized`。完整图预览独立于人像筛选；未取得可用人像时仍可查看、放大完整图，但不能应用人像。协议允许只返回一张底图供观察。不能把去人物底图冒充完整构图。真实输出数量由 `usage.generated_images` 记录，不能把一次候选任务视作只计一张输出图。

原 VLM 接口说明如下。

| 前缀 | 接口 | 输出模式 |
| --- | --- | --- |
| `QWEN` | `/chat/completions` 图像消息 | JSON Object，关闭思考 |
| `SEED` | `/chat/completions` 图像消息 | 关闭思考，提示词约束 JSON；选用已开通的 Seed 2.0 模型 |
| `GPT` | `/responses` 图像消息 | `text.format` 严格 JSON Schema，`store=false` |
| `GEMINI` | `/models/{model}:generateContent` | inline 图像、`responseJsonSchema` |

各前缀支持 `_API_KEY`、`_MODEL`、`_BASE_URL` 环境变量。环境变量优先于统一配置，显式空值也会覆盖。统一配置中的 QWEN/Seed 默认模型不代表账号调用已验收；GPT/Gemini 默认模型为空，需按账号开通情况填写。上游地址由服务端管理员设置，客户端不能指定网关上游 URL。

网关启动时读取统一配置，修改后重启。独立部署须同时携带根目录 `config/cloud-models.json` 并保留 `services/vlm-gateway/gateway` 相对位置；不要只复制 Python 文件。修改网关业务路径后，还须重新构建安装 App，使两端一致。各供应商请求结构仍按现有协议适配，修改路径不会自动切换协议。密钥只用环境变量，不写进共享 JSON。

`POST /v1/director/step` 请求字段：

```json
{
  "version": 1,
  "imageId": "frame-uuid",
  "requestId": "request-uuid",
  "provider": "QWEN",
  "imageBase64": "JPEG 的标准 Base64，不含 data URI 前缀",
  "prompt": "客户端构造的导演协议、用户要求、已有方案及工具观察"
}
```

响应为 `{model, raw, decision, elapsedMs}`；模型 JSON 结构错误时 `decision=null` 并保留原始文本，由客户端使用一次修正预算。网关只校验公共协议；设备倍率、候选数量、选中方案修改范围等由客户端校验。

`/v1/capabilities` 返回四家供应商配置状态和服务端模型标识，不返回密钥。`/health` 只说明服务进程存活，不代表模型可用。

## 错误和日志

Seedream 任务日志包含 `VlmImageGen` 前缀与候选 ID，记录调用阶段、HTTP 状态、上游错误码／请求 ID、图层数量和耗时。查询 FAILED 任务时，`result.failure` 可包含 `stage`、`type`、`code`、`http_status`、`request_id`，不返回上游 message、照片、提示词、密钥或签名地址；Android 同标签日志会展示这份安全摘要。仅增加诊断，不自动重发失败的收费任务。

400/422 表示参数问题，401 表示网关令牌无效，403 表示识别到的内容拒绝，429 表示上游限流，502/503/504 表示上游输出/配置/连接/超时等问题。内容拒绝不会作为普通服务失败换模型；供应商鉴权失败表示服务端配置不可用。不同模型新增的拒绝码仍需要真实联调补充映射。

HTTPX 连接超时 10 秒，其他网络阶段超时 60 秒；Android 整次网关请求期限 70 秒。服务不自动重试，不自动跨区域换模型。默认日志不记录图片、API key、完整提示词或模型文本。正式部署需在 HTTPS 反向代理处设置适合并发量的请求体限制和访问控制；不要开启记录原始请求体的调试日志。

## 测试

```bash
python -m pytest tests
```

测试使用 `httpx.MockTransport` 和 FastAPI TestClient，覆盖四家图像请求格式、鉴权、协议错误、内容拒绝、限流和不重试。没有调用真实计费模型。完整 Android/模型包/设备验收步骤见 [`docs/FEATURE_CAMERA_VLM.md`](../../docs/FEATURE_CAMERA_VLM.md)。

## P4 多图部署

升级网关后重启服务；原 `/v1/capabilities` 与 `/v1/director/step` 保持兼容。新增鉴权接口：

- `GET /v2/capabilities`：返回 `version=2`，逐供应商的实际 `model/configured/multiImage/maxImages`。
- `POST /v2/views/select`：输入 `version=2/provider/requestId/scanId/prompt/images`。每张图含 `frameId/imageBase64/preferred/width/height`；图片长边最多 768，1–8 张 JPEG，解码字节合计最多 3 MiB。
- 输出 `model/raw/selection/elapsedMs`。`selection` 含 `scanId/primary/alternatives`（最多两个备选），每个候选包含 `frameId/subject/reason/backgroundRegions/subjectRegions/uncertainties`。区域是该帧的归一化矩形。引用未提交帧、重复帧或非法区域时返回 `selection=null`，客户端最多纠正一次，服务端不重试。

多图能力默认关闭，不能只凭存在密钥而启用。验证**当前配置的实际模型**后，例如配置 `QWEN_MULTI_IMAGE_MODEL` 为与 `QWEN_MODEL` 相同的 ID，配置 `QWEN_MULTI_IMAGE_MAX_IMAGES=8`；其他供应商同理。模型 ID 改变但声明未同步时，多图自动停用。真实供应商图像数量、质量和响应正确率仍需人工验收。

四家适配器均在每张图前发送 `frameId=...` 文字，并使用供应商真实图像通道。网关不保存扫描图片。旧部署没有 v2 声明时，App 保留关键帧让用户选背景，继续走 v1 单图构图。

验证命令（Python 3.10+，仅模拟上游）：

```bash
cd services/vlm-gateway
python -m pytest tests -q
```

部署前保留 HTTPS、令牌与原有上游配置；无需为实时背景匹配增加接口。取消、内容拒绝和参数错误不由服务端自动重试。

## 摄影参考目录 v2（2026-10-08）

本次新增 `POST /v2/director/step` 和 `/v2/capabilities.providers[].directorV2`；该声明绑定环境变量中实际配置的模型，不代表供应商已实测通过。请求仍为单张 JPEG、provider、requestId、imageId 和 prompt，但 `version=2`。响应保持 `{model,raw,decision,elapsedMs}`，v2 数据位于 raw，`decision=null`，避免改变 v1 的 Decision 类型。

raw 顶层为 `version=2,imageId,action=FINAL,intents`；每项字段 `id,title,shot,zone,actionId,expression,expressionIntensity,yaw,reason,backgroundKeepRegions,uncertainties`。初稿白名单为 `draft.rain_natural_stand`、`draft.rain_side_stand`、`draft.rain_look_back`，表情 `NEUTRAL/SMILE/HAPPY`，强度 0–1、额外朝向 ±35°。模型不能指定骨骼矩阵、资源路径或更改角色外观。四供应商沿用真实图像通道，GPT／Gemini 使用新 schema；千问和 Seed 使用 JSON 提示词。最终数量、来源、布局和动作校验由 App 执行，不合法 raw 交有限修正轮次；不静默生成默认专业姿势。

部署需更新这些 Python 文件并重启现有网关进程（保持原令牌、上游配置和 HTTPS）；无需重新配置密钥。App 查询不存在 v2 的旧网关或缺少 directorV2 声明时走 v1，并提示旧能力。计费预算继续由 App 会话统一统计，不因版本、重新生成或切供应商重置。新增 `tests/test_photo_reference.py` 使用模拟上游；本轮未运行测试，真实供应商可用性由用户联调。
