#!/usr/bin/env bash
# 准备固定版本 MNN 源码；无参数，不运行 Gradle、不下载权重、不覆盖已有源码目录。
set -euo pipefail
script_dir="$(cd "$(dirname "$0")" && pwd)"
target_dir="$script_dir/../third_party/MNN"
mkdir -p "$(dirname "$target_dir")"
if [ -d "$target_dir" ]; then
    actual_tag="$(git -C "$target_dir" describe --tags --exact-match HEAD)"
    [ "$actual_tag" = "3.6.1" ] || { echo '现有 MNN 不是 3.6.1，请人工检查。' >&2; exit 1; }
    echo 'MNN 3.6.1 已准备，请在本地构建。'
    exit 0
fi
git clone --depth 1 --branch 3.6.1 --recurse-submodules https://github.com/alibaba/MNN.git "$target_dir"
echo 'MNN 3.6.1 源码已准备；未运行构建。'
