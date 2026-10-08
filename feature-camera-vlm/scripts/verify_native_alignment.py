"""检查合并后的 ARM64 ELF；只读文件，不打包 APK。"""
import argparse
import hashlib
import struct
from pathlib import Path


def verify(directory: Path, runtime: Path) -> None:
    libraries = sorted(directory.glob("*.so"))
    if not libraries:
        raise SystemExit("未找到合并后的 ARM64 原生库，请先运行 :app:mergeDebugNativeLibs")
    for library in libraries:
        data = library.read_bytes()
        assert data[:4] == b"\x7fELF" and data[4:6] == bytes((2, 1)), library.name
        offset = struct.unpack_from("<Q", data, 32)[0]
        size, count = struct.unpack_from("<HH", data, 54)
        loads = []
        for index in range(count):
            entry = offset + index * size
            if struct.unpack_from("<I", data, entry)[0] == 1:
                file_offset, address = struct.unpack_from("<QQ", data, entry + 8)
                alignment = struct.unpack_from("<Q", data, entry + 48)[0]
                loads.append(alignment)
                assert alignment >= 16384 and file_offset % 16384 == address % 16384, library.name
        assert loads, library.name
        print(f"{library.name}: 16 KiB ELF 对齐通过")
    merged = directory / "libc++_shared.so"
    assert runtime.exists() and merged.exists(), "缺少工程 C++ 运行库"
    assert hashlib.sha256(runtime.read_bytes()).digest() == hashlib.sha256(merged.read_bytes()).digest(), "共享运行库来源不一致"
    print(f"共 {len(libraries)} 个 ARM64 库通过，C++ 运行库与工程 NDK 产物一致。APK ZIP 对齐需打包后另验。")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, default=Path("app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a"))
    parser.add_argument("--runtime", type=Path, default=Path("feature-camera-vlm/build/intermediates/library_jni/debug/copyDebugJniLibsProjectOnly/jni/arm64-v8a/libc++_shared.so"))
    args = parser.parse_args()
    verify(args.directory, args.runtime)
