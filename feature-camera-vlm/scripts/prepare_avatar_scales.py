#!/usr/bin/env python3
"""从现有 GLB 的参考姿态蒙皮顶点生成尺度元数据；不修改角色或动作。
Rain 用独立头部/鞋底网格；Rocketbox 用不透明头部/身体网格，排除 opacity 附件。
这些是资源坐标测量，摄影外观及脚底接触仍需真机验收。
"""
import json
import math
import pathlib
import struct

ROOT = pathlib.Path(__file__).resolve().parents[1] / "src/main/assets"

def mul(a, b):
    return [sum(a[k*4+r]*b[c*4+k] for k in range(4)) for c in range(4) for r in range(4)]

def trs(node):
    if 'matrix' in node:
        return node['matrix']
    x,y,z,w = node.get('rotation',[0,0,0,1]); sx,sy,sz = node.get('scale',[1,1,1])
    tx,ty,tz = node.get('translation',[0,0,0])
    return [(1-2*y*y-2*z*z)*sx,(2*x*y+2*w*z)*sx,(2*x*z-2*w*y)*sx,0,
            (2*x*y-2*w*z)*sy,(1-2*x*x-2*z*z)*sy,(2*y*z+2*w*x)*sy,0,
            (2*x*z+2*w*y)*sz,(2*y*z-2*w*x)*sz,(1-2*x*x-2*y*y)*sz,0,tx,ty,tz,1]

def measure(path, version):
    data = (ROOT/path).read_bytes(); size = struct.unpack_from('<I',data,12)[0]
    doc = json.loads(data[20:20+size]); binary = data[28+size:]
    def accessor(index):
        a = doc['accessors'][index]; v = doc['bufferViews'][a['bufferView']]
        count = {'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4,'MAT4':16}[a['type']]
        fmt = {5126:'f',5123:'H',5121:'B',5125:'I'}[a['componentType']]
        width = struct.calcsize('<'+fmt)*count; stride = v.get('byteStride',width)
        start = v.get('byteOffset',0)+a.get('byteOffset',0)
        result = [struct.unpack_from('<'+fmt*count,binary,start+i*stride) for i in range(a['count'])]
        if a.get('normalized') and fmt in ('B','H'):
            limit = 255 if fmt == 'B' else 65535
            result = [tuple(x/limit for x in row) for row in result]
        return result
    # 首帧动画作为参考姿态；不能从后续摄影动作的包围盒重新归一化。
    for channel in doc.get('animations',[{}])[0].get('channels',[]):
        sampler = doc['animations'][0]['samplers'][channel['sampler']]
        target = channel['target']; prop = target['path']
        if prop in ('rotation','translation','scale'):
            values = accessor(sampler['output']); doc['nodes'][target['node']][prop] = list(values[1 if sampler.get('interpolation') == 'CUBICSPLINE' else 0])
    parents = {c:i for i,n in enumerate(doc['nodes']) for c in n.get('children',[])}
    worlds = {}
    def world(i):
        if i not in worlds:
            local = trs(doc['nodes'][i]); worlds[i] = mul(world(parents[i]),local) if i in parents else local
        return worlds[i]
    head=[]; feet=[]
    for i,node in enumerate(doc['nodes']):
        if 'mesh' not in node: continue
        skin = doc.get('skins',[])[node['skin']] if 'skin' in node else None
        joints = [mul(world(j),list(ibm)) for j,ibm in zip(skin['joints'],accessor(skin['inverseBindMatrices']))] if skin else None
        for primitive in doc['meshes'][node['mesh']]['primitives']:
            name = node.get('name','')+' '+doc.get('materials',[{}])[primitive.get('material',0)].get('name','')
            is_head = 'head-Photo' in name or name.endswith('_head')
            is_feet = 'shoes-Photo' in name or name.endswith('_body')
            if not is_head and not is_feet: continue
            attrs = primitive['attributes']; positions = accessor(attrs['POSITION'])
            ids = accessor(attrs['JOINTS_0']) if joints else None; weights = accessor(attrs['WEIGHTS_0']) if joints else None
            for k,p in enumerate(positions):
                if joints:
                    m = [sum(joints[int(j)][n]*w for j,w in zip(ids[k],weights[k])) for n in range(16)]
                else: m = world(i)
                value = tuple(sum(m[c*4+r]*p[c] for c in range(3))+m[12+r] for r in range(3))
                if is_head: head.append(value)
                if is_feet: feet.append(value)
    if not head or not feet: raise ValueError('缺少可测量头部/脚底网格: '+path)
    top=max(head,key=lambda p:p[1]); bottom=min(feet,key=lambda p:p[1])
    # 保留相同中心线，不把鼻子/鞋尖偏移当作身体站位。
    center_x=(min(p[0] for p in feet)+max(p[0] for p in feet))/2
    foot={'x':center_x,'y':bottom[1],'z':0.0}; crown={'x':center_x,'y':top[1],'z':0.0}
    return dict(assetPath=path,assetVersion=version,version=1,foot=foot,crown=crown,
                forward={'x':0.0,'y':0.0,'z':1.0},metersPerUnit=1.0,
                source='GLB reference skin; opaque head/body or Rain head/shoes; excludes separate hair/opacity meshes')

if __name__ == '__main__':
    assets = [('vlm_avatars/'+name+'.glb',1) for name in ['adult_female','adult_male','girl','boy']]
    assets += [('photo_samples/rain-natural-v1/'+name+'.glb',version) for name,version in [('white',5),('asian',5),('black',4)]]
    values = [measure(path,version) for path,version in assets]
    (ROOT/'avatar-scales.json').write_text(json.dumps(values,ensure_ascii=False,indent=2)+'\n')
    for item in values: print(item['assetPath'],round(item['crown']['y']-item['foot']['y'],4))
