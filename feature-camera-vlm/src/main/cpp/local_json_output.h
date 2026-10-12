#pragma once
#include <cctype>
#include <string>
#include <vector>

/** 仅检查流式 JSON 的边界；语法、方案数量及业务参数仍交给 Kotlin 严格校验。 */
inline bool localJsonOutputComplete(const std::string& text, bool allowFence = false) {
    size_t i = 0;
    auto skipSpace = [&] { while (i < text.size() && std::isspace(static_cast<unsigned char>(text[i]))) ++i; };
    skipSpace();
    if (allowFence && text.compare(i, 3, "\xEF\xBB\xBF") == 0) { i += 3; skipSpace(); }
    if (allowFence && text.compare(i, 7, "<think>") == 0) {
        const auto end = text.find("</think>", i + 7);
        if (end == std::string::npos) return false;
        i = end + 8; skipSpace();
    }
    bool fenced = false;
    if (allowFence && text.compare(i, 3, "```") == 0) {
        fenced = true;
        while (i < text.size() && text[i] == '`') ++i;
        while (i < text.size() && (text[i] == ' ' || text[i] == '\t')) ++i;
        std::string language;
        while (i < text.size() && std::isalpha(static_cast<unsigned char>(text[i]))) {
            language += static_cast<char>(std::tolower(static_cast<unsigned char>(text[i++])));
        }
        if (!language.empty() && language != "json") return false;
        skipSpace();
    }
    if (i == text.size() || (text[i] != '{' && text[i] != '[')) return false;
    std::vector<char> stack;
    bool inString = false, escaped = false, smartQuotes = false;
    for (; i < text.size(); ++i) {
        const char c = text[i];
        if (inString) {
            if (escaped) escaped = false;
            else if (c == '\\') escaped = true;
            else if (smartQuotes && text.compare(i, 3, "\xE2\x80\x9D") == 0) { inString = false; smartQuotes = false; i += 2; }
            else if (!smartQuotes && c == '"') inString = false;
            continue;
        }
        if (c == '"') inString = true;
        else if (allowFence && text.compare(i, 3, "\xE2\x80\x9C") == 0) { inString = true; smartQuotes = true; i += 2; }
        else if (c == '{' || c == '[') stack.push_back(c);
        else if (c == '}' || c == ']') {
            if (stack.empty() || stack.back() != (c == '}' ? '{' : '[')) return false;
            stack.pop_back();
            if (stack.empty()) {
                ++i; skipSpace();
                if (i == text.size()) return true; // 完整 JSON 足够，展示围栏可尚未生成。
                if (!fenced) return false;
                const auto start = i;
                while (i < text.size() && text[i] == '`') ++i;
                if (i - start < 3) return false;
                skipSpace();
                return i == text.size();
            }
        }
    }
    return false;
}
