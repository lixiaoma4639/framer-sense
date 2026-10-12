# 仅在构建目录生成带取消支持的四个文件，保留 third_party 原始 checkout。
# 补丁锚点不匹配时明确中止配置，禁止静默编译不可取消的版本。
find_package(Git REQUIRED)
set(VLM_MNN_CANCEL_PATCH "${CMAKE_CURRENT_LIST_DIR}/../../../../patches/mnn-3.6.1-cancellation.patch")
set(VLM_MNN_CANCEL_SOURCE "${CMAKE_CURRENT_BINARY_DIR}/mnn-cancellation")
set(VLM_MNN_CANCEL_FILES
    transformers/llm/engine/include/llm/llm.hpp
    transformers/llm/engine/src/llm.cpp
    transformers/llm/engine/src/omni.hpp
    transformers/llm/engine/src/omni.cpp)
foreach(relative IN LISTS VLM_MNN_CANCEL_FILES)
    get_filename_component(parent "${VLM_MNN_CANCEL_SOURCE}/${relative}" DIRECTORY)
    file(MAKE_DIRECTORY "${parent}")
    configure_file("${MNN_SOURCE}/${relative}" "${VLM_MNN_CANCEL_SOURCE}/${relative}" COPYONLY)
endforeach()
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${VLM_MNN_CANCEL_PATCH}")
execute_process(
    # .cxx 位于仓库子目录；必须阻止 Git 发现父仓库，否则子目录前缀过滤
    # 可以跳过全部补丁且返回 0。此处只对构建副本作无索引补丁操作。
    COMMAND "${CMAKE_COMMAND}" -E env
        --unset=GIT_DIR --unset=GIT_WORK_TREE
        "GIT_CEILING_DIRECTORIES=${CMAKE_CURRENT_BINARY_DIR}"
        "${GIT_EXECUTABLE}" apply --unsafe-paths "--directory=${VLM_MNN_CANCEL_SOURCE}" "${VLM_MNN_CANCEL_PATCH}"
    WORKING_DIRECTORY "${VLM_MNN_CANCEL_SOURCE}"
    RESULT_VARIABLE patch_result
    OUTPUT_VARIABLE patch_output
    ERROR_VARIABLE patch_error)
if(NOT patch_result EQUAL 0)
    message(FATAL_ERROR "MNN 3.6.1 取消补丁无法应用；未修改 third_party，请检查源码版本。${patch_output}${patch_error}")
endif()

# 摄影约束使用同一份构建副本和类布局；不修改官方 checkout。
set(VLM_MNN_PHOTO_PATCH "${CMAKE_CURRENT_LIST_DIR}/../../../../patches/mnn-3.6.1-photography.patch")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${VLM_MNN_PHOTO_PATCH}")
execute_process(
    COMMAND "${CMAKE_COMMAND}" -E env --unset=GIT_DIR --unset=GIT_WORK_TREE
        "GIT_CEILING_DIRECTORIES=${CMAKE_CURRENT_BINARY_DIR}"
        "${GIT_EXECUTABLE}" apply --unsafe-paths "--directory=${VLM_MNN_CANCEL_SOURCE}" "${VLM_MNN_PHOTO_PATCH}"
    WORKING_DIRECTORY "${VLM_MNN_CANCEL_SOURCE}"
    RESULT_VARIABLE photo_result ERROR_VARIABLE photo_error)
if(NOT photo_result EQUAL 0)
    message(FATAL_ERROR "MNN 摄影约束补丁无法应用：${photo_error}")
endif()
file(READ "${VLM_MNN_CANCEL_SOURCE}/transformers/llm/engine/include/llm/llm.hpp" photo_header)
file(READ "${VLM_MNN_CANCEL_SOURCE}/transformers/llm/engine/src/llm.cpp" photo_impl)
if(NOT photo_header MATCHES "setTokenConstraint" OR NOT photo_impl MATCHES "mTokenConstraint\\(logits, token_id\\)")
    message(FATAL_ERROR "MNN 摄影约束补丁未生效")
endif()

# 不能仅信任退出码：四个补丁文件必须确实改变，且各自包含新增接口/实现。
foreach(relative IN LISTS VLM_MNN_CANCEL_FILES)
    file(SHA256 "${MNN_SOURCE}/${relative}" original_sha)
    file(SHA256 "${VLM_MNN_CANCEL_SOURCE}/${relative}" patched_sha)
    if(original_sha STREQUAL patched_sha)
        message(FATAL_ERROR "MNN 取消补丁未生效：${relative} 仍为原始内容。${patch_output}${patch_error}")
    endif()
    file(READ "${VLM_MNN_CANCEL_SOURCE}/${relative}" patched_content)
    if(relative MATCHES "/llm\\.(hpp|cpp)$")
        set(required_marker "setCancelCallback")
    else()
        set(required_marker "resetCancelledRequest")
    endif()
    string(FIND "${patched_content}" "${required_marker}" marker_index)
    if(marker_index EQUAL -1)
        message(FATAL_ERROR "MNN 取消补丁缺少接口或实现：${relative} / ${required_marker}")
    endif()
endforeach()

# 源码根目录仍指向官方 checkout，仅替换 llm 目标的两个实现文件。
get_target_property(llm_sources llm SOURCES)
foreach(relative IN ITEMS transformers/llm/engine/src/llm.cpp transformers/llm/engine/src/omni.cpp)
    list(FIND llm_sources "${MNN_SOURCE}/${relative}" original_index)
    if(original_index EQUAL -1)
        message(FATAL_ERROR "MNN llm 目标未包含预期源文件：${relative}")
    endif()
    list(REMOVE_AT llm_sources ${original_index})
    list(APPEND llm_sources "${VLM_MNN_CANCEL_SOURCE}/${relative}")
endforeach()
set_property(TARGET llm PROPERTY SOURCES "${llm_sources}")
# 所有 LLM 实现必须使用相同的类布局；生成源码的引号头文件也从此目录查找。
target_include_directories(llm BEFORE PRIVATE
    "${VLM_MNN_CANCEL_SOURCE}/transformers/llm/engine/include"
    "${VLM_MNN_CANCEL_SOURCE}/transformers/llm/engine/src"
    "${MNN_SOURCE}/transformers/llm/engine/src")
target_include_directories(apply_template BEFORE PRIVATE "${VLM_MNN_CANCEL_SOURCE}/transformers/llm/engine/include")
