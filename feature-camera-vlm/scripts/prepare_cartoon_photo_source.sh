#!/usr/bin/env bash
# 只准备官方素材；不执行素材内嵌脚本，不改系统 Blender 安装。
set -euo pipefail
task_repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
task_assets="$task_repo/.tools/rain-v33"
mkdir -p "$task_assets"
if [[ ! -f "$task_assets/rain-v33.zip" ]]; then
  curl --fail --location --retry 2 --max-time 180 \
    'https://studio.blender.org/download-source/files/ee/a7/eea73e55dba1cea31c09848df6a794b2-4.zip' \
    -o "$task_assets/rain-v33.zip.part"
  mv "$task_assets/rain-v33.zip.part" "$task_assets/rain-v33.zip"
fi
python3 - "$task_assets" <<'PY'
import hashlib, pathlib, sys, zipfile
folder = pathlib.Path(sys.argv[1])
source = folder / 'rain-v33.zip'
assert hashlib.sha256(source.read_bytes()).hexdigest() == '80217f163f6392dc829233d63c2cfb5e1376775bc34101ad14f39631fea70d24', 'RAIN_ARCHIVE_CHANGED'
with zipfile.ZipFile(source) as archive:
    for entry in archive.infolist():
        name = pathlib.PurePosixPath(entry.filename)
        assert not name.is_absolute() and '..' not in name.parts
        assert (entry.external_attr >> 16) & 0o170000 != 0o120000, 'ARCHIVE_SYMLINK_REJECTED'
    archive.extractall(folder)
print('Rain 官方源素材已校验、解压；许可 CC BY 4.0，署名要求见资源 NOTICE.md。')
PY
