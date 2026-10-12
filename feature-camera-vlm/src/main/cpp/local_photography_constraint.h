#pragma once
#include <array>
#include <algorithm>
#include <cmath>
#include <functional>
#include <limits>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

// 仅定义传输格式和合法边界，所有可变文字/数值仍由模型 token 分数决定。
struct LocalPhotoConfig {
    int plans = 0;
    std::vector<int> expressions;
    std::vector<std::array<float,4>> keep;
};
inline LocalPhotoConfig localPhotoConfig(std::string& prompt) {
    const auto newline = prompt.find('\n');
    if (newline == std::string::npos) throw std::runtime_error("LOCAL_PHOTO_SCHEMA_MISSING");
    std::istringstream input(prompt.substr(0,newline));
    LocalPhotoConfig config;
    std::string magic;
    int actions = 0, regions = 0;
    if (!(input >> magic >> config.plans >> actions) || magic != "PHOTO_ROWS_V1" ||
        (config.plans != 1 && config.plans != 3) || actions < 1 || actions > 32)
        throw std::runtime_error("LOCAL_PHOTO_SCHEMA_INVALID");
    for (int i=0;i<actions;++i) {
        int mask;
        if (!(input >> mask) || mask < 1 || mask > 7) throw std::runtime_error("LOCAL_PHOTO_EXPRESSIONS_INVALID");
        config.expressions.push_back(mask);
    }
    if (!(input >> regions) || regions < 0 || regions > 16) throw std::runtime_error("LOCAL_PHOTO_REGIONS_INVALID");
    for (int i=0;i<regions;++i) {
        std::array<float,4> r;
        for (auto& v:r) if (!(input >> v) || !std::isfinite(v) || v<0 || v>1)
            throw std::runtime_error("LOCAL_PHOTO_REGIONS_INVALID");
        if (r[2]<=r[0] || r[3]<=r[1]) throw std::runtime_error("LOCAL_PHOTO_REGIONS_INVALID");
        config.keep.push_back(r);
    }
    std::string extra;
    if (input >> extra) throw std::runtime_error("LOCAL_PHOTO_SCHEMA_INVALID");
    prompt.erase(0,newline+1);
    return config;
}

/** 字节级状态机支持跨 token 的 UTF-8；完成前 EOS/Markdown/多余字段没有合法路径。 */
class LocalPhotoGrammar {
    struct Cell { int kind; std::string literal; int field; int maxChars; };
    struct Program { LocalPhotoConfig config; std::vector<Cell> cells; };
    std::shared_ptr<const Program> program_;
    size_t cell_ = 0, offset_ = 0;
    std::string number_;
    mutable std::shared_ptr<const std::vector<std::string>> options_;
    std::array<int,12> values_{};
    int chars_ = 0, utf8_ = 0;
    bool finalPeriod_ = false, sentenceEnded_ = false;
    unsigned int codepoint_ = 0;
    unsigned char nextMin_ = 0x80, nextMax_ = 0xbf;

    bool cropKeeps(int l,int t,int size) const {
        for (const auto& r:program_->config.keep) {
            const float x = std::max(0.f,std::min((l+size)/100.f,r[2])-std::max(l/100.f,r[0]));
            const float y = std::max(0.f,std::min((t+size)/100.f,r[3])-std::max(t/100.f,r[1]));
            if (x*y < .8999f*(r[2]-r[0])*(r[3]-r[1])) return false;
        }
        return true;
    }
    bool cropPossible(int l,int t) const {
        for(int size=20;size<=100-std::max(l,t);size+=5) if(cropKeeps(l,t,size)) return true;
        return false;
    }
    std::vector<std::string> choices(int field) const {
        std::vector<std::string> result;
        const int q=values_[0], l=values_[1], t=values_[2], size=values_[3];
        for(int v=-35;v<=400;++v) {
            bool allowed=false;
            switch(field) {
                case 0: allowed=v>=0 && v<=3; break;
                case 1:
                    if(v>=0 && v<=80 && v%5==0) for(int top=0;top<=80;top+=5)
                        if(cropPossible(v,top)) { allowed=true; break; }
                    break;
                case 2: allowed=v>=0 && v<=80 && v%5==0 && cropPossible(l,v); break;
                case 3: allowed=v>=20 && v<=100-std::max(l,t) && v%5==0 && cropKeeps(l,t,v); break;
                case 4: allowed=v>l && v<l+size && v%5==0; break;
                case 5: allowed=v>=t && v<=t+(q<=1 ? size*.8 : size*.5) && v%5==0; break;
                case 6: {
                    const float span=(v-values_[5])/static_cast<float>(size);
                    const float foot=(v-t)/static_cast<float>(size);
                    const float cut=span>0 ? (foot-1)/span : -1;
                    allowed=v>values_[5] && v%5==0 && span>=.08f && span<=4.f && foot>=.1f && foot<=4.f &&
                        (q<=1 ? v<=t+size : q==2 ? cut>=.201f && cut<=.579f : cut>=.581f && cut<=.839f);
                    break;
                }
                case 7: allowed=v>=5 && v%5==0 && v<=2*std::min(values_[4]-l,l+size-values_[4]); break;
                case 8: allowed=v>=1 && v<=static_cast<int>(program_->config.expressions.size()); break;
                case 9: allowed=v>=0 && v<=2 && (program_->config.expressions.at(values_[8]-1)&(1<<v)); break;
                case 10: allowed=v>=0 && v<=100 && v%10==0; break;
                case 11: allowed=v>=-35 && v<=35 && v%5==0; break;
            }
            if(allowed) result.push_back(std::to_string(v));
        }
        return result;
    }
    void advance() { ++cell_; offset_=0; number_.clear(); options_.reset(); chars_=0; utf8_=0; finalPeriod_=false; sentenceEnded_=false; codepoint_=0; nextMin_=0x80; nextMax_=0xbf; }
    bool byte(unsigned char c) {
        if (cell_==program_->cells.size()) return false;
        const auto& node=program_->cells[cell_];
        if(node.kind==0) {
            if(c!=static_cast<unsigned char>(node.literal[offset_])) return false;
            if(++offset_==node.literal.size()) advance();
            return true;
        }
        if(node.kind==1) {
            if(utf8_>0) {
                if(c<nextMin_ || c>nextMax_) return false;
                codepoint_=(codepoint_<<6)|(c&0x3f);
                --utf8_;
                if(finalPeriod_) {
                    nextMin_=nextMax_=0x82; // 最后一字为完整中文句号，不能停在 UTF-8 半个字。
                    if(utf8_==0) sentenceEnded_=true;
                } else {
                    nextMin_=0x80; nextMax_=0xbf;
                    if(utf8_==0) sentenceEnded_=codepoint_==0x3002 || codepoint_==0xff01 || codepoint_==0xff1f;
                }
                return true;
            }
            if(c=='"') { if(chars_<2 || (node.field==1 && !sentenceEnded_)) return false; advance(); return true; }
            if(chars_>=node.maxChars) return false;
            if(node.field==1 && chars_>=node.maxChars-1) {
                if(c=='.') { ++chars_; sentenceEnded_=true; return true; }
                if(c==0xe3) { ++chars_; codepoint_=3; utf8_=2; nextMin_=nextMax_=0x80; finalPeriod_=true; return true; }
                return false;
            }
            if(chars_>=node.maxChars || c<0x20 || c=='\\' || c=='<' || c=='>' || c=='`') return false;
            if(c<0x80) { ++chars_; sentenceEnded_=c=='.' || c=='!' || c=='?'; return true; }
            if(c>=0xc2 && c<=0xdf) { utf8_=1; codepoint_=c&0x1f; }
            else if(c>=0xe0 && c<=0xef) { utf8_=2; codepoint_=c&0x0f; nextMin_=c==0xe0 ? 0xa0 : 0x80; nextMax_=c==0xed ? 0x9f : 0xbf; }
            else if(c>=0xf0 && c<=0xf4) { utf8_=3; codepoint_=c&0x07; nextMin_=c==0xf0 ? 0x90 : 0x80; nextMax_=c==0xf4 ? 0x8f : 0xbf; }
            else return false;
            ++chars_; return true;
        }
        if(!options_) options_=std::make_shared<const std::vector<std::string>>(choices(node.field));
        const auto& options=*options_;
        const std::string candidate=number_+static_cast<char>(c);
        for(const auto& option:options) if(option.compare(0,candidate.size(),candidate)==0) {
            number_=candidate; return true;
        }
        if(std::find(options.begin(),options.end(),number_)==options.end()) return false;
        values_[node.field]=std::stoi(number_);
        advance();
        return byte(c);
    }
public:
    explicit LocalPhotoGrammar(LocalPhotoConfig config) {
        auto p=std::make_shared<Program>(); p->config=std::move(config);
        auto literal=[&](std::string s) { p->cells.push_back({0,std::move(s),0,0}); };
        auto text=[&](int max,bool sentence=false) { p->cells.push_back({1,"",sentence ? 1 : 0,max}); };
        literal("{\"s\":\""); text(28,true); literal(",\"p\":[");
        for(int row=0;row<p->config.plans;++row) {
            literal(row==0 ? "[\"" : ",[\""); text(6); literal(",");
            for(int field=0;field<12;++field) { p->cells.push_back({2,"",field,0}); literal(","); }
            literal("\""); text(18,true); literal(",\""); text(18,true); literal("]");
        }
        literal("]}"); program_=p;
    }
    bool accept(const std::string& piece) {
        if(piece.empty()) return false;
        for(unsigned char c:piece) if(!byte(c)) return false;
        return true;
    }
    bool permits(const std::string& piece) const {
        if(cell_<program_->cells.size() && program_->cells[cell_].kind==2 && !options_)
            options_=std::make_shared<const std::vector<std::string>>(choices(program_->cells[cell_].field));
        auto trial=*this; return trial.accept(piece);
    }
    bool complete() const { return cell_==program_->cells.size(); }
    std::string position() const { return "cell="+std::to_string(cell_)+"/"+std::to_string(program_->cells.size()); }
};

/** 词表按首字节分桶；非法采样才检查候选，不在每个 token 扫描整个词表。 */
struct LocalPhotoVocabulary {
    std::vector<std::string> pieces;
    std::array<std::vector<int>,256> buckets;
    void initialize(int size,const std::function<std::string(int)>& decode,const std::function<bool()>& cancelled) {
        if(size<1 || size>1000000) throw std::runtime_error("LOCAL_PHOTO_VOCABULARY_INVALID");
        pieces.resize(size);
        for(int id=0;id<size;++id) {
            if(id%256==0 && cancelled()) throw std::runtime_error("LOCAL_PHOTO_CANCELLED");
            auto piece=decode(id);
            // 空 padding、EOS 与聊天控制符不属于摄影输出。所有官方 tokenizer 都检查 id 边界。
            if(piece.empty() || piece.find("<|")!=std::string::npos) continue;
            buckets[static_cast<unsigned char>(piece[0])].push_back(id);
            pieces[id]=std::move(piece);
        }
    }
};
inline int constrainLocalPhotoToken(LocalPhotoGrammar& grammar,const LocalPhotoVocabulary& vocab,
                                   const float* logits,int sampled,const std::function<bool()>& cancelled) {
    if(!logits) throw std::runtime_error("LOCAL_PHOTO_LOGITS_INVALID");
    if(sampled>=0 && sampled<static_cast<int>(vocab.pieces.size()) && grammar.permits(vocab.pieces[sampled])) {
        grammar.accept(vocab.pieces[sampled]); return sampled;
    }
    int best=-1; float score=-std::numeric_limits<float>::infinity();
    for(int first=0;first<256;++first) {
        if(cancelled()) throw std::runtime_error("LOCAL_PHOTO_CANCELLED");
        if(!grammar.permits(std::string(1,static_cast<char>(first)))) continue;
        for(int id:vocab.buckets[first]) if(std::isfinite(logits[id]) && logits[id]>score && grammar.permits(vocab.pieces[id])) {
            score=logits[id]; best=id;
        }
    }
    if(best<0) throw std::runtime_error("LOCAL_PHOTO_NO_VALID_TOKEN:"+grammar.position());
    grammar.accept(vocab.pieces[best]); return best;
}
