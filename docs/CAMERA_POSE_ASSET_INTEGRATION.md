# P2 人像动作素材接入与验收

更新日期：2026-10-02。P2 软件支持受控姿势、完整姿态快照和 GLB 动画静帧采样。生产目录仍使用四个现有 Rocketbox 角色、12 个基础受控动作及 6 个表情；这些基础姿势不代表专业摄影素材已经验收。

## 生产目录状态

| 稳定动作 ID | 专业样片 | 状态 |
| --- | --- | --- |
| `professional.natural_stand` | 自然站立 | `AWAITING_ASSET` |
| `professional.side_stand` | 侧身 | `AWAITING_ASSET` |
| `professional.look_back` | 回眸 | `AWAITING_ASSET` |
| `professional.hand_hip` | 手扶腰 | `AWAITING_ASSET` |
| `professional.walk_pause` | 轻步行停顿 | `AWAITING_ASSET` |

以上条目没有执行资源，不进入推荐和手动选择。审核后才能设置为 `REVIEWED`。首版仅允许不依赖座椅、墙面或道具接触的站姿；`SEAT/WALL/PROP` 无论资源是否存在均不可选，等待后续环境条件验证。

## 注册动作

入口为 `avatar/PhotoActionCatalog.kt` 的 `PhotoActionDefinition`。每个条目记录稳定 `id`、递增 `version`、`rigId`、支持角色、适用景别、条件、执行来源和指导。资源放在模块 `src/main/assets/`，文件路径由本地目录维护，大模型不能指定资产文件、动画名称或关节矩阵。

- `avatarAssets` 逐角色指定 `AvatarCatalog.Asset(fileName, version, rigId, boundsExtentScale)`。省略时使用该角色当前内置资源；新骨架必须显式绑定各支持角色的资源。
- 动作内容、采样时刻或指导变化需增加动作版本；GLB、骨架、材质、形变或姿态快照变化需同步增加引用它的动作/角色资源版本。禁止替换内容后复用原版本。
- `labelResource` 和 `stepResources` 引用模块 `strings.xml`；姿态/动画必须提供 `guidance: AvatarPoseDirective`，表示经审核的身体、手臂、头部及站姿语义。`legacyLabel` 仅用于旧姿态字段兼容，不替代实际执行数据。
- 默认 `boundsExtentScale=100f` 仅适配现有 Rocketbox 导出的 extent 问题。符合标准且包围盒正确的新 GLB 应设置 `1f`，并在 Android 中核对头脚和投影比例。该项是包围盒校正，不能用来修补错误骨架单位。
- `PhotoActionResolver` 将最终结果写入 App 内 `ResolvedPhotoSpec`。旧方案无此字段时按基础动作解析；它不进入 v1 网关 JSON。布局、角色、动作版本、采样和表情以解析结果为准，兼容字段必须一致。

自动选择从当前角色、景别及实际文件能力筛选通过的目录中，只采用与模型结构化动作语义完全匹配的 `REVIEWED` 完整姿态/动画；无匹配时保留可执行基础动作。手动选择不调用模型，缺资源或实际渲染失败时保留此前方案。

## 执行格式

### 受控姿势

`PhotoPoseSource.Controlled(pose, directive)` 使用现有受限局部骨骼旋转。`directive=null` 时保留历史 `PoseId` 的精确执行与指导含义；模型组合动作使用内部 `basic.directive`，不会出现在用户动作选择列表。

### 完整姿态快照

`PhotoPoseSource.Snapshot("目录/姿态.json")` 引用 UTF-8 JSON：

```json
{
  "rigId": "角色骨架版本",
  "bones": {
    "关节名称": [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]
  }
}
```

上例仅展示一个矩阵的格式，不能直接作为完整人体姿态。`bones` 必须恰好覆盖目标 GLB 的所有 `skin.joints`，名字唯一；矩阵为相对于该关节父节点的绝对局部变换、列主序 4×4，并与角色原生骨架单位一致。数值必须有限，仿射底行为 `[0,0,0,1]`，线性部分不可奇异。不同角色的绑定姿势、关节偏移或比例不同时分别导出快照和动作条目，不能仅凭关节同名判定可以共享。

### GLB 动画片段

`PhotoPoseSource.Animation("唯一动画名称", sampleSeconds)` 引用动作条目绑定的 GLB。GLB v2 需内嵌完整 BIN、网格、蒙皮和所需纹理；动画名称唯一且非空，关键帧时间为有限、非负、严格递增的 FLOAT SCALAR。采样秒数必须在实际动画时长范围内。客户端同时校验二进制范围、骨架及 gltfio 实际片段名称/时长。

动画默认停在目录指定的拍照时刻，不持续播放。要求作者选择稳定、可模仿的拍照瞬间，不能把转场中间帧作为样片。GLB 使用右手坐标、Y 向上，需在 Android 中检查原始朝向、脚底原点、单位、材质和包围盒；模型整体朝向与布局会在执行动作之后应用。

## 表情与渲染顺序

每次渲染先恢复所有节点的绑定局部变换，清零所有脸部形变；再执行基础姿势、完整快照或动画采样，然后应用整体布局与用户选择的表情，最后更新蒙皮矩阵。动画中的 morph 权重会由最终表情覆盖，本批不保留片段自带的面部表情。

表情使用 `AvatarCatalog.expressionWeights` 的真实通道名称。新角色必须兼容这套通道或在目录中添加明确映射，并逐项校验正权重通道。缺失通道的表情不显示为可选；当前方案表情无法执行时明确失败。零强度的指导显示平静。动作库不能声称渲染器没有执行的眼球/手指等控制。

脸部示范复用同一角色、动作采样、表情与强度，以实际头部世界位置建立独立相机。它不更改构图布局或重复校准，不是放大预览截图；侧脸或回眸中表情是否足够清楚需真机验收。

## 素材交付与人工审核

每个正式样片需附工程源文件、导出 GLB/快照、骨架版本、各角色适配列表、采样秒数、表情通道、中文步骤、作者/来源与授权文件。授权记录应列明商用、修改及 App 再分发范围，保存订单或书面许可和文件摘要。

人工审核要检查重心与动作自然度、手指和四肢、衣物/皮肤穿模、头部与视线、裁切、各观察角度、表情辨识度和步骤可模仿性；分别在制作软件和目标手机比对。记录角色/动作/资源版本、机型、Android、拍照采样点、截图与结论。程序的结构校验不能替代这部分审核。五个样片全部通过真实素材与人工验收后，才能登记为“专业样片完成”；商业发布还需要后续扫描摄影闭环和整体验收。

## 专用测试资产

`src/androidTest/assets/photo_pose_fixture/pose-test.glb` 与 `pose-test.json` 由仓库 `scripts/generate_pose_fixture.py` 生成，为原创两关节蒙皮方块，无第三方素材。用于验证完整姿态、命名动画采样、缺失资源、切换重置与头部取景；只打入仪器测试资源，不进入正式 App。JVM 测试也读取同一资产验证元数据与采样边界。不得用此几何测试资产替代专业样片。

重新生成：在仓库根目录执行 `python3 feature-camera-vlm/scripts/generate_pose_fixture.py`。设备验证需支持 ARM64/OpenGL；本轮只编译设备测试代码，实际 GPU 像素差异和内容质量由用户运行、验收。
