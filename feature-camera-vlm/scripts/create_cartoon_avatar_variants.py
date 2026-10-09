#!/usr/bin/env python3
"""制作 Rain 卡通外观初稿，不渲染、不重载校验、不运行测试。

基于已交付的 v2 GLB 调整固定拓扑造型，修改同一网格的 Basis 和所有表情目标；
三类名称是用户选择的角色设计标签，不用于推断照片人物族裔。所有变体仍待用户验收。
"""
import argparse
import json
from pathlib import Path
import struct
import sys

import bpy
import numpy as np

CLIP = 'photo-rain-natural-v1'
CATALOG_VERSION = 5
VARIANTS = {
    'WHITE': {'file': 'white.glb', 'version': 5, 'skin': [1, 1, 1, 1], 'hair': [1, 1, 1, 1],
              'skinLift': .38, 'skinTarget': [.96, .88, .82], 'darkIris': False,
              'jawScale': 1, 'eyeHeightScale': 1, 'noseWidthScale': 1,
              'noseDepthMeters': 0, 'lipHeightScale': 1},
    'ASIAN': {'file': 'asian.glb', 'version': 5, 'skin': [1, 1, 1, 1], 'hair': [.08, .06, .05, 1],
              'skinLift': .20, 'skinTarget': [.94, .86, .76], 'darkIris': True,
              'jawScale': .90, 'cheekWidthScale': 1.045, 'eyeHeightScale': .86,
              'noseWidthScale': .94, 'noseDepthMeters': .009, 'lipHeightScale': .90,
              'mouthWidthScale': .96, 'lipDepthMeters': .003, 'browHeightScale': .88},
    'BLACK': {'file': 'black.glb', 'skin': [.34, .23, .19, 1.0], 'hair': [.10, .085, .075, 1.0],
              'version': 4,
              'jawScale': 1.035, 'eyeHeightScale': 1.0, 'noseWidthScale': 1.10,
              'noseDepthMeters': .001, 'lipHeightScale': 1.08},
}


def sculpt(values, design):
    """米级局部平滑造型；对每个完整表情分别使用相同映射，保留拓扑和牙齿关系。"""
    points = values.reshape(-1, 3).copy()
    x, y, z = points[:, 0].copy(), points[:, 1].copy(), points[:, 2].copy()
    front = np.exp(-((y + .13) / .08) ** 2)
    jaw = np.exp(-((z - 1.365) / .07) ** 2) * front
    points[:, 0] += x * (design['jawScale'] - 1) * jaw
    cheeks = np.exp(-((np.abs(x) - .073) / .046) ** 2 - ((z - 1.414) / .042) ** 2) * front
    points[:, 0] += x * (design.get('cheekWidthScale', 1) - 1) * cheeks
    nose = np.exp(-(x / .027) ** 2 - ((z - 1.445) / .038) ** 2) * front
    points[:, 0] += x * (design['noseWidthScale'] - 1) * nose
    points[:, 1] += design['noseDepthMeters'] * nose
    eyes = np.exp(-((np.abs(x) - .047) / .038) ** 2 - ((z - 1.477) / .039) ** 2) * front
    points[:, 2] += (z - 1.477) * (design['eyeHeightScale'] - 1) * eyes
    mouth = np.exp(-(x / .037) ** 2 - ((z - 1.387) / .026) ** 2) * front
    points[:, 2] += (z - 1.387) * (design['lipHeightScale'] - 1) * mouth
    points[:, 0] += x * (design.get('mouthWidthScale', 1) - 1) * mouth
    points[:, 1] += design.get('lipDepthMeters', 0) * mouth
    brows = np.exp(-((np.abs(x) - .049) / .042) ** 2 - ((z - 1.515) / .022) ** 2) * front
    points[:, 2] += (z - 1.515) * (design.get('browHeightScale', 1) - 1) * brows
    return points.ravel()


def save_texture(image, folder):
    folder.mkdir(parents=True, exist_ok=True)
    image.filepath_raw = str(folder / (image.name.replace('/', '_') + '.png'))
    image.file_format = 'PNG'
    image.save()
    image.pack()


def lighten_skin(obj, design, folder):
    """仅调整皮肤 baseColor 贴图，保留原纹理差异，不增加全局曝光或改变衣物。"""
    amount = design.get('skinLift', 0)
    if not amount: return
    copies = {}
    for material in obj.data.materials:
        if not material or not material.use_nodes: continue
        if not any(part in material.name for part in ('rain-head-', 'rain-body-')): continue
        for node in material.node_tree.nodes:
            if node.type != 'TEX_IMAGE' or not node.image: continue
            source = node.image
            if source.as_pointer() not in copies:
                image = source.copy()
                values = np.empty(image.size[0] * image.size[1] * 4, dtype=np.float32)
                source.pixels.foreach_get(values)
                pixels = values.reshape(-1, 4)
                pixels[:, :3] = pixels[:, :3] * (1 - amount) + np.array(design['skinTarget']) * amount
                image.pixels.foreach_set(pixels.ravel())
                save_texture(image, folder)
                copies[source.as_pointer()] = image
            node.image = copies[source.as_pointer()]


def dark_iris(obj, folder):
    """只改变眼睛贴图中的蓝色虹膜，保留眼白和亮点。"""
    for material in obj.data.materials:
        if not material or not material.use_nodes: continue
        for node in material.node_tree.nodes:
            if node.type != 'TEX_IMAGE' or not node.image: continue
            image = node.image.copy(); node.image = image
            width, height = image.size
            values = np.empty(width * height * 4, dtype=np.float32)
            image.pixels.foreach_get(values); pixels = values.reshape(-1, 4)
            mask = (pixels[:, 2] > pixels[:, 0] * 1.15) & (pixels[:, 1] > pixels[:, 0] * 1.08)
            brightness = np.max(pixels[mask, :3], axis=1)
            pixels[mask, :3] = brightness[:, None] * np.array([.30, .16, .07])
            image.pixels.foreach_set(pixels.ravel()); save_texture(image, folder)


def write_document(path, data, document):
    """只替换 JSON 块，BIN、采样数据、网格与贴图保持原字节。"""
    json_length = struct.unpack_from('<I', data, 12)[0]
    encoded = json.dumps(document, separators=(',', ':')).encode()
    encoded += b' ' * (-len(encoded) % 4)
    tail = data[20 + json_length:]
    result = struct.pack('<III', 0x46546C67, 2, 20 + len(encoded) + len(tail))
    result += struct.pack('<II', len(encoded), 0x4E4F534A) + encoded + tail
    path.write_bytes(result)


def set_material_factors(path, design):
    """修改标准 glTF baseColorFactor，避免额外 shader 属性和颜色缓冲。"""
    data = path.read_bytes(); json_length = struct.unpack_from('<I', data, 12)[0]
    document = json.loads(data[20:20 + json_length])
    for material in document.get('materials', []):
        name = material.get('name', '')
        factor = design['skin'] if any(n in name for n in ('rain-head-', 'rain-body-')) else \
            design['hair'] if any(n in name for n in ('rain-hair_main-', 'rain-hair_ponytail-', 'rain-hair_strand-', 'rain-eyebrows-')) else None
        if factor is not None: material.setdefault('pbrMetallicRoughness', {})['baseColorFactor'] = factor
    write_document(path, data, document)


def repair_clip_names(repo):
    """修复已知 v3 导出命名，不渲染、不重载验证，也不放宽 App 动画校验。"""
    folder = repo / 'feature-camera-vlm/src/main/assets/photo_samples/rain-natural-v1'
    for design in VARIANTS.values():
        path = folder / design['file']; data = path.read_bytes()
        document = json.loads(data[20:20 + struct.unpack_from('<I', data, 12)[0]])
        for animation in document.get('animations', []):
            if animation.get('name') == CLIP + '.001': animation['name'] = CLIP
        write_document(path, data, document)
        print('CLIP_NAME_WRITTEN', path, flush=True)


def generate(repo, name, design):
    folder = repo / 'feature-camera-vlm/src/main/assets/photo_samples/rain-natural-v1'
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(folder / 'avatar.glb'))
    rig = next(obj for obj in bpy.context.scene.objects if obj.type == 'ARMATURE')
    rig.animation_data_clear()
    # 导入动作还留在 bpy.data.actions 中。先移除它们再创建，才能避免 CLIP 被自动
    # 改为 CLIP.001；创建后再删除旧动作不会恢复新动作的名字。
    for other in list(bpy.data.actions): bpy.data.actions.remove(other)
    bpy.context.scene.frame_start = 0; bpy.context.scene.frame_end = 24
    bpy.context.scene.render.fps = 24
    for frame in (0, 24):
        for bone in rig.pose.bones:
            bone.rotation_mode = 'QUATERNION'
            for attribute in ('location', 'rotation_quaternion', 'scale'):
                bone.keyframe_insert(attribute, frame=frame)
    action = rig.animation_data.action; action.name = CLIP
    bpy.context.scene.frame_set(0)
    texture_folder = repo / '.tools/rain-v33/variant-textures-v5' / name
    for obj in bpy.context.scene.objects:
        if obj.type != 'MESH' or not obj.data.materials: continue
        if any(part in obj.name for part in ('rain-head-', 'rain-eyes-', 'rain-eye_dots-', 'rain-eyebrows-', 'rain-eyelashes-', 'rain-gums_', 'rain-tongue-')):
            targets = obj.data.shape_keys.key_blocks if obj.data.shape_keys else [obj.data]
            for target in targets:
                vertices = target.data if hasattr(target, 'data') else target.vertices
                values = np.empty(len(vertices) * 3, dtype=np.float32)
                vertices.foreach_get('co', values); vertices.foreach_set('co', sculpt(values, design))
            obj.data.update()
        lighten_skin(obj, design, texture_folder)
        if obj.name.startswith('GEO-rain-eyes-') and design.get('darkIris', True): dark_iris(obj, texture_folder)
    destination = folder / design['file']
    bpy.ops.object.select_all(action='DESELECT'); rig.select_set(True)
    for obj in bpy.context.scene.objects:
        if obj.type == 'MESH' and obj.data.materials: obj.select_set(True)
    bpy.context.view_layer.objects.active = rig
    bpy.ops.export_scene.gltf(filepath=str(destination), export_format='GLB', use_selection=True,
        export_animations=True, export_animation_mode='ACTIONS', export_anim_slide_to_zero=True,
        export_skins=True, export_def_bones=True, export_morph=True, export_morph_animation=False,
        export_normals=True, export_materials='EXPORT', export_image_format='AUTO', export_extras=True,
        export_vertex_color='NONE', export_all_vertex_colors=False, export_active_vertex_color_when_no_material=False)
    set_material_factors(destination, design)
    print('VARIANT_WRITTEN', name, destination, flush=True)


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--repo', required=True, type=Path)
    parser.add_argument('--repair-clip-names', action='store_true')
    parser.add_argument('--only', nargs='+', choices=list(VARIANTS), default=['WHITE', 'ASIAN'])
    args = parser.parse_args(sys.argv[sys.argv.index('--') + 1:]); repo = args.repo.resolve()
    if args.repair_clip_names: repair_clip_names(repo)
    else:
        # 黑人 v4 已获用户外观肯定，默认不重制；原始 avatar.glb 始终只作输入。
        for name in args.only: generate(repo, name, VARIANTS[name])
    notice = repo / 'feature-camera-vlm/src/main/assets/photo_samples/rain-natural-v1/NOTICE.md'
    text = notice.read_text()
    if '外观目录版本 3' not in text:
        notice.write_text(text + '\n外观目录版本 3：白种人选项沿用 Rain 基础外观；亚洲人与黑人为 Framer Sense\n'
            '制作的派生脸部造型、虹膜与材质初稿。分类仅为用户选择的设计标签，\n'
            '不用于推断照片人物族裔。保留原 CC BY 4.0 署名，外观与摄影质量待验收。\n')
    output = repo / 'artifacts/camera-cartoon-sample-v1/variant-authoring.json'
    output.write_text(json.dumps({'assetVersion': CATALOG_VERSION, 'source': 'Rain v2 mobile GLB',
        'clip': CLIP, 'clipNameRepair': args.repair_clip_names,
        'generatedVariants': [] if args.repair_clip_names else args.only,
        'variants': VARIANTS,
        'readiness': 'DRAFT', 'validation': 'NOT_RUN_USER_REQUEST', 'qualityApproval': False,
        'notes': 'v5 白种人单独浅暖肤色，亚洲人独立五官初稿；黑人 v4 默认保留。角色设计标签由用户选择，不代表族裔统一外貌。未运行渲染、重载校验或测试。'},
        ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__': main()
