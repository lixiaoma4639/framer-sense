"""与 Android 公共协议对应的数据定义。"""
from enum import Enum
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field


class StrictModel(BaseModel):
    """拒绝未知字段和 NaN/Infinity，避免客户端与网关协议漂移。"""
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


class Provider(str, Enum):
    """可通过服务端访问的供应商标识。"""
    QWEN = "QWEN"
    SEED = "SEED"
    GPT = "GPT"
    GEMINI = "GEMINI"


class Point(StrictModel):
    """方案画面内的归一化点；半身照的脚底允许超出画面。"""
    x: float
    y: float


class Crop(StrictModel):
    """原始冻结画面上的裁剪区域。"""
    left: float
    top: float
    right: float
    bottom: float


class Avatar(StrictModel):
    """人物整体布局、预置姿态和表情。"""
    foot: Point
    height: float
    yaw: float
    pose: Literal["RELAXED", "SIDE", "LOOK_BACK", "HANDS_FRONT", "HAND_HIP", "HANDS_HIPS", "WAVE", "POINT", "HAT", "LOOK_UP", "LOOK_DOWN", "ARMS_OPEN"]
    expression: Literal["NEUTRAL", "SMILE", "HAPPY", "SURPRISED"]
    expressionIntensity: float


class Plan(StrictModel):
    """一个可渲染的构图方案；几何约束由客户端进一步校验。"""
    id: str
    title: str
    shot: Literal["ENVIRONMENT", "FULL", "HALF", "CLOSE_UP"]
    crop: Crop
    zoom: float
    avatar: Avatar
    guidance: str
    reason: str
    needsRetake: bool
    uncertainties: list[str]
    revision: int


class Decision(StrictModel):
    """有限工具动作或最终结果，不允许任意代码。"""
    version: Literal[1]
    imageId: str
    action: Literal["CAPABILITIES", "VALIDATE", "RENDER", "FINAL"]
    plans: list[Plan]


class StepRequest(StrictModel):
    """单步图文输入；图片只在请求生命周期内存在。"""
    version: Literal[1]
    imageId: str = Field(min_length=1, max_length=128)
    provider: Provider
    requestId: str = Field(min_length=1, max_length=128)
    imageBase64: str = Field(min_length=4, max_length=6_000_000)
    prompt: str = Field(min_length=1, max_length=100_000)


class StepReply(StrictModel):
    """模型的可追溯输出；解析失败时保留 raw 供 Agent 修正。"""
    model: str
    raw: str
    decision: Decision | None
    elapsedMs: int
