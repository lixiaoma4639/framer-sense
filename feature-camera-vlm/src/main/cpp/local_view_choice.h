#pragma once
#include <cmath>
#include <limits>
#include <stdexcept>
#include <unordered_set>
#include <vector>
#include "local_image_prompt.h"

struct LocalViewChoice {
    int ordinal;
    std::vector<float> scores;
};

// 摄影组合数与输入图片数独立；一张背景可以比较六个摄影组合。
inline constexpr size_t kMaxLocalChoices = 6;

/** 接受单行或完整未填充序列的最后一行；分块/填充位置不明确时拒绝。 */
inline size_t localViewLogitOffset(size_t elements, size_t vocabulary, int promptTokens) {
    if (elements == 0 || vocabulary == 0 || elements % vocabulary != 0 || promptTokens <= 0)
        throw std::runtime_error("LOCAL_VIEW_CHOICE_INVALID");
    const auto rows = elements / vocabulary;
    if (rows == 1) return 0;
    if (rows == static_cast<size_t>(promptTokens)) return elements - vocabulary;
    throw std::runtime_error("LOCAL_VIEW_CHOICE_UNSUPPORTED");
}

/** 仅比较真实模型的合法编号分数；缺失、越界或非有限分数不得兜底选首帧。 */
inline LocalViewChoice chooseLocalView(const float* logits, size_t vocabulary,
                                     const std::vector<int>& digitTokens) {
    if (!logits || digitTokens.empty() || digitTokens.size() > kMaxLocalChoices)
        throw std::runtime_error("LOCAL_VIEW_CHOICE_INVALID");
    std::unordered_set<int> unique;
    LocalViewChoice choice{1, {}};
    float best = std::numeric_limits<float>::lowest();
    for (size_t i = 0; i < digitTokens.size(); ++i) {
        int token = digitTokens[i];
        if (token < 0 || static_cast<size_t>(token) >= vocabulary || !unique.insert(token).second)
            throw std::runtime_error("LOCAL_VIEW_CHOICE_INVALID");
        float score = logits[token];
        if (!std::isfinite(score)) throw std::runtime_error("LOCAL_VIEW_CHOICE_INVALID");
        choice.scores.push_back(score);
        if (i == 0 || score > best) { best = score; choice.ordinal = static_cast<int>(i + 1); }
    }
    return choice;
}
