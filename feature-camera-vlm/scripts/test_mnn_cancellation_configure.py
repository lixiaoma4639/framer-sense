"""取消补丁配置回归：只配置微型 CMake 工程，不构建 Android、不加载模型。由用户运行。"""
import difflib
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


MODULE = Path(__file__).resolve().parents[1]
HELPER = MODULE / "src/main/cpp/cmake/MnnCancellation.cmake"
ENGINE = "transformers/llm/engine"


@unittest.skipUnless(shutil.which("git") and shutil.which("cmake"), "需要 Git、CMake 和宿主 C++ 编译器")
class CancellationConfigureTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="vlm-cancel-config-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        self.feature = self.root / "feature"
        helper = self.feature / "src/main/cpp/cmake/MnnCancellation.cmake"
        helper.parent.mkdir(parents=True)
        shutil.copyfile(HELPER, helper)
        patch = self.feature / "patches/mnn-3.6.1-cancellation.patch"
        patch.parent.mkdir(parents=True)
        self.vendor = self.root / "vendor"
        self.sources = {
            f"{ENGINE}/include/llm/llm.hpp": ("// original header\n", "void setCancelCallback();\n"),
            f"{ENGINE}/src/llm.cpp": ("void originalLlm() {}\n", "void setCancelCallback() {}\n"),
            f"{ENGINE}/src/omni.hpp": ("// original omni header\n", "void resetCancelledRequest();\n"),
            f"{ENGINE}/src/omni.cpp": ("void originalOmni() {}\n", "void resetCancelledRequest() {}\n"),
        }
        patches = []
        for relative, (original, modified) in self.sources.items():
            source = self.vendor / relative
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(original)
            patches.extend(difflib.unified_diff(original.splitlines(True), modified.splitlines(True),
                fromfile="a/" + relative, tofile="b/" + relative))
        patch.write_text("".join(patches))
        photo_patch = self.feature / "patches/mnn-3.6.1-photography.patch"
        self.photo_sources = {}
        photo_diffs = []
        for relative, (_, cancelled) in self.sources.items():
            if relative.endswith("/llm.hpp"):
                modified = cancelled + "void setTokenConstraint();\n"
            elif relative.endswith("/llm.cpp"):
                modified = cancelled + "// mTokenConstraint(logits, token_id)\n"
            else:
                continue
            self.photo_sources[relative] = modified
            photo_diffs.extend(difflib.unified_diff(cancelled.splitlines(True), modified.splitlines(True),
                fromfile="a/" + relative, tofile="b/" + relative))
        photo_patch.write_text("".join(photo_diffs))
        self.project = self.feature / "src/main/cpp"
        (self.project / "CMakeLists.txt").write_text("""
cmake_minimum_required(VERSION 3.22.1)
project(cancellation_fixture LANGUAGES CXX)
get_filename_component(MNN_SOURCE "${CMAKE_CURRENT_LIST_DIR}/../../../../vendor" REALPATH)
add_library(llm STATIC "${MNN_SOURCE}/transformers/llm/engine/src/llm.cpp" "${MNN_SOURCE}/transformers/llm/engine/src/omni.cpp")
add_executable(apply_template EXCLUDE_FROM_ALL "${MNN_SOURCE}/transformers/llm/engine/src/llm.cpp")
include("${CMAKE_CURRENT_LIST_DIR}/cmake/MnnCancellation.cmake")
""")
        self.build = self.feature / ".cxx/Debug/fixture/arm64-v8a"

    def configure(self, *extra):
        return subprocess.run(["cmake", "-S", str(self.project), "-B", str(self.build), *extra],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def assert_generated_sources(self):
        for relative, (original, modified) in self.sources.items():
            self.assertEqual(original, (self.vendor / relative).read_text())
            self.assertEqual(self.photo_sources.get(relative, modified), (self.build / "mnn-cancellation" / relative).read_text())

    def test_patch_applies_under_nested_repository_directory(self):
        result = self.configure()
        self.assertEqual(0, result.returncode, result.stdout)
        self.assert_generated_sources()

    def test_reconfiguration_recopies_and_reapplies_without_double_patch(self):
        first = self.configure()
        self.assertEqual(0, first.returncode, first.stdout)
        second = self.configure()
        self.assertEqual(0, second.returncode, second.stdout)
        self.assert_generated_sources()

    def test_success_exit_code_without_changes_is_rejected(self):
        fake_git = self.root / "fake-git"
        fake_git.write_text('#!/bin/sh\nif [ "$1" = "--version" ]; then echo "git version 2.44.0"; fi\nexit 0\n')
        fake_git.chmod(0o755)
        result = self.configure("-DGIT_EXECUTABLE=" + str(fake_git))
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("补丁未生效", result.stdout)


if __name__ == "__main__":
    unittest.main()
