"""生成 P2 专用蒙皮方块测试资产；无第三方素材，不作为摄影样片。"""
import json
import math
import pathlib
import struct


def generate():
    root = pathlib.Path(__file__).resolve().parents[1] / "src/androidTest/assets/photo_pose_fixture"
    root.mkdir(parents=True, exist_ok=True)
    binary = bytearray()
    views, accessors = [], []

    def accessor(values, fmt, components, kind, component_type, bounds=None):
        while len(binary) % 4:
            binary.append(0)
        start = len(binary)
        binary.extend(struct.pack("<" + fmt * len(values), *values))
        views.append({"buffer": 0, "byteOffset": start, "byteLength": len(binary) - start})
        item = {"bufferView": len(views) - 1, "componentType": component_type,
                "count": len(values) // components, "type": kind}
        if bounds:
            item.update(min=bounds[0], max=bounds[1])
        accessors.append(item)
        return len(accessors) - 1

    vertices = [(-.2, 0, -.2), (.2, 0, -.2), (.2, 1, -.2), (-.2, 1, -.2),
                (-.2, 0, .2), (.2, 0, .2), (.2, 1, .2), (-.2, 1, .2)]
    positions = accessor([v for point in vertices for v in point], "f", 3, "VEC3", 5126, ([-.2, 0, -.2], [.2, 1, .2]))
    normals = accessor([v for x, y, z in vertices for v in (x / .574456, (y - .5) / .574456, z / .574456)], "f", 3, "VEC3", 5126)
    joints = accessor([v for _, y, _ in vertices for v in (int(y > .5), 0, 0, 0)], "H", 4, "VEC4", 5123)
    weights = accessor([1., 0., 0., 0.] * 8, "f", 4, "VEC4", 5126)
    indices = accessor([0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4,
                        3, 7, 6, 3, 6, 2, 1, 2, 6, 1, 6, 5, 0, 4, 7, 0, 7, 3], "H", 1, "SCALAR", 5123)
    identity = [1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1.]
    inverse_head = identity.copy()
    inverse_head[13] = -.7
    inverse_bind = accessor(identity + inverse_head, "f", 16, "MAT4", 5126)
    times = accessor([0., 1.], "f", 1, "SCALAR", 5126, ([0.], [1.]))
    rotations = accessor([0., 0., 0., 1., 0., math.sin(math.pi / 6), 0., math.cos(math.pi / 6)], "f", 4, "VEC4", 5126)
    document = {
        "asset": {"version": "2.0", "generator": "FramerSense P2 test fixture"},
        "scene": 0, "scenes": [{"nodes": [0]}],
        "nodes": [{"name": "FixtureRoot", "children": [1, 3]},
                  {"name": "Bip01 Spine1", "children": [2]},
                  {"name": "Bip01 Head", "translation": [0., .7, 0.]},
                  {"name": "FixtureMesh", "mesh": 0, "skin": 0}],
        "skins": [{"joints": [1, 2], "inverseBindMatrices": inverse_bind, "skeleton": 1}],
        "meshes": [{"primitives": [{"attributes": {"POSITION": positions, "NORMAL": normals,
                                                    "JOINTS_0": joints, "WEIGHTS_0": weights},
                                    "indices": indices, "material": 0}]}],
        "materials": [{"doubleSided": True, "pbrMetallicRoughness": {
            "baseColorFactor": [.3, .6, .8, 1.], "metallicFactor": 0., "roughnessFactor": 1.}}],
        "animations": [{"name": "fixture-head-turn", "samplers": [{"input": times, "output": rotations, "interpolation": "LINEAR"}],
                        "channels": [{"sampler": 0, "target": {"node": 2, "path": "rotation"}}]}],
        "bufferViews": views, "accessors": accessors, "buffers": [{"byteLength": len(binary)}]}
    def write_glb(name):
        document["buffers"][0]["byteLength"] = len(binary)
        encoded = json.dumps(document, separators=(",", ":")).encode()
        encoded += b" " * ((-len(encoded)) % 4)
        padded = binary + b"\0" * ((-len(binary)) % 4)
        data = struct.pack("<III", 0x46546C67, 2, 28 + len(encoded) + len(padded))
        data += struct.pack("<II", len(encoded), 0x4E4F534A) + encoded
        data += struct.pack("<II", len(padded), 0x004E4942) + padded
        (root / name).write_bytes(data)
    write_glb("pose-test.glb")
    # 合法 glTF 的四颜色属性，但在 gltfio + Ubershader 的属性/dummy 分配中超过 8 槽。
    # 只用于验证 Kotlin 在 JNI 加载之前拒绝，禁止交给原生加载器或作为生产资源。
    color = accessor([1.] * 32, "f", 4, "VEC4", 5126)
    primitive = document["meshes"][0]["primitives"][0]
    primitive["attributes"].update({f"COLOR_{i}": color for i in range(4)})
    document["meshes"][0]["name"] = "OverBudgetMesh"
    write_glb("over-budget.glb")
    head = identity.copy()
    head[12], head[13] = .18, .7
    (root / "pose-test.json").write_text(json.dumps({"rigId": "fixture-v1", "bones": {
        "Bip01 Spine1": identity, "Bip01 Head": head}}, indent=2) + "\n")


if __name__ == "__main__":
    generate()
