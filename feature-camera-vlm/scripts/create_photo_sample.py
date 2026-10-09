#!/usr/bin/env python3
"""由 Blender 4.5 LTS 执行，制作第一批待验收样片；不声明摄影质量已通过。

示例：Blender --background --factory-startup --python scripts/create_photo_sample.py -- --repo /仓库
完整角色和骨骼沿用现有 GLB。两骨骼解析 IK 使用真实关节头位置，避免导入器
估算的骨尾长度影响解算。源工程、完整动画、脸部对照和结构报告可重复生成。
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import sys

import bpy
import numpy as np
from mathutils import Matrix, Vector

PROFILE = {
    "id": "female-natural-expression-v1", "version": 1,
    "status": "DRAFT", "defaultIntensity": 0.8,
    "expressions": {
        "NEUTRAL": {},
        "SMILE": {"AK_44_MouthSmileLeft": 0.75, "AK_45_MouthSmileRight": 0.75,
                  "AK_07_CheekSquintLeft": 0.15, "AK_08_CheekSquintRight": 0.15},
    },
}
CLIP = "photo-natural-stand-v1"


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def reset_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)


def rig_and_meshes():
    rig = next(o for o in bpy.data.objects if o.type == 'ARMATURE')
    # glTF 导入器会创建骨骼显示用的 Icosphere；仅交付实际蒙皮网格。
    meshes = [o for o in bpy.context.scene.objects if o.type == 'MESH'
              and any(m.type == 'ARMATURE' and m.object == rig for m in o.modifiers)]
    assert len(rig.pose.bones) >= 70 and meshes
    return rig, meshes


def normalize_rig(rig, meshes):
    """把厘米级骨架的对象缩放烘焙进骨架数据，交付米级标准资源。"""
    before = {b.name: joint(rig, b.name).copy() for b in rig.pose.bones}
    mesh_world = {m.name: m.matrix_world.copy() for m in meshes}
    bpy.ops.object.select_all(action='DESELECT')
    rig.select_set(True); bpy.context.view_layer.objects.active = rig
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    for mesh in meshes: mesh.matrix_world = mesh_world[mesh.name]
    bpy.context.view_layer.update()
    # 厘米骨架转米时有浮点分解误差，关节位置最多允许半毫米偏移。
    assert max((joint(rig, name) - p).length for name, p in before.items()) < 0.0005


def joint(rig, name):
    return rig.matrix_world @ rig.pose.bones[name].head


def rotate_world(rig, name, rotation):
    """绕当前骨头的关节头施加世界旋转，保留绑定的偏移和比例。"""
    bone = rig.pose.bones[name]
    world = rig.matrix_world @ bone.matrix
    pivot = world.translation.copy()
    transform = Matrix.Translation(pivot) @ rotation.to_matrix().to_4x4() @ Matrix.Translation(-pivot)
    bone.matrix = rig.matrix_world.inverted() @ transform @ world
    bpy.context.view_layer.update()


def aim(rig, bone, child, goal):
    origin = joint(rig, bone)
    current = joint(rig, child) - origin
    desired = Vector(goal) - origin
    assert min(current.length, desired.length) > 1e-6
    rotate_world(rig, bone, current.rotation_difference(desired))


def solve_limb(rig, upper, lower, end, goal, pole):
    """两段肢体的解析 IK；记录不可达目标，不用拉伸骨骼伪造接触。"""
    origin = joint(rig, upper)
    middle = joint(rig, lower)
    tip = joint(rig, end)
    a, b = (middle - origin).length, (tip - middle).length
    direction = Vector(goal) - origin
    requested = direction.length
    distance = min(max(requested, abs(a - b) + 1e-5), (a + b) * 0.995)
    axis = direction.normalized()
    side = Vector(pole) - origin
    side -= axis * side.dot(axis)
    assert side.length > 1e-5
    side.normalize()
    along = (a * a - b * b + distance * distance) / (2 * distance)
    elbow = origin + axis * along + side * math.sqrt(max(0, a * a - along * along))
    achieved_goal = origin + axis * distance
    aim(rig, upper, lower, elbow)
    aim(rig, lower, end, achieved_goal)
    error = (joint(rig, end) - achieved_goal).length
    assert error < 0.002, (end, error)
    return {"joint": end, "requested": list(goal), "achieved": list(joint(rig, end)),
            "errorMeters": error, "clampedMeters": abs(requested - distance)}


def make_pose(rig):
    if rig.animation_data:
        rig.animation_data_clear()
    for bone in rig.pose.bones:
        bone.matrix_basis = Matrix.Identity(4)
    bpy.context.view_layer.update()
    # 角色正面为 -Y；左侧是角色的左侧，+X。支撑脚保持原位置。
    ankle_targets = {s: joint(rig, f"Bip01 {s} Foot").copy() for s in ('L', 'R')}
    original_feet = {s: (rig.matrix_world @ rig.pose.bones[f"Bip01 {s} Foot"].matrix).copy()
                     for s in ('L', 'R')}
    pelvis = rig.pose.bones['Bip01 Pelvis']
    world = rig.matrix_world @ pelvis.matrix
    world.translation += Vector((0.018, 0.0, -0.004))
    pelvis.matrix = rig.matrix_world.inverted() @ world
    bpy.context.view_layer.update()
    # 轻微右脚前移，身体重心偏向左侧；膝盖目标确定弯曲方向。
    ankle_targets['R'] += Vector((-0.008, -0.035, 0.0))
    report = []
    for side in ('L', 'R'):
        target = ankle_targets[side]
        report.append(solve_limb(rig, f"Bip01 {side} Thigh", f"Bip01 {side} Calf",
                                 f"Bip01 {side} Foot", target, (target.x, -0.35, 0.45)))
        # 保留脚掌原有方向，避免 IK 把鞋尖带向膝盖。
        foot = original_feet[side]
        foot.translation = joint(rig, f"Bip01 {side} Foot")
        rig.pose.bones[f"Bip01 {side} Foot"].matrix = rig.matrix_world.inverted() @ foot
        bpy.context.view_layer.update()
    for side, sign in (('L', 1), ('R', -1)):
        shoulder = joint(rig, f"Bip01 {side} UpperArm")
        target = Vector((sign * 0.225 + 0.018, -0.035, shoulder.z - 0.475))
        report.append(solve_limb(rig, f"Bip01 {side} UpperArm", f"Bip01 {side} Forearm",
                                 f"Bip01 {side} Hand", target, (sign * 0.30, 0.16, 1.12)))
        wrist = joint(rig, f"Bip01 {side} Hand")
        aim(rig, f"Bip01 {side} Hand", f"Bip01 {side} Finger2",
            wrist + Vector((sign * 0.012, -0.015, -0.11)))
    # 手指沿各自屈曲平面轻弯，拇指沿用素材已有自然展开，不强行握拳。
    for side in ('L', 'R'):
        for finger in range(1, 5):
            for suffix, angle in (('', 3), ('1', 7), ('2', 5)):
                name = f"Bip01 {side} Finger{finger}{suffix}"
                bone = rig.pose.bones[name]
                bone.rotation_mode = 'XYZ'
                bone.rotation_euler.z = math.radians(angle)
    bpy.context.view_layer.update()
    return report


def expression(meshes, name, intensity=0.8):
    weights = PROFILE['expressions'][name]
    supported = set()
    for mesh in meshes:
        keys = mesh.data.shape_keys
        if not keys:
            continue
        if keys.animation_data:
            keys.animation_data_clear()
        for key in keys.key_blocks:
            key.value = weights.get(key.name, 0.0) * intensity
            supported.add(key.name)
    assert supported.issuperset(weights), set(weights) - supported
    bpy.context.view_layer.update()


def bounds(meshes):
    graph = bpy.context.evaluated_depsgraph_get()
    points = [o.matrix_world @ v.co for mesh in meshes
              for o in [mesh.evaluated_get(graph)] for v in o.data.vertices]
    return Vector(tuple(min(p[i] for p in points) for i in range(3))), Vector(tuple(max(p[i] for p in points) for i in range(3)))


def bake(rig):
    bpy.context.scene.render.fps = 24
    bpy.context.scene.frame_start, bpy.context.scene.frame_end = 1, 25
    for frame in (1, 25):
        for bone in rig.pose.bones:
            bone.rotation_mode = 'QUATERNION'
            bone.keyframe_insert('location', frame=frame)
            bone.keyframe_insert('rotation_quaternion', frame=frame)
            bone.keyframe_insert('scale', frame=frame)
    rig.animation_data.action.name = CLIP
    bpy.context.scene.frame_set(1)


def setup_studio():
    scene = bpy.context.scene
    scene.render.engine = 'CYCLES'
    scene.cycles.device = 'CPU'
    scene.cycles.samples = 24
    scene.cycles.use_denoising = True
    scene.render.image_settings.file_format = 'PNG'
    scene.render.resolution_percentage = 100
    scene.world = bpy.data.worlds.new('SampleStudio')
    scene.world.use_nodes = True
    scene.world.node_tree.nodes['Background'].inputs[0].default_value = (0.65, 0.68, 0.72, 1)
    scene.world.node_tree.nodes['Background'].inputs[1].default_value = 0.65
    scene.view_settings.view_transform = 'AgX'
    for name, position, energy, size in [('Key', (-2, -3, 4), 350, 3), ('Fill', (2, -1, 2), 180, 2)]:
        light = bpy.data.lights.new(name, 'AREA'); light.energy = energy; light.shape = 'DISK'; light.size = size
        obj = bpy.data.objects.new(name, light); scene.collection.objects.link(obj); obj.location = position
        obj.rotation_euler = (Vector((0, 0, 1)) - obj.location).to_track_quat('-Z', 'Y').to_euler()
    camera = bpy.data.objects.new('ReviewCamera', bpy.data.cameras.new('ReviewCamera'))
    scene.collection.objects.link(camera); scene.camera = camera
    camera.data.type = 'ORTHO'
    return camera


def render(camera, path, center, angle, size, face=False):
    scene = bpy.context.scene
    scene.render.resolution_x = 512
    scene.render.resolution_y = 512 if face else 768
    camera.data.ortho_scale = size
    radians = math.radians(angle)
    camera.location = center + Vector((3 * math.sin(radians), -3 * math.cos(radians), 0))
    camera.rotation_euler = (center - camera.location).to_track_quat('-Z', 'Y').to_euler()
    scene.render.filepath = str(path)
    bpy.ops.render.render(write_still=True)


def review_grid(output):
    """把同一交付资源的实际渲染排成对照图，便于用户一次检查。"""
    pixels = np.ones((1536, 768, 4), dtype=np.float32)
    for i, name in enumerate(['neutral-front', 'neutral-quarter', 'neutral-side', 'neutral-back', 'neutral-face', 'smile-face']):
        image = bpy.data.images.load(str(output / (name + '.png')), check_existing=False)
        height = 576 if i < 4 else 384
        image.scale(384, height)
        values = np.empty(384 * height * 4, dtype=np.float32)
        image.pixels.foreach_get(values)
        x = (i % 2) * 384
        y = 1536 - (i // 2 + 1) * 576 if i < 4 else 0
        pixels[y:y + height, x:x + 384] = values.reshape(height, 384, 4)
        bpy.data.images.remove(image)
    grid = bpy.data.images.new('ReviewGrid', 768, 1536)
    grid.pixels.foreach_set(pixels.ravel())
    grid.filepath_raw = str(output / 'review-grid.png')
    grid.file_format = 'PNG'; grid.save()
    bpy.data.images.remove(grid)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--skip-render', action='store_true')
    args = parser.parse_args(sys.argv[sys.argv.index('--') + 1:])
    repo = args.repo.resolve()
    source = repo / 'feature-camera-vlm/src/main/assets/vlm_avatars/adult_female.glb'
    asset_dir = repo / 'feature-camera-vlm/src/main/assets/photo_samples/female-natural-v1'
    output = repo / 'artifacts/camera-photo-sample-v1'
    asset_dir.mkdir(parents=True, exist_ok=True); output.mkdir(parents=True, exist_ok=True)
    reset_scene(); bpy.ops.import_scene.gltf(filepath=str(source))
    rig, meshes = rig_and_meshes()
    # 样片只保留新动作，防止导出/重载时选中上游单帧动作。
    for obj in bpy.data.objects:
        if obj.animation_data: obj.animation_data_clear()
    for action in list(bpy.data.actions): bpy.data.actions.remove(action, do_unlink=True)
    normalize_rig(rig, meshes)
    ik = make_pose(rig)
    expression(meshes, 'NEUTRAL'); bake(rig)
    glb = asset_dir / 'avatar.glb'
    bpy.ops.object.select_all(action='DESELECT')
    rig.select_set(True)
    for mesh in meshes: mesh.select_set(True)
    bpy.context.view_layer.objects.active = rig
    bpy.ops.export_scene.gltf(filepath=str(glb), export_format='GLB', use_selection=True,
                              export_animations=True, export_animation_mode='ACTIONS',
                              export_force_sampling=True, export_morph=True,
                              export_morph_animation=False, export_skins=True,
                              export_anim_slide_to_zero=True)
    profile = asset_dir / 'expressions.json'
    profile.write_text(json.dumps(PROFILE, indent=2) + '\n')
    # 实际重新导入导出资源，后续预览来自交付 GLB，不仅来自制作场景。
    reset_scene(); bpy.ops.import_scene.gltf(filepath=str(glb))
    rig, meshes = rig_and_meshes()
    assert len(bpy.data.actions) == 1 and CLIP in rig.animation_data.action.name
    bpy.context.scene.frame_set(1)
    for result in ik:
        assert (joint(rig, result['joint']) - Vector(result['achieved'])).length < 0.002, result['joint']
    minimum, maximum = bounds(meshes)
    assert 1.3 < maximum.z - minimum.z < 2.0, (minimum, maximum)
    camera = setup_studio()
    center = (minimum + maximum) / 2
    full_size = (maximum.z - minimum.z) * 1.12
    head = joint(rig, 'Bip01 Head') + Vector((0, -0.025, 0.06))
    expression(meshes, 'NEUTRAL')
    if not args.skip_render:
        for label, angle in [('front', 0), ('quarter', 35), ('side', 90), ('back', 180)]:
            render(camera, output / f'neutral-{label}.png', center, angle, full_size)
        render(camera, output / 'neutral-face.png', head, 0, 0.36, face=True)
        expression(meshes, 'SMILE')
        render(camera, output / 'smile-face.png', head, 0, 0.36, face=True)
        render(camera, output / 'smile-front.png', center, 0, full_size)
        review_grid(output)
    expression(meshes, 'NEUTRAL')
    bpy.context.preferences.filepaths.save_version = 0
    bpy.ops.wm.save_as_mainfile(filepath=str(output / 'source.blend'), compress=True)
    report = {'status': 'DRAFT', 'blenderVersion': bpy.app.version_string,
              'sourceSha256': sha256(source), 'glbSha256': sha256(glb),
              'expressionSha256': sha256(profile), 'boneCount': len(rig.pose.bones),
              'animationName': CLIP, 'sampleSeconds': 0.0, 'ik': ik,
              'boundsBlenderZUp': {'min': list(minimum), 'max': list(maximum)},
              'review': {'structure': 'RELOADED_FULL_POSE_VERIFIED', 'desktop': 'DRAFT_VISUAL_REVIEW_REQUIRED',
                         'device': 'PENDING', 'photography': 'PENDING'}}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print('PHOTO_SAMPLE_COMPLETE', json.dumps(report))


if __name__ == '__main__':
    main()
