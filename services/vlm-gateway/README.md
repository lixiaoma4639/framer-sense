# 本地 VLM 云端网关

Python 3.10+。本服务只执行一轮供应商请求，会话、工具循环和自动切换由 Android 客户端管理。本次已编写实现及模拟测试，未启动服务、未运行测试、未请求真实供应商。

## 启动

```bash
cd services/vlm-gateway
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
```

编辑 `.env`：设置独立随机 `VLM_GATEWAY_TOKEN`，再填写所需供应商的 `*_API_KEY` 与账号实际可用的 `*_MODEL`。未同时配置 key/model 的供应商会报告未配置。不要把供应商 API key 填入 App，也不要提交 `.env`。

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

| 前缀 | 接口 | 输出模式 |
| --- | --- | --- |
| `QWEN` | `/chat/completions` 图像消息 | JSON Object，关闭思考 |
| `SEED` | `/chat/completions` 图像消息 | 关闭思考，提示词约束 JSON；选用已开通的 Seed 2.0 模型 |
| `GPT` | `/responses` 图像消息 | `text.format` 严格 JSON Schema，`store=false` |
| `GEMINI` | `/models/{model}:generateContent` | inline 图像、`responseJsonSchema` |

各前缀支持 `_API_KEY`、`_MODEL`、`_BASE_URL` 环境变量。上游地址由服务端管理员设置，客户端不能指定上游 URL。`.env.example` 的模型示例不是可用性保证，需按账号开通情况填写。

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

400/422 表示参数问题，401 表示网关令牌无效，403 表示识别到的内容拒绝，429 表示上游限流，502/503/504 表示上游输出/配置/连接/超时等问题。内容拒绝不会作为普通服务失败换模型；供应商鉴权失败表示服务端配置不可用。不同模型新增的拒绝码仍需要真实联调补充映射。

HTTPX 连接超时 10 秒，其他网络阶段超时 60 秒；Android 整次网关请求期限 70 秒。服务不自动重试，不自动跨区域换模型。默认日志不记录图片、API key、完整提示词或模型文本。正式部署需在 HTTPS 反向代理处设置适合并发量的请求体限制和访问控制；不要开启记录原始请求体的调试日志。

## 测试

```bash
python -m pytest tests
```

测试使用 `httpx.MockTransport` 和 FastAPI TestClient，覆盖四家图像请求格式、鉴权、协议错误、内容拒绝、限流和不重试。没有调用真实计费模型。完整 Android/模型包/设备验收步骤见 [`docs/FEATURE_CAMERA_VLM.md`](../../docs/FEATURE_CAMERA_VLM.md)。
