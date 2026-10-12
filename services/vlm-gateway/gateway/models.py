"""与 Android 公共协议对应的数据定义。"""
from enum import Enum
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator


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


class ViewRegion(StrictModel):
    left: float = Field(ge=0, le=1)
    top: float = Field(ge=0, le=1)
    right: float = Field(ge=0, le=1)
    bottom: float = Field(ge=0, le=1)


class ViewCandidate(StrictModel):
    frameId: str = Field(min_length=1, max_length=128)
    subject: str = Field(min_length=1, max_length=2000)
    reason: str = Field(min_length=1, max_length=4000)
    backgroundRegions: list[ViewRegion] = Field(max_length=8)
    subjectRegions: list[ViewRegion] = Field(max_length=8)
    uncertainties: list[str] = Field(max_length=16)


class ViewSelection(StrictModel):
    version: Literal[2]
    scanId: str
    primary: ViewCandidate
    alternatives: list[ViewCandidate] = Field(max_length=2)


class ViewImage(StrictModel):
    frameId: str = Field(min_length=1, max_length=128)
    imageBase64: str = Field(min_length=4, max_length=4_194_304)
    preferred: bool
    width: int = Field(gt=0, le=768)
    height: int = Field(gt=0, le=768)


class ViewRequest(StrictModel):
    version: Literal[2]
    scanId: str = Field(min_length=1, max_length=128)
    provider: Provider
    requestId: str = Field(min_length=1, max_length=128)
    images: list[ViewImage] = Field(min_length=1, max_length=8)
    prompt: str = Field(min_length=1, max_length=100_000)


class ViewReply(StrictModel):
    model: str
    raw: str
    selection: ViewSelection | None
    elapsedMs: int


class PhotoReferenceIntent(StrictModel):
    id: str = Field(min_length=1, max_length=128)
    title: str = Field(max_length=500)
    shot: Literal["ENVIRONMENT", "FULL", "HALF", "CLOSE_UP"]
    zone: Literal["LEFT", "CENTER", "RIGHT"]
    actionId: Literal["draft.rain_natural_stand", "draft.rain_side_stand", "draft.rain_look_back"]
    expression: Literal["NEUTRAL", "SMILE", "HAPPY"]
    expressionIntensity: float = Field(ge=0, le=1)
    yaw: float = Field(ge=-35, le=35)
    reason: str = Field(max_length=4000)
    backgroundKeepRegions: list[ViewRegion] = Field(max_length=8)
    uncertainties: list[str] = Field(max_length=16)


class PhotoReferenceDecision(StrictModel):
    version: Literal[2]
    imageId: str
    action: Literal["FINAL"]
    intents: list[PhotoReferenceIntent] = Field(min_length=1, max_length=3)


class PhotoStepRequest(StepRequest):
    version: Literal[2]


class PhotographyPoint(StrictModel):
    x: float = Field(ge=0, le=1)
    y: float = Field(ge=-2, le=4)


class PhotographyBodyRegion(StrictModel):
    left: float = Field(ge=0, le=1)
    top: float = Field(ge=-2, le=4)
    right: float = Field(ge=0, le=1)
    bottom: float = Field(ge=-2, le=4)


class PhotographyReference(StrictModel):
    objectName: str = Field(min_length=1)
    region: ViewRegion
    heightMinMeters: float = Field(gt=0)
    heightMaxMeters: float = Field(gt=0)
    depthRelation: Literal["SAME", "PERSON_NEARER", "PERSON_FARTHER", "UNKNOWN"]
    evidence: str = Field(min_length=1)

    @model_validator(mode="after")
    def valid_reference(self):
        if self.heightMaxMeters < self.heightMinMeters or self.region.right <= self.region.left or self.region.bottom <= self.region.top:
            raise ValueError("PHOTO_REFERENCE_INVALID")
        return self


class PhotographyIntent(StrictModel):
    id: str = Field(min_length=1)
    title: str = Field(min_length=1)
    shot: Literal["ENVIRONMENT", "FULL", "HALF", "CLOSE_UP"]
    crop: ViewRegion
    cropReason: str
    head: PhotographyPoint
    foot: PhotographyPoint
    personRegion: PhotographyBodyRegion
    actionId: Literal["draft.rain_natural_stand", "draft.rain_side_stand", "draft.rain_look_back", "basic.relaxed", "basic.side", "basic.look_back", "basic.hands_front"]
    expression: Literal["NEUTRAL", "SMILE", "HAPPY"]
    expressionIntensity: float = Field(ge=0, le=1)
    yaw: float = Field(ge=-35, le=35)
    reason: str = Field(min_length=1)
    guidance: str = Field(min_length=1)
    backgroundKeepRegions: list[ViewRegion] = Field(max_length=16)
    references: list[PhotographyReference] = Field(max_length=8)
    uncertainties: list[str]

    @model_validator(mode="after")
    def valid_geometry(self):
        c = self.crop
        width, height = c.right-c.left, c.bottom-c.top
        if min(width, height) < .1999 or abs(width-height) > .015:
            raise ValueError("PHOTO_CROP_INVALID")
        if min(width, height) < .85 and not self.cropReason.strip():
            raise ValueError("PHOTO_CROP_REASON_MISSING")
        for r in self.backgroundKeepRegions:
            area = (r.right-r.left)*(r.bottom-r.top)
            visible = max(0, min(c.right, r.right)-max(c.left, r.left))*max(0, min(c.bottom, r.bottom)-max(c.top, r.top))
            if r.right <= r.left or r.bottom <= r.top or visible/area < .90-.0001:
                raise ValueError("PHOTO_BACKGROUND_CROPPED")
        r = self.personRegion
        if self.head.y >= self.foot.y or r.right <= r.left or r.bottom <= r.top or abs(self.head.x-self.foot.x) > .15:
            raise ValueError("PERSON_REGION_INVALID")
        if not (r.left <= self.head.x <= r.right and r.left <= self.foot.x <= r.right and self.head.y >= r.top-.02 and self.foot.y <= r.bottom+.02):
            raise ValueError("PERSON_REGION_INVALID")
        span = (self.foot.y-self.head.y)/height
        top = (self.head.y-c.top)/height
        foot = (self.foot.y-c.top)/height
        if not (.08 <= span <= 4 and -.02 <= top <= .9 and .1 <= foot <= 4 and c.left <= self.foot.x <= c.right):
            raise ValueError("PERSON_LAYOUT_INVALID")
        cut = (foot-1)/span
        if self.shot in ("FULL", "ENVIRONMENT") and (top < 0 or foot > 1):
            raise ValueError("FULL_BODY_CROPPED")
        if self.shot == "HALF" and not (.20 <= cut <= .58):
            raise ValueError("HALF_BODY_CUT_INVALID")
        if self.shot == "CLOSE_UP" and not (.58 <= cut <= .84):
            raise ValueError("CLOSE_UP_CUT_INVALID")
        return self


class PhotographyDecision(StrictModel):
    version: Literal[3]
    imageId: str
    sceneDescription: str = Field(min_length=1)
    plans: list[PhotographyIntent] = Field(min_length=1, max_length=3)

    @model_validator(mode="after")
    def valid_ids(self):
        if len({p.id for p in self.plans}) != len(self.plans):
            raise ValueError("PHOTO_PLAN_IDS_INVALID")
        return self


class PhotographyStepRequest(StepRequest):
    version: Literal[3]
