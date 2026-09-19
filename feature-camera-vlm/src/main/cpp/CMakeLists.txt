# 声明构建此工程所需的最低 CMake 版本；Android Gradle Plugin 会使用它解析后续指令。
cmake_minimum_required(VERSION 3.22.1)

# 定义当前 CMake 工程名为 vlm_mnn，并启用 C 与 C++ 两种编程语言。
project(vlm_mnn LANGUAGES C CXX)

# 要求所有 C++ 源文件按 C++17 标准编译，以满足本模块和 MNN 的语言特性需求。
set(CMAKE_CXX_STANDARD 17)

# 为静态库及其目标启用位置无关代码（PIC），使其可被安全链接进 Android 的 .so 动态库。
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# 计算 MNN 源码目录：从当前 CMakeLists.txt 所在目录回退三级，再进入 third_party/MNN。
set(MNN_SOURCE "${CMAKE_CURRENT_LIST_DIR}/../../../third_party/MNN")

# 在配置阶段检查 MNN 根目录的 CMakeLists.txt 是否存在，避免后续构建出现难以定位的错误。
if(NOT EXISTS "${MNN_SOURCE}/CMakeLists.txt")
    # 立即终止 CMake 配置，并提示开发者先准备所需的 MNN 源码。
    message(FATAL_ERROR "缺少 MNN 3.6.1 源码。请先运行 feature-camera-vlm/scripts/prepare_mnn.sh，再由本地 Gradle 构建。")
# 结束上面的 MNN 源码存在性判断。
endif()

# 将 MNN 编译为共享库；CACHE BOOL 让该值进入 CMake 缓存，FORCE 确保不被旧缓存覆盖。
set(MNN_BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)

# 告知 MNN 当前由 Android 构建链路调用，以启用其 Android 平台相关配置。
set(MNN_BUILD_FOR_ANDROID_COMMAND ON CACHE BOOL "" FORCE)

# 关闭 MNN 的拆分构建模式，使当前工程按单一 MNN 目标链接。
set(MNN_SEP_BUILD OFF CACHE BOOL "" FORCE)

# 启用 MNN LLM 模块，提供大语言/视觉语言模型推理所需能力。
set(MNN_BUILD_LLM ON CACHE BOOL "" FORCE)

# 启用 Transformer 算子融合，以改善 Transformer 模型推理性能。
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "" FORCE)

# 启用 MNN 的 OpenCV 相关能力，供图像预处理流程使用。
set(MNN_BUILD_OPENCV ON CACHE BOOL "" FORCE)

# 启用图片编解码能力，使 MNN 可处理常见图像编码格式。
set(MNN_IMGCODECS ON CACHE BOOL "" FORCE)

# 关闭音频模块，因为当前视觉语言模型流程不需要音频推理能力。
set(MNN_BUILD_AUDIO OFF CACHE BOOL "" FORCE)

# 关闭 MNN LLM 示例程序，避免将演示可执行文件纳入 Android 构建。
set(MNN_LLM_BUILD_DEMO OFF CACHE BOOL "" FORCE)

# 关闭 MNN 通用示例程序，减少无关编译产物与构建时间。
set(MNN_BUILD_DEMO OFF CACHE BOOL "" FORCE)

# 关闭 MNN 开发工具构建，应用运行时不需要这些工具。
set(MNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)

# 关闭模型转换器构建；模型转换应在开发环境完成，不应集成到 Android App。
set(MNN_BUILD_CONVERTER OFF CACHE BOOL "" FORCE)

# 关闭 MNN 测试目标，避免测试代码进入应用的 Native 构建流程。
set(MNN_BUILD_TEST OFF CACHE BOOL "" FORCE)

# 关闭 LLM 的 HTTP 资源下载功能；本模块使用本地已下载的模型文件。
set(LLM_SUPPORT_HTTP_RESOURCE OFF CACHE BOOL "" FORCE)

# 将 MNN 源码作为子工程纳入当前构建；第二个参数指定其生成文件单独放在当前构建目录的 mnn 子目录。
add_subdirectory("${MNN_SOURCE}" "${CMAKE_CURRENT_BINARY_DIR}/mnn")

# 不让 MNN 的 apply_template 辅助目标默认参与 ALL 构建目标，避免产生当前应用不需要的产物。
set_target_properties(apply_template PROPERTIES EXCLUDE_FROM_ALL TRUE)

# 为 MNN 链接器设置 16 KB 最大页大小兼容参数，以适配采用 16 KB 内存页的 Android 设备。
target_link_options(MNN PRIVATE "-Wl,-z,max-page-size=16384")

# 将 JNI 桥接源码 mnn_bridge.cpp 编译为名为 vlm_mnn 的 Android 共享库，即 libvlm_mnn.so。
add_library(vlm_mnn SHARED mnn_bridge.cpp)

# 为 vlm_mnn 的编译过程添加 MNN 公共头文件和 LLM 引擎头文件搜索目录；PRIVATE 表示不向依赖方传递。
target_include_directories(vlm_mnn PRIVATE "${MNN_SOURCE}/include" "${MNN_SOURCE}/transformers/llm/engine/include")

# 将 vlm_mnn 链接到 MNN 推理库与 Android log 库，以使用 MNN API 和 __android_log_print 等日志能力。
target_link_libraries(vlm_mnn MNN log)

# 为最终 JNI 共享库设置同样的 16 KB 最大页大小链接参数，确保 vlm_mnn 也具备相同兼容性。
target_link_options(vlm_mnn PRIVATE "-Wl,-z,max-page-size=16384")
