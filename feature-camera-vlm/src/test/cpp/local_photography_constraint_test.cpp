// 由用户执行，不作为 App 构建目标。
#include "../../main/cpp/local_photography_constraint.h"
#include <cassert>

int main() {
    std::string prompt="PHOTO_ROWS_V1 3 3 7 1 3 0\nPlan from the image.";
    const auto config=localPhotoConfig(prompt);
    assert(prompt=="Plan from the image." && config.plans==3);
    const std::string row=R"(["入口留白",1,0,0,100,25,15,90,20,1,1,80,0,"门框引导视线，保留空间。","保持垂直线，人物避开门框。"] )";
    auto compactRow=row.substr(0,row.size()-1); // 无外部空白。
    const std::string prefix=R"({"s":"暖墙形成层次，入口亮部衬托人物。","p":[)";
    const std::string full=prefix+compactRow+","+compactRow+","+compactRow+"]}";
    LocalPhotoGrammar grammar(config);
    for(char c:full) assert(grammar.accept(std::string(1,c))); // UTF-8 跨 token。
    assert(grammar.complete());
    assert(!grammar.permits("extra") && !grammar.permits("}"));
    LocalPhotoGrammar partial(config);
    assert(partial.accept(prefix+compactRow));
    assert(!partial.complete());
    assert(!partial.permits("]}")); // 不允许一套方案提前结束。
    assert(partial.permits(","));
    for(auto bad:std::vector<std::string>{"```json", "<think>", "{“s”", prefix+R"(["标题",1,0,0,100,125,)"}) {
        LocalPhotoGrammar rejected(config); assert(!rejected.permits(bad));
    }
    LocalPhotoGrammar unsupported(config);
    const auto second=prefix+compactRow+R"(,["侧身呼应",1,0,0,100,50,10,95,25,2,)";
    assert(unsupported.accept(second));
    assert(!unsupported.permits("1,")); // 动作2仅支持平静，不受 prompt 软约束支配。
    assert(unsupported.permits("0,"));
    for (int shot=0;shot<=3;++shot) {
        const int foot=shot<=1 ? 90 : shot==2 ? 150 : 250;
        std::string p="PHOTO_ROWS_V1 1 1 7 0\nPrompt";
        LocalPhotoGrammar layout(localPhotoConfig(p));
        const std::string sample=R"({"s":"门框与暖墙形成纵深。","p":[["入口留白",)"+std::to_string(shot)+
            ",0,0,100,25,10,"+std::to_string(foot)+R"(,20,1,1,80,0,"保留门框，形成留白。","人物避开门框和亮斑。"]]})";
        assert(layout.accept(sample) && layout.complete());
    }
    std::string protectedPrompt="PHOTO_ROWS_V1 1 1 7 1 0.2 0.2 0.8 0.8\nPrompt";
    LocalPhotoGrammar protectedLayout(localPhotoConfig(protectedPrompt));
    assert(!protectedLayout.permits(R"({"s":"门框构成画面主体。","p":[["入口留白",1,70,)"));
    assert(protectedLayout.permits(R"({"s":"门框构成画面主体。","p":[["入口留白",1,0,0,100,)"));
    LocalPhotoGrammar shortSentence(config);
    assert(shortSentence.accept("{\"s\":\""+std::string(27,'a')));
    assert(!shortSentence.permits("\"")); // 句子必须以完整标点结束。
    assert(shortSentence.accept("。"));
    assert(!shortSentence.permits("。") && !shortSentence.permits(".")); // 上限不会退化为无限句号。
    assert(shortSentence.permits("\",\"p\":["));
    LocalPhotoVocabulary vocabWithBytes;
    vocabWithBytes.initialize(3,[](int i) { return std::vector<std::string>{"<|endoftext|>", std::string("\xe3",1), "\xe3\x80\x82"}[i]; },[] { return false; });
    LocalPhotoGrammar bounded(config);
    assert(bounded.accept("{\"s\":\""+std::string(27,'a')));
    const float punctuationScores[]={999,1,2};
    assert(constrainLocalPhotoToken(bounded,vocabWithBytes,punctuationScores,0,[] { return false; })==2);
    LocalPhotoVocabulary vocab;
    vocab.initialize(5,[](int i) { return std::vector<std::string>{"<|endoftext|>","`", "{", "[]", "bad"}[i]; },[] { return false; });
    const float logits[]={999,100,1,50,20};
    LocalPhotoGrammar selected(config);
    assert(constrainLocalPhotoToken(selected,vocab,logits,0,[] { return false; })==2); // 高分 EOS 仍不可选。
    bool cancelled=false;
    try { LocalPhotoGrammar next(config); constrainLocalPhotoToken(next,vocab,logits,0,[] { return true; }); }
    catch (const std::runtime_error&) { cancelled=true; }
    assert(cancelled);
}
