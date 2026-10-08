"""验证离线视觉输入前缀，不加载模型、不构建 APK。需要宿主 C++17 编译器。"""
import os
import subprocess
import tempfile
from pathlib import Path


if __name__ == "__main__":
    source = Path(__file__).resolve().parents[1] / "src/test/cpp/local_image_prompt_test.cpp"
    with tempfile.TemporaryDirectory(prefix="vlm-image-prefix-") as directory:
        executable = Path(directory) / "image-prefix-test"
        subprocess.run([os.environ.get("CXX", "c++"), "-std=c++17", "-Wall", "-Wextra", "-Werror", str(source), "-o", str(executable)], check=True)
        subprocess.run([str(executable)], check=True)
