"""将已转换的 Qwen3-VL MNN 导出目录打包为应用可导入 ZIP；不下载权重，不改变原目录。"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import tempfile
import zipfile

MODEL = "Qwen3-VL-2B-Instruct"
RUNTIME = "MNN-3.6.1"
REQUIRED = {
    "llm_config": "llm_config.json", "llm_model": "llm.mnn",
    "llm_weight": "llm.mnn.weight", "tokenizer_file": "tokenizer.txt",
    "visual_model": "visual.mnn",
}


def checked_path(root: Path, name: str) -> Path:
    """解析模型相对路径。root 为导出目录，name 为配置路径；返回无符号链接且不越界的普通文件。"""
    if not isinstance(name, str) or not name or "\\" in name or ":" in name or any(p in ("", ".", "..") for p in name.split("/")) or PurePosixPath(name).is_absolute():
        raise ValueError(f"非法包内路径：{name}")
    path = root / name
    if any(p.is_symlink() for p in (path, *path.parents)) or not path.is_file() or not path.resolve().is_relative_to(root.resolve()):
        raise ValueError(f"文件缺失、符号链接或路径越界：{name}")
    if path.stat().st_size <= 0:
        raise ValueError(f"文件为空：{name}")
    return path


def read_config(root: Path, name: str) -> dict:
    """读取有大小上限的配置。root 为导出目录，name 为配置名；返回 JSON 对象。"""
    path = checked_path(root, name)
    if path.stat().st_size > 2 * 1024 * 1024:
        raise ValueError(f"配置过大：{name}")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"配置必须为对象：{name}")
    return value


def referenced_files(config: dict, root: Path) -> set[str]:
    """递归收集安全配置引用。config 为配置对象，root 为导出目录；返回必须打包的文件路径。"""
    files = set()
    for key, value in config.items():
        if isinstance(value, dict):
            files.update(referenced_files(value, root))
        if key in {"base_dir", "tmp_path", "prefix_cache_path", "draft_model", "npu_model_dir"}:
            raise ValueError(f"首版不支持配置 {key}；请在导出副本中移除后再打包")
        if key == "backend_type" and value != "cpu":
            raise ValueError("首版仅支持 CPU 后端")
        if key == "speculative_type" and value != "none":
            raise ValueError("首版不支持推测解码")
        if key.endswith(("_file", "_model", "_weight")) or key == "llm_config":
            checked_path(root, value)
            files.add(value)
    return files


def sha256(path: Path) -> str:
    """流式计算 SHA-256。path 为普通模型文件；返回十六进制摘要，退出关闭文件。"""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def package_model(source: Path, destination: Path) -> None:
    """创建原子提交的 ZIP。source 为 MNN 导出目录，destination 为尚不存在的输出 ZIP；失败清理临时文件。"""
    source = source.resolve()
    destination = destination.absolute()
    if destination.exists():
        raise ValueError("输出文件已存在，请指定新路径")
    config = read_config(source, "config.json")
    info = read_config(source, "llm_config.json")
    if config.get("llm_config", "llm_config.json") != "llm_config.json":
        raise ValueError("元配置必须为 llm_config.json")
    merged = config | info
    if merged.get("is_visual") is not True or merged.get("model_type") != "qwen3_vl":
        raise ValueError("需要真实 qwen3_vl 视觉模型配置")
    if merged.get("is_single") is False or merged.get("is_audio") is True:
        raise ValueError("首版仅支持单体图文模型")
    names = {"config.json", "llm_config.json"} | referenced_files(config, source) | referenced_files(info, source)
    for key, fallback in REQUIRED.items():
        name = merged.get(key, fallback)
        checked_path(source, name)
        names.add(name)
    if "tie_embeddings" not in merged:
        names.add(merged.get("embedding_file", "embeddings_bf16.bin"))
    # MNN 视觉网络可把权重放在同名 .weight 文件，必须随主图一起收录。
    for name in list(names):
        if name.endswith(".mnn") and (source / (name + ".weight")).exists():
            names.add(name + ".weight")
    files = []
    for name in sorted(names):
        path = checked_path(source, name)
        files.append({"path": name, "size": path.stat().st_size, "sha256": sha256(path)})
    if sum(f["size"] for f in files) > 12 * 1024**3:
        raise ValueError("模型包解压后超过应用的 12 GiB 上限")
    manifest = {"model": MODEL, "runtime": RUNTIME, "files": files}
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix="vlm-package-", suffix=".zip", dir=destination.parent)
    os.close(fd)
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
            archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
            for entry in files:
                archive.write(source / entry["path"], entry["path"])
        # 同一目录的硬链接提交，已有目的文件时明确失败，避免覆盖用户文件。
        os.link(temporary, destination)
    finally:
        Path(temporary).unlink(missing_ok=True)
    print(f"已打包 {len(files)} 个文件：{destination}；尚未验证设备推理。")


def main() -> None:
    """解析命令行并打包；无参数，命令行 source/destination 分别指定导出目录和新 ZIP 路径。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Qwen3-VL-2B-Instruct 的 MNN 3.6.1 导出目录")
    parser.add_argument("destination", type=Path, help="新建的 ZIP 路径")
    args = parser.parse_args()
    package_model(args.source, args.destination)


if __name__ == "__main__":
    main()
