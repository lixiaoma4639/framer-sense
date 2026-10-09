#!/usr/bin/env python3
"""从 Rain 已交付绑定骨架制作完整静态摄影姿态；不渲染、不重载或运行测试。

使用 glTF 原节点局部矩阵，避免 Blender 导入骨骼 roll 与 Filament 节点坐标不一致。
两段肢体 IK 保持长度，手脚目标、极向量与完整 69 关节结果写入制作记录。
三个外观共用身体绑定；原 GLB、贴图、表情目标与黑人外观不改。
"""
import argparse
import json
import math
from pathlib import Path
import struct
import sys

from mathutils import Matrix, Quaternion, Vector

RIG_ID = 'rain-photo-sample-v1'
VERSION = 1
POSES = ('side_stand', 'look_back', 'hand_hip', 'walk_pause')


class Pose:
    def __init__(self, document):
        self.nodes = document['nodes']
        self.parents = {child: parent for parent, node in enumerate(self.nodes)
                        for child in node.get('children', [])}
        self.joints = document['skins'][0]['joints']
        self.names = {self.nodes[index]['name']: index for index in self.joints}
        self.local = [self.matrix(node) for node in self.nodes]
        self.goals = []

    @staticmethod
    def matrix(node):
        if 'matrix' in node:
            return Matrix([node['matrix'][offset:offset + 4] for offset in range(0, 16, 4)]).transposed()
        x, y, z, w = node.get('rotation', [0, 0, 0, 1])
        return Matrix.Translation(Vector(node.get('translation', [0, 0, 0]))) @ \
            Quaternion((w, x, y, z)).to_matrix().to_4x4() @ \
            Matrix.Diagonal((*node.get('scale', [1, 1, 1]), 1))

    def world(self, index):
        parent = self.parents.get(index)
        return self.local[index].copy() if parent is None else self.world(parent) @ self.local[index]

    def position(self, name):
        return self.world(self.names[name]).translation.copy()

    def set_world(self, name, world):
        index = self.names[name]
        parent = self.parents.get(index)
        self.local[index] = world if parent is None else self.world(parent).inverted() @ world

    def rotate(self, name, axis, degrees):
        world = self.world(self.names[name])
        pivot = world.translation.copy()
        rotation = Quaternion(Vector(axis), math.radians(degrees)).to_matrix().to_4x4()
        self.set_world(name, Matrix.Translation(pivot) @ rotation @ Matrix.Translation(-pivot) @ world)

    def move(self, name, offset):
        world = self.world(self.names[name]); world.translation += Vector(offset)
        self.set_world(name, world)

    def aim(self, name, child, target):
        world = self.world(self.names[name]); origin = world.translation.copy()
        current = self.position(child) - origin; desired = Vector(target) - origin
        rotation = current.rotation_difference(desired).to_matrix().to_4x4()
        self.set_world(name, Matrix.Translation(origin) @ rotation @ Matrix.Translation(-origin) @ world)

    def limb(self, upper, lower, end, target, pole, keep_end_orientation=True):
        origin, middle, tip = self.position(upper), self.position(lower), self.position(end)
        end_world = self.world(self.names[end])
        a, b = (middle - origin).length, (tip - middle).length
        direction = Vector(target) - origin; requested = direction.length
        distance = min(max(requested, abs(a - b) + .00001), (a + b) * .995)
        axis = direction.normalized()
        side = Vector(pole) - origin; side -= axis * side.dot(axis); side.normalize()
        along = (a * a - b * b + distance * distance) / (2 * distance)
        elbow = origin + axis * along + side * math.sqrt(max(0, a * a - along * along))
        goal = origin + axis * distance
        self.aim(upper, lower, elbow); self.aim(lower, end, goal)
        if keep_end_orientation:
            end_world.translation = self.position(end); self.set_world(end, end_world)
        self.goals.append({'joint': end, 'requested': list(target), 'achieved': list(self.position(end)),
                           'clampedMeters': abs(requested - distance), 'pole': list(pole)})

    def legs(self, targets):
        for side, target in targets.items():
            self.limb('DEF-Thigh1.' + side, 'DEF-Shin1.' + side, 'DEF-Foot.' + side,
                      target, (target.x, .45, target.z + .35))

    def snapshot(self):
        return {'rigId': RIG_ID, 'bones': {self.nodes[index]['name']:
            [float(self.local[index][row][column]) for column in range(4) for row in range(4)]
            for index in self.joints}}


def create(document, name):
    pose = Pose(document)
    feet = {side: pose.position('DEF-Foot.' + side) for side in ('L', 'R')}
    if name == 'side_stand':
        pose.move('DEF-Pelvis', (.012, -.004, 0)); pose.legs(feet)
        pose.rotate('PhotoRoot', (0, 1, 0), 40)
        pose.rotate('DEF-Neck', (0, 1, 0), -10)
        pose.rotate('DEF-Head', (0, 1, 0), -10)
    elif name == 'look_back':
        pose.move('DEF-Pelvis', (-.010, -.004, 0)); pose.legs(feet)
        pose.rotate('PhotoRoot', (0, 1, 0), 95)
        pose.rotate('DEF-Spine2', (0, 1, 0), -10)
        pose.rotate('DEF-Spine3', (0, 1, 0), -10)
        pose.rotate('DEF-Neck', (0, 1, 0), -30)
        pose.rotate('DEF-Head', (0, 1, 0), -35)
    elif name == 'hand_hip':
        pose.move('DEF-Pelvis', (-.012, -.004, 0)); pose.legs(feet)
        hip = pose.position('DEF-Pelvis')
        hand = Vector((hip.x + .165, hip.y + .075, hip.z + .055))
        pose.limb('DEF-Upperarm1.L', 'DEF-Forearm1.L', 'DEF-Hand.L', hand,
                  (hip.x + .38, hip.y + .20, hip.z - .025))
        pose.aim('DEF-Hand.L', 'DEF-Middle1.L', hand + Vector((.015, -.08, .005)))
        pose.rotate('PhotoRoot', (0, 1, 0), 12)
    elif name == 'walk_pause':
        feet['L'] += Vector((.005, 0, .12)); feet['R'] += Vector((-.005, 0, -.04))
        pose.move('DEF-Pelvis', (.012, -.012, .025)); pose.legs(feet)
        for side, forward in (('L', -.035), ('R', .05)):
            hand = pose.position('DEF-Hand.' + side) + Vector((0, .015, forward))
            shoulder = pose.position('DEF-Upperarm1.' + side)
            pose.limb('DEF-Upperarm1.' + side, 'DEF-Forearm1.' + side, 'DEF-Hand.' + side,
                      hand, (shoulder.x + (.16 if side == 'L' else -.16), shoulder.y - .18, shoulder.z - .08))
        pose.rotate('PhotoRoot', (0, 1, 0), 15)
    return pose


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--repo', required=True, type=Path)
    args = parser.parse_args(sys.argv[sys.argv.index('--') + 1:]); repo = args.repo.resolve()
    source = repo / 'feature-camera-vlm/src/main/assets/photo_samples/rain-natural-v1/avatar.glb'
    data = source.read_bytes(); length = struct.unpack_from('<I', data, 12)[0]
    document = json.loads(data[20:20 + length])
    output = repo / 'feature-camera-vlm/src/main/assets/photo_samples/rain-poses-v1'
    output.mkdir(parents=True, exist_ok=True)
    records = {}
    for name in POSES:
        pose = create(document, name)
        (output / (name + '.json')).write_text(json.dumps(pose.snapshot(), indent=2) + '\n')
        records[name] = {'goals': pose.goals, 'jointsWritten': len(pose.joints), 'readiness': 'DRAFT'}
        print('POSE_WRITTEN', name, len(pose.joints), flush=True)
    (output / 'NOTICE.md').write_text('Rain Rig (CC) Blender Foundation | studio.blender.org\n'
        'https://studio.blender.org/characters/rain/v3/\nCC BY 4.0 https://creativecommons.org/licenses/by/4.0/\n'
        'Framer Sense 制作完整骨骼摄影姿态初稿，尚未通过人工摄影验收。\n')
    artifact = repo / 'artifacts/camera-cartoon-poses-v1'; artifact.mkdir(parents=True, exist_ok=True)
    (artifact / 'authoring.json').write_text(json.dumps({'rigId': RIG_ID, 'poseVersion': VERSION,
        'source': str(source.relative_to(repo)), 'poses': records, 'readiness': 'DRAFT',
        'renderValidation': 'NOT_RUN_USER_REQUEST', 'automatedTests': 'NOT_RUN_USER_REQUEST',
        'professionalApproval': False}, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__': main()
