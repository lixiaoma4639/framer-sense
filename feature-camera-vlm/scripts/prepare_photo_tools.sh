#!/bin/bash
# 仅准备制作工具，Mac Apple Silicon 首批固定 Blender 4.5.14，不修改系统安装。
set -euo pipefail
task_root="$(cd "$(dirname "$0")/../.." && pwd)"
tool_dir="$task_root/.tools/blender-4.5.14"
if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
    echo '首批脚本使用 macOS ARM64；其他平台请从 Blender 官方下载 4.5 LTS。' >&2
    exit 1
fi
mkdir -p "$tool_dir"
if [[ ! -f "$tool_dir/blender.dmg" ]]; then
    curl --fail --location --retry 2 'https://download.blender.org/release/Blender4.5/blender-4.5.14-macos-arm64.dmg' -o "$tool_dir/blender.dmg" ||
        curl --fail --location --retry 2 'https://mirrors.ocf.berkeley.edu/blender/release/Blender4.5/blender-4.5.14-macos-arm64.dmg' -o "$tool_dir/blender.dmg"
fi
actual="$(shasum -a 256 "$tool_dir/blender.dmg" | cut -d ' ' -f 1)"
[[ "$actual" == 65134d9b07b20e2fa8d3c9e44f6f44ffb5c9774dd521b95f50387310241ca170 ]] || { echo 'Blender 镜像校验失败，请重新下载。' >&2; exit 1; }
if [[ ! -d "$tool_dir/Blender.app" ]]; then
    mount_dir="$(mktemp -d /private/tmp/framer-photo-blender.XXXXXX)"
    hdiutil attach "$tool_dir/blender.dmg" -nobrowse -readonly -mountpoint "$mount_dir"
    trap 'hdiutil detach "$mount_dir" >/dev/null' EXIT
    ditto "$mount_dir/Blender.app" "$tool_dir/Blender.app"
fi
echo "$tool_dir/Blender.app/Contents/MacOS/Blender"
