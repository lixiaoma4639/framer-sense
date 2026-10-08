#pragma once
#include <stdexcept>
#include <string>
#include <vector>

inline constexpr size_t kMaxLocalImages = 4;

/** 每张图都进入 Omni 视觉通道，编号与 Kotlin 提交顺序一致。 */
inline std::string localImagePrefix(const std::vector<std::string>& paths) {
    if (paths.empty() || paths.size() > kMaxLocalImages) throw std::runtime_error("LOCAL_VIEW_INPUT");
    std::string prefix;
    for (size_t i = 0; i < paths.size(); ++i) {
        const auto& path = paths[i];
        if (path.empty() || path.front() != '/' || path.find_first_of("<>\r\n") != std::string::npos)
            throw std::runtime_error("LOCAL_VIEW_INPUT");
        if (paths.size() > 1) prefix += "Picture " + std::to_string(i + 1) + ":\n";
        prefix += "<img>" + path + "</img>\n";
    }
    return prefix;
}

/** 用户文本不得引入额外媒体；只有应用生成的私有路径进入视觉通道。 */
inline std::string localVisualPrompt(const std::vector<std::string>& paths, const std::string& prompt) {
    std::string escaped;
    for (char c : prompt) {
        if (c == '<') escaped += "\\u003c";
        else if (c == '>') escaped += "\\u003e";
        else escaped += c;
    }
    return localImagePrefix(paths) + escaped;
}
