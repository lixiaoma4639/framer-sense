#!/usr/bin/env python3
"""Blender 4.5 LTS：Rain 官方原始控制器 → 冻结摄影姿态 + 两个完整面部形变。

关闭内嵌脚本自动执行。身体先按原 rig 求值再烘焙网格，面部骨骼、修正形变、
晶格一起烘焙成固定拓扑的 morph。手机不执行 CloudRig/BendyBone；此样片不能任意
改变关节，也不宣称五个专业动作已完成。原始完整 rig 另存，方便后续制作。
"""
import argparse
import hashlib
import json
import math
import struct
from pathlib import Path
import sys

import bpy
import numpy as np
from mathutils import Vector, Quaternion
sys.path.insert(0, str(Path(__file__).resolve().parent))
from create_photo_sample import solve_limb, rotate_world, setup_studio, render

CLIP = 'photo-rain-natural-v1'
ASSET_VERSION = 2
PROFILE = {
    'id': 'rain-photography-expression-v1', 'version': 1, 'status': 'DRAFT',
    'defaultIntensity': 1.0,
    'expressions': {'NEUTRAL': {}, 'SMILE': {'PhotoSmile': 1.0}, 'HAPPY': {'PhotoHappy': 1.0}},
}
SOURCE_URL = 'https://studio.blender.org/download-source/files/ee/a7/eea73e55dba1cea31c09848df6a794b2-4.zip'
SOURCE_SHA = '80217f163f6392dc829233d63c2cfb5e1376775bc34101ad14f39631fea70d24'
CREDIT = 'Rain Rig (CC) Blender Foundation | studio.blender.org'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def expression(rig, name):
    """ACT 控制器的局部 Y 轴为表情滑杆；米级输入，禁止直接用权重当位移。"""
    smile, happy = name == 'SMILE', name == 'HAPPY'
    for side in ('L', 'R'):
        rig.pose.bones['ACT-Lips_Corner.' + side].location = (0, .018 if smile else .028 if happy else 0, 0)
        rig.pose.bones['ACT-Cheek_Outer.' + side].location = (0, .006 if smile else .012 if happy else 0, 0)
        rig.pose.bones['ACT-Eyelid_Lower.' + side].location = (0, .004 if smile else .008 if happy else 0, 0)
    jaw = rig.pose.bones['MSTR-Jaw']
    jaw.rotation_mode = 'XYZ'
    jaw.rotation_euler.x = .025 if smile else .10 if happy else 0
    rig.update_tag()
    bpy.context.view_layer.update()


def pose(rig):
    properties = rig.pose.bones['Properties_IKFK']
    for key in ('ik_arm_left', 'ik_arm_right', 'ik_fingers_left', 'ik_fingers_right'):
        properties[key] = 0.0
    rig.update_tag(); bpy.context.view_layer.update()
    # 两边略有差别，手与衣服留出空隙；不通过拉伸手臂实现目标。
    for side, sign in [('L', 1), ('R', -1)]:
        solve_limb(rig, 'FK-Upperarm.' + side, 'FK-Forearm.' + side, 'FK-Hand.' + side,
                   Vector((sign * (.215 if side == 'L' else .225), -.015, .78 if side == 'L' else .76)),
                   Vector((sign * .33, .10, 1.05)))
        for finger in ('Index', 'Middle', 'Ring', 'Pinky'):
            for n, degrees in ((1, 8), (2, 15), (3, 10)):
                bone = rig.pose.bones[f'FK-{finger}{n}.{side}']
                bone.rotation_mode = 'XYZ'; bone.rotation_euler.x = math.radians(degrees)
    # 源 rig 的马尾默认向后水平展开，摄影样片需要受重力下垂的静止造型。
    rotate_world(rig, 'FK-Hair_Ponytail2', Quaternion((1, 0, 0), math.radians(-65)))
    rotate_world(rig, 'FK-Hair_Ponytail3', Quaternion((1, 0, 0), math.radians(-15)))
    rig.update_tag(); bpy.context.view_layer.update()


def evaluated_mesh(obj):
    graph = bpy.context.evaluated_depsgraph_get()
    return bpy.data.meshes.new_from_object(obj.evaluated_get(graph), preserve_all_data_layers=True, depsgraph=graph)


def coordinates(mesh):
    values = np.empty(len(mesh.vertices) * 3, dtype=np.float32)
    mesh.vertices.foreach_get('co', values)
    return values.reshape(-1, 3)


def bake_color(obj, textures):
    """把 UDIM 和节点材质转为标准 baseColor；使用独立烘焙 UV，保留源 shader UV。"""
    old = obj.data.uv_layers.active
    assert old is not None, obj.name
    old.active_render = True
    values = np.array([tuple(v.uv) for v in old.data], dtype=np.float32)
    tile_min = math.floor(float(values[:, 0].min()) + 1e-5)
    tile_count = max(1, math.ceil(float(values[:, 0].max()) - 1e-5) - tile_min)
    uv = obj.data.uv_layers.new(name='PhotoUV', do_init=True)
    uv_name = uv.name
    for index, row in enumerate(values):
        uv.data[index].uv = ((row[0] - tile_min) / tile_count, row[1])
    # 隐式贴图仍读取原始 UV，目标 UV 只传给 bake。
    obj.data.uv_layers.active = old
    size = 1024 if any(s in obj.name for s in ('head', 'eyes', 'jeans', 'hair_main')) else 512
    image = bpy.data.images.new(obj.name + '-color', size * tile_count, size, alpha=False)
    originals = []
    for index, mat in enumerate(obj.data.materials):
        mat = mat.copy(); obj.data.materials[index] = mat; originals.append(mat)
        node = mat.node_tree.nodes.new('ShaderNodeTexImage'); node.image = image
        mat.node_tree.nodes.active = node
    bpy.ops.object.select_all(action='DESELECT'); obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.context.scene.render.engine = 'CYCLES'; bpy.context.scene.cycles.samples = 1
    if 'eye_dots' in obj.name:
        image.generated_color = (1, 1, 1, 1)
    else:
        bpy.ops.object.bake(type='DIFFUSE', pass_filter={'COLOR'}, use_clear=True,
                            margin=8, uv_layer=uv.name)
    image.filepath_raw = str(textures / (obj.name + '.png')); image.file_format = 'PNG'; image.save()
    image.pack()
    mat = bpy.data.materials.new(obj.name + '-MobilePBR'); mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get('Principled BSDF')
    bsdf.inputs['Roughness'].default_value = .25 if 'eyes' in obj.name else .65
    node = mat.node_tree.nodes.new('ShaderNodeTexImage'); node.image = image
    mat.node_tree.links.new(node.outputs['Color'], bsdf.inputs['Base Color'])
    obj.data.materials.clear(); obj.data.materials.append(mat)
    for poly in obj.data.polygons: poly.material_index = 0
    # 只交付新的 UV，防止 glTF 挑选原始 UDIM。
    for name in [layer.name for layer in obj.data.uv_layers if layer.name != uv_name]:
        obj.data.uv_layers.remove(obj.data.uv_layers[name])
    obj.data.uv_layers.active = obj.data.uv_layers[uv_name]
    obj.data.uv_layers.active.active_render = True


def mobile_rig(native, meshes):
    """保留身体和手指关节的绑定身份；摄影姿势已烘焙，脸部控制合并到 Head。"""
    prefixes = ('Spine', 'Neck', 'Head', 'Pelvis', 'Clavicle', 'Foot', 'Toe', 'Shin',
                'Thigh', 'Hand', 'Index', 'Middle', 'Ring', 'Pinky', 'Thumb', 'Forearm', 'Upperarm')
    names = {b.name for b in native.data.bones if b.name.startswith(tuple('DEF-' + p for p in prefixes))
             and b.name != 'DEF-Head_Top'}
    data = bpy.data.armatures.new('RainPhotographySkeleton')
    rig = bpy.data.objects.new('RainPhotographySkeleton', data); bpy.context.scene.collection.objects.link(rig)
    bpy.ops.object.select_all(action='DESELECT'); rig.select_set(True); bpy.context.view_layer.objects.active = rig
    bpy.ops.object.mode_set(mode='EDIT')
    root = data.edit_bones.new('PhotoRoot'); root.head = (0, 0, 0); root.tail = (0, 0, .1)
    for name in sorted(names):
        source = native.pose.bones[name]
        bone = data.edit_bones.new(name)
        bone.matrix = native.matrix_world @ source.matrix
        bone.length = max(.005, source.length)
        parent = source.parent
        while parent and parent.name not in names: parent = parent.parent
        # 第二轮设置层级，避免字母顺序依赖。
    for name in names:
        parent = native.pose.bones[name].parent
        while parent and parent.name not in names: parent = parent.parent
        data.edit_bones[name].parent = data.edit_bones[parent.name] if parent else root
    bpy.ops.object.mode_set(mode='OBJECT')
    for obj, original in meshes:
        old_names = {v.index: v.name for v in original.vertex_groups}
        weights = []
        for vertex in obj.data.vertices:
            sums = {}
            for group in vertex.groups:
                name = old_names.get(group.group, '')
                target = name if name in names else ('DEF-Head' if any(s in obj.name for s in
                         ('head', 'eyes', 'eye_', 'gums', 'tongue', 'hair', 'eyebrow', 'eyelash')) else 'PhotoRoot')
                sums[target] = sums.get(target, 0) + group.weight
            # 限制到 glTF 的四权重；这些关节在样片中不追加受控修正。
            selected = sorted(sums.items(), key=lambda p: p[1], reverse=True)[:4]
            total = sum(w for _, w in selected)
            weights.append([(n, w / total) for n, w in selected] if total > 0 else [('PhotoRoot', 1.0)])
        obj.vertex_groups.clear()
        groups = {n: obj.vertex_groups.new(name=n) for n in names | {'PhotoRoot'}}
        for index, row in enumerate(weights):
            for name, weight in row: groups[name].add([index], weight, 'REPLACE')
        modifier = obj.modifiers.new('MobileSkin', 'ARMATURE'); modifier.object = rig
    bpy.context.scene.render.fps = 24
    bpy.context.scene.frame_start = 1; bpy.context.scene.frame_end = 25
    for frame in (1, 25):
        for bone in rig.pose.bones:
            bone.rotation_mode = 'QUATERNION'
            for prop in ('location', 'rotation_quaternion', 'scale'): bone.keyframe_insert(prop, frame=frame)
    rig.animation_data.action.name = CLIP
    bpy.context.scene.frame_set(1)
    return rig


def contact_sheet(output):
    # 上排三种脸部、下排正面/侧面/背面，全部来自重新导入的手机 GLB。
    pixels = np.ones((1024, 1536, 4), dtype=np.float32)
    for i, name in enumerate(('neutral-face', 'smile-face', 'happy-face', 'neutral-front', 'neutral-side', 'neutral-back')):
        image = bpy.data.images.load(str(output / (name + '.png')), check_existing=False)
        width = 512 if i < 3 else 341
        image.scale(width, 512)
        data = np.empty(width * 512 * 4, dtype=np.float32); image.pixels.foreach_get(data)
        x = (i % 3) * 512 + (512 - width) // 2; y = 512 if i < 3 else 0
        pixels[y:y+512, x:x+width] = data.reshape(512, width, 4)
        bpy.data.images.remove(image)
    image = bpy.data.images.new('RainReview', 1536, 1024)
    image.pixels.foreach_set(pixels.ravel()); image.filepath_raw = str(output / 'review-grid.png')
    image.file_format = 'PNG'; image.save()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--skip-render', action='store_true')
    args = parser.parse_args(sys.argv[sys.argv.index('--') + 1:])
    repo = args.repo.resolve()
    source = repo / '.tools/rain-v33/Rain v3.3/rain_v3.2.blend'
    archive = repo / '.tools/rain-v33/rain-v33.zip'
    assert digest(archive) == SOURCE_SHA, 'RAIN_ARCHIVE_CHANGED'
    output = repo / 'artifacts/camera-cartoon-sample-v1'; output.mkdir(parents=True, exist_ok=True)
    textures = output / 'textures'; textures.mkdir(exist_ok=True)
    assets = repo / 'feature-camera-vlm/src/main/assets/photo_samples/rain-natural-v1'; assets.mkdir(parents=True, exist_ok=True)
    bpy.ops.wm.open_mainfile(filepath=str(source), use_scripts=False)
    if bpy.context.object and bpy.context.object.mode != 'OBJECT':
        bpy.ops.object.mode_set(mode='OBJECT')
    native = bpy.data.objects['RIG-rain']; pose(native); expression(native, 'NEUTRAL')
    # 固定各表情的求值拓扑；降低移动端面数，面部仍保留平滑细分。
    geometry = [o for o in bpy.context.scene.objects if o.type == 'MESH' and o.name.startswith('GEO-')
                and not o.hide_render and o.name != 'GEO-rain-eye_cornea']
    for obj in geometry:
        for modifier in obj.modifiers:
            if modifier.type == 'SUBSURF':
                if obj.animation_data:
                    for driver in obj.animation_data.drivers:
                        if driver.data_path == f'modifiers["{modifier.name}"].levels': driver.mute = True
                modifier.levels = modifier.render_levels = 1
    bpy.context.view_layer.update()
    for text in bpy.data.texts: text.use_module = False
    bpy.ops.file.pack_all()
    bpy.ops.wm.save_as_mainfile(filepath=str(output / 'source.blend'), compress=True)
    meshes = []
    for original in geometry:
        mesh = evaluated_mesh(original)
        obj = bpy.data.objects.new(original.name + '-Photo', mesh)
        bpy.context.scene.collection.objects.link(obj); obj.matrix_world = original.matrix_world.copy()
        # 保留原组索引，与求值网格的顶点组对应。
        for group in original.vertex_groups: obj.vertex_groups.new(name=group.name)
        obj.shape_key_add(name='Basis'); meshes.append((obj, original))
    deltas = {}
    for name, target in [('SMILE', 'PhotoSmile'), ('HAPPY', 'PhotoHappy')]:
        expression(native, name); deltas[name] = {}
        for obj, original in meshes:
            evaluated = evaluated_mesh(original)
            assert len(evaluated.vertices) == len(obj.data.vertices), 'EXPRESSION_TOPOLOGY_CHANGED'
            values = coordinates(evaluated); delta = np.linalg.norm(values - coordinates(obj.data), axis=1)
            changed = int(np.count_nonzero(delta > .0001))
            if changed:
                key = obj.shape_key_add(name=target); key.data.foreach_set('co', values.ravel())
                deltas[name][obj.name] = {'verticesChangedOverPointOneMillimeter': changed,
                                        'maxDeltaMeters': float(delta.max())}
            bpy.data.meshes.remove(evaluated)
        assert deltas[name].get('GEO-rain-head-Photo', {}).get('verticesChangedOverPointOneMillimeter', 0) > 1000
    expression(native, 'NEUTRAL')
    rig = mobile_rig(native, meshes)
    for original in geometry: original.hide_render = True; original.hide_set(True)
    native.hide_render = True; native.hide_set(True)
    bpy.context.scene.cycles.device = 'CPU'
    for obj, _ in meshes:
        print('BAKE_COLOR', obj.name, flush=True); bake_color(obj, textures)
        # 原 rig 的多个颜色层用于制作修正，已参与 baseColor 烘焙。
        # Blender 4.5 默认导出全部颜色层（还会追加白色 COLOR_0），可使 gltfio
        # 每个图元的独立属性缓冲加上 dummy 槽超过 Filament 的 8 槽上限。
        for name in [layer.name for layer in obj.data.color_attributes]:
            obj.data.color_attributes.remove(obj.data.color_attributes[name])
    # 保证默认平静，无任何动画驱动表情权重。
    bpy.ops.object.select_all(action='DESELECT'); rig.select_set(True)
    for obj, _ in meshes: obj.select_set(True)
    bpy.context.view_layer.objects.active = rig
    for action in list(bpy.data.actions):
        if action != rig.animation_data.action: bpy.data.actions.remove(action)
    bpy.ops.export_scene.gltf(filepath=str(assets / 'avatar.glb'), export_format='GLB', use_selection=True,
                              export_animations=True, export_animation_mode='ACTIONS', export_anim_slide_to_zero=True,
                              export_skins=True, export_morph=True, export_morph_animation=False, export_texcoords=True,
                              export_normals=True, export_materials='EXPORT', export_image_format='AUTO',
                              export_vertex_color='NONE', export_all_vertex_colors=False,
                              export_active_vertex_color_when_no_material=False,
                              export_extras=True, export_yup=True, export_def_bones=True)
    payload = (assets / 'avatar.glb').read_bytes()
    document = json.loads(payload[20:20 + struct.unpack_from('<I', payload, 12)[0]])
    required_attributes = {'POSITION', 'NORMAL', 'TEXCOORD_0', 'JOINTS_0', 'WEIGHTS_0'}
    assert all(set(primitive['attributes']) == required_attributes
               for mesh in document['meshes'] for primitive in mesh['primitives']), 'FILAMENT_VERTEX_LAYOUT_UNEXPECTED'
    (assets / 'expressions.json').write_text(json.dumps(PROFILE, ensure_ascii=False, indent=2) + '\n')
    (assets / 'NOTICE.md').write_text(f'# Rain 摄影样片\n\n{CREDIT}\n\n来源：https://studio.blender.org/characters/rain/v3/\n许可：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）。\n\n修改：Framer Sense 制作冻结站姿、完整表情形变和移动端 PBR 材质。\n源 rig 未在手机执行。此样片为 DRAFT，未获用户摄影质量验收。\n')
    report = {'status': 'DRAFT', 'credit': CREDIT, 'license': 'CC-BY-4.0', 'sourceUrl': SOURCE_URL,
              'sourceArchiveSha256': SOURCE_SHA, 'sourceBlendSha256': digest(source), 'assetSha256': digest(assets / 'avatar.glb'),
              'assetVersion': ASSET_VERSION,
              'maxFilamentVertexBufferSlotsUpperBound': 6,
              'vertexColorExport': 'NONE_AFTER_BASECOLOR_BAKE',
              'bodyPose': 'baked-evaluated-geometry', 'face': 'baked-full-expression-morphs',
              'clip': CLIP, 'sampleSeconds': 0, 'skeletonJoints': len(rig.data.bones),
              'vertices': sum(len(o.data.vertices) for o, _ in meshes), 'expressionDeltas': deltas,
              'deviceValidation': 'PENDING', 'professionalApproval': False}
    # 重新导入交付 GLB；对照图绝不使用只能在 Blender 表现的原 rig。
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(assets / 'avatar.glb'))
    imported = [o for o in bpy.context.scene.objects if o.type == 'MESH' and o.data.materials]
    imported_rig = next(o for o in bpy.context.scene.objects if o.type == 'ARMATURE')
    imported_rig.animation_data_clear()
    assert 'DEF-Head' in imported_rig.data.bones
    targets = {key.name for obj in imported if obj.data.shape_keys for key in obj.data.shape_keys.key_blocks}
    assert {'PhotoSmile', 'PhotoHappy'} <= targets
    report['reimportVerified'] = True
    (output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    if not args.skip_render:
        camera = setup_studio(); bpy.context.scene.cycles.samples = 24
        face_center = Vector((0, -.04, 1.455))
        for name, target in [('neutral', None), ('smile', 'PhotoSmile'), ('happy', 'PhotoHappy')]:
            for obj in imported:
                if obj.data.shape_keys:
                    for key in obj.data.shape_keys.key_blocks[1:]: key.value = 1.0 if key.name == target else 0.0
            bpy.context.view_layer.update()
            render(camera, output / (name + '-face.png'), face_center, 0, .33, True)
            if name == 'neutral':
                for label, angle in [('front', 0), ('side', 90), ('back', 180)]:
                    render(camera, output / ('neutral-' + label + '.png'), Vector((0, 0, .80)), angle, 1.9)
        contact_sheet(output)
    print('RAIN_SAMPLE_DONE', json.dumps(report, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
