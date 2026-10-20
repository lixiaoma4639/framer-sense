#include <jni.h>
#include <android/log.h>
#include <chrono>
#include <atomic>
#include <map>
#include <llm/llm.hpp>
#include <memory>
#include <ostream>
#include <streambuf>
#include <stdexcept>
#include <string>
#include <vector>
#include <cctype>
#include <sstream>
#include "local_image_prompt.h"
#include "local_view_choice.h"

using MNN::Transformer::Llm;
// 离线协议只需要一句场景观察；较小预算避免低端 CPU 在模型忽略停止指令时长时间续写。
static constexpr int kMaxOutputTokens = 96;
static constexpr int kMaxSceneDescriptionTokens = 192;
// 不覆盖模型包的 sampler 配置。Qwen3-VL-2B 的导出配置使用 mixed sampler；
// 强制 greedy 会在部分 ARM CPU 上重复同一 token，直到触发输出上限。
static constexpr char kCpuConfig[] = R"({"backend_type":"cpu","thread_num":4,"max_all_tokens":16384,"max_new_tokens":96,"reuse_kv":false,"use_template":true,"timeout_ms":120000})";

/** 每轮推理绑定自己的 AtomicBoolean；回调仅在同步 CPU 推理线程读取，不保留 JNI 局部引用。 */
class NativeCancelScope {
public:
    NativeCancelScope(JNIEnv* env, Llm* llm, jobject flag, const char* task)
        : env_(env), llm_(llm), flag_(flag), task_(task), id_(++nextId_), started_(std::chrono::steady_clock::now()) {
        flagClass_ = env_->GetObjectClass(flag_);
        if (!flagClass_ || env_->ExceptionCheck()) throw std::runtime_error("MNN cancellation flag unavailable");
        get_ = env_->GetMethodID(flagClass_, "get", "()Z");
        if (!get_ || env_->ExceptionCheck()) throw std::runtime_error("MNN cancellation flag unavailable");
        operatorChecksAtStart_ = llm_->cancellationOperatorChecks();
        llm_->setCancelCallback([this] { return cancelled(); });
        __android_log_print(ANDROID_LOG_INFO, "VlmOffline", "MNN 可取消任务开始 nativeRequest=%llu task=%s cancellation=operator_boundary", id_, task_);
    }
    ~NativeCancelScope() {
        // 先解除捕获，再释放本次 JNI 引用；不能让下一轮回调读到旧 flag/env。
        llm_->setCancelCallback({});
        env_->DeleteLocalRef(flagClass_);
    }
    bool cancelled() {
        if (observed_) return true;
        if (env_->ExceptionCheck()) return false;
        if (env_->CallBooleanMethod(flag_, get_) != JNI_TRUE) return false;
        observed_ = true;
        observedAt_ = std::chrono::steady_clock::now();
        __android_log_print(ANDROID_LOG_INFO, "VlmOffline", "MNN 取消已检测 nativeRequest=%llu task=%s elapsedMs=%lld inGraph=%d operatorChecks=%zu", id_, task_, elapsed(started_), static_cast<int>(llm_->cancellationGraphActive()), llm_->cancellationOperatorChecks() - operatorChecksAtStart_);
        return true;
    }
    jbyteArray cancelledResult() {
        // 后端已通过 CALL_BACK_STOP/onExecuteEnd 退出，此时才能清理部分视觉输出与 KV 状态。
        llm_->setCancelCallback({});
        llm_->resetCancelledRequest();
        __android_log_print(ANDROID_LOG_INFO, "VlmOffline", "MNN 取消清理完成 nativeRequest=%llu task=%s cleanupMs=%lld totalMs=%lld modelRetained=true", id_, task_, elapsed(observedAt_), elapsed(started_));
        return env_->NewByteArray(0);
    }
private:
    static long long elapsed(std::chrono::steady_clock::time_point from) {
        return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - from).count();
    }
    inline static std::atomic<unsigned long long> nextId_{0};
    JNIEnv* env_;
    Llm* llm_;
    jobject flag_;
    jclass flagClass_ = nullptr;
    jmethodID get_ = nullptr;
    const char* task_;
    unsigned long long id_;
    size_t operatorChecksAtStart_ = 0;
    bool observed_ = false;
    std::chrono::steady_clock::time_point started_, observedAt_;
};

/** 直接监听 MNN 的 token 输出，首 token 后由协程监控停滞，不把耗时长当成异常。 */
class TokenOutputBuffer : public std::streambuf {
public:
    TokenOutputBuffer(JNIEnv* env, jobject progress, jmethodID onToken)
        : env_(env), progress_(progress), onToken_(onToken) {}
    const std::string& str() const { return text_; }
    /** 只识别完整的顶层 JSON 边界，不替代 Kotlin 的协议和几何校验。 */
    bool completeJson() const {
        std::vector<char> stack;
        bool started = false, inString = false, escaped = false;
        for (size_t i = 0; i < text_.size(); ++i) {
            const char c = text_[i];
            if (!started) {
                if (std::isspace(static_cast<unsigned char>(c))) continue;
                if (c != '[' && c != '{') return false;
                started = true;
            }
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '[' || c == '{') stack.push_back(c);
            else if (c == ']' || c == '}') {
                if (stack.empty() || stack.back() != (c == ']' ? '[' : '{')) return false;
                stack.pop_back();
                if (stack.empty()) {
                    for (++i; i < text_.size(); ++i) if (!std::isspace(static_cast<unsigned char>(text_[i]))) return false;
                    return true;
                }
            }
        }
        return false;
    }
protected:
    std::streamsize xsputn(const char* data, std::streamsize size) override {
        if (size > 0) {
            text_.append(data, static_cast<size_t>(size));
            env_->CallVoidMethod(progress_, onToken_);
        }
        return size;
    }
    int_type overflow(int_type value) override {
        if (!traits_type::eq_int_type(value, traits_type::eof())) {
            const char c = traits_type::to_char_type(value);
            xsputn(&c, 1);
        }
        return traits_type::not_eof(value);
    }
private:
    JNIEnv* env_;
    jobject progress_;
    jmethodID onToken_;
    std::string text_;
};

/** 复制 Java 路径并及时释放 JNI 字符指针。
 * @param env 当前线程 JNI 环境。
 * @param text 应用生成的私有路径。
 * @return 独立的原生字符串。
 */
static std::string pathString(JNIEnv* env, jstring text) {
    const char* chars = env->GetStringUTFChars(text, nullptr);
    if (!chars) throw std::runtime_error("无法读取路径");
    std::string copy(chars);
    env->ReleaseStringUTFChars(text, chars);
    return copy;
}

/** 向 JVM 抛出脱敏异常。
 * @param env JNI 环境。
 * @param message 本地错误说明。
 */
static void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

/** 加载 CPU 多模态模型。
 * @param env JNI 环境。
 * @param self Kotlin 单例（不持有）。
 * @param configPath 已校验的 config.json 路径。
 * @param gemmaVisualOnly 经 Kotlin 校验的 Gemma 4 仅使用图文能力。
 * @param qwen35NonThinking 仅 Qwen3.5 关闭思考模式以适配摄影短协议。
 * @return 模型句柄；失败抛出异常并返回 0。
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_framer_sense_feature_camera_vlm_data_MnnNative_load(JNIEnv* env, jobject self, jstring configPath, jboolean gemmaVisualOnly, jboolean qwen35NonThinking) {
    try {
        const auto path = pathString(env, configPath);
        auto llm = std::unique_ptr<Llm>(Llm::createLLM(path));
        if (!llm) throw std::runtime_error("无法创建 MNN 模型");
        // 当前导入的 Qwen3-VL-2B 包在 Galaxy S9 的 OpenCL 视觉预填充会无限停滞；
        // 默认 CPU 后端可稳定完成，且单次短协议避免旧实现的第二轮超时。
        llm->set_config(kCpuConfig);
        if (gemmaVisualOnly == JNI_TRUE) {
            // 仅作用于 Kotlin 校验过的 Gemma 4 图文包；不修改磁盘配置及 Qwen 参数。
            if (!llm->set_config(R"({"is_audio":false})")) throw std::runtime_error("GEMMA_VISUAL_CONFIG_FAILED");
            __android_log_print(ANDROID_LOG_INFO, "VlmOffline", "MNN Gemma 图文适配 audioEnabled=false sourceConfigUnchanged=true");
        }
        if (qwen35NonThinking == JNI_TRUE) {
            if (!llm->set_config(R"({"jinja":{"context":{"enable_thinking":false}}})")) throw std::runtime_error("QWEN35_TEMPLATE_CONFIG_FAILED");
            __android_log_print(ANDROID_LOG_INFO, "VlmOffline", "MNN Qwen3.5 摄影适配 thinkingEnabled=false sourceConfigUnchanged=true");
        }
        // 必须先启用回调和 Session_Debug，再创建视觉/语言计算图。
        llm->setCancelCallback({});
        if (!llm->load()) throw std::runtime_error("MNN 模型加载失败，请检查权重和视觉组件");
        __android_log_print(ANDROID_LOG_INFO, "VlmOffline", "MNN 模型加载成功 backend=cpu cancellation=operator_boundary");
        return reinterpret_cast<jlong>(llm.release());
    } catch (const std::exception& e) { fail(env, e.what()); return 0; }
}

/** 将图片作为 MNN Omni 的 img 标签视觉输入，在算子及 token 边界检查取消。
 * @param env JNI 环境。
 * @param handle 串行线程独占模型句柄。
 * @param paths 按编号排列的私有 JPEG 路径，由 Omni 逐张读取并编码为视觉特征。
 * @param promptBytes UTF-8 导演提示词。
 * @param cancelled Java AtomicBoolean；不能在本函数执行中释放 handle。
 * @param progress 每次实际输出 token 时通知 Kotlin，加载和首 token 不设超时。
 * @return UTF-8 输出；取消返回空输出并由 Kotlin 丢弃。
 */
static jbyteArray inferImages(JNIEnv* env, jlong handle, const std::vector<std::string>& paths, jbyteArray promptBytes, jobject cancelled, jobject progress, int maxOutputTokens = kMaxOutputTokens) {
    std::unique_ptr<NativeCancelScope> cancellation;
    try {
        auto* llm = reinterpret_cast<Llm*>(handle);
        if (!llm) throw std::runtime_error("模型尚未加载");
        cancellation = std::make_unique<NativeCancelScope>(env, llm, cancelled, "scene");
        if (cancellation->cancelled()) return cancellation->cancelledResult();
        jsize length = env->GetArrayLength(promptBytes);
        std::string prompt(length, '\0');
        env->GetByteArrayRegion(promptBytes, 0, length, reinterpret_cast<jbyte*>(prompt.data()));
        const auto progressClass = env->GetObjectClass(progress);
        const auto onToken = env->GetMethodID(progressClass, "onToken", "()V");
        if (!onToken) return nullptr;
        TokenOutputBuffer buffer(env, progress, onToken);
        std::ostream output(&buffer);
        llm->reset();
        // MNN Omni::tokenizer_encode 实际解析 img 标签并运行视觉编码器。
        const std::string visualPrompt = localVisualPrompt(paths, prompt);
        __android_log_print(ANDROID_LOG_INFO, "VlmOffline", "MNN 开始视觉预填充 images=%zu promptBytes=%zu maxOutputTokens=%d", paths.size(), visualPrompt.size(), maxOutputTokens);
        const auto prefillStarted = std::chrono::steady_clock::now();
        // response(..., 1) 同时完成预填充、采样并输出第一个真实 token。不能用 0：
        // 普通 Llm 的预填充不会设置 current_token，手动解码 -1 会使后续解码退化为重复符号。
        if (!cancellation->cancelled()) llm->response(visualPrompt, &output, "", 1);
        if (cancellation->cancelled()) return cancellation->cancelledResult();
        __android_log_print(ANDROID_LOG_INFO, "VlmOffline", "MNN 视觉预填充结束 elapsedMs=%lld", static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - prefillStarted).count()));
        for (int i = 1; i < maxOutputTokens && !llm->stoped() && !buffer.completeJson(); ++i) {
            if (cancellation->cancelled()) break;
            llm->generate(1);
        }
        env->DeleteLocalRef(progressClass);
        if (env->ExceptionCheck()) return nullptr;
        if (cancellation->cancelled()) return cancellation->cancelledResult();
        const auto* context = llm->getContext();
        const bool cancelledNow = false;
        const bool complete = buffer.completeJson();
        __android_log_print(ANDROID_LOG_INFO, "VlmOffline",
            "MNN 生成结束 status=%d cancelled=%d completeJson=%d promptTokens=%d outputTokens=%zu lastToken=%d visionMs=%lld prefillMs=%lld decodeMs=%lld outputBytes=%zu",
            static_cast<int>(context->status), static_cast<int>(cancelledNow), static_cast<int>(complete),
            context->prompt_len, context->output_tokens.size(), context->current_token,
            static_cast<long long>(context->vision_us / 1000),
            static_cast<long long>(context->prefill_us / 1000),
            static_cast<long long>(context->decode_us / 1000), buffer.str().size());
        if (llm->getContext()->status == MNN::Transformer::LlmStatus::TIMEOUT) {
            env->ThrowNew(env->FindClass("java/util/concurrent/TimeoutException"), "MNN inference timeout");
            return nullptr;
        }
        if (llm->getContext()->status == MNN::Transformer::LlmStatus::INTERNAL_ERROR) throw std::runtime_error("MNN 推理错误");
        const std::string& text = buffer.str();
        auto result = env->NewByteArray(static_cast<jsize>(text.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
        if (!cancelledNow && !complete && !llm->stoped()) {
            const auto callbackClass = env->GetObjectClass(progress);
            const auto callback = env->GetMethodID(callbackClass, "onIncompleteOutput", "([B)V");
            if (callback && result) env->CallVoidMethod(progress, callback, result);
            env->DeleteLocalRef(callbackClass);
            if (env->ExceptionCheck()) return nullptr;
            // 文本短协议没有 JSON 闭合标志。保留已生成的文本交给 Kotlin 的场景描述
            // 回退解析；若它确实不含有效观察，解析层仍会明确拒绝，而不是误报推理失败。
            __android_log_print(ANDROID_LOG_WARN, "VlmOffline", "MNN 输出达到预算，交由离线协议解析已生成文本");
        }
        return result;
    } catch (const std::exception& e) {
        if (cancellation && cancellation->cancelled()) return cancellation->cancelledResult();
        fail(env, e.what()); return nullptr;
    }
}

/** 一次真实视觉预填充后，按模型分数在合法编号中作选择，不自由生成格式文本。 */
static jbyteArray chooseView(JNIEnv* env, jlong handle, const std::vector<std::string>& paths,
                            jbyteArray promptBytes, jobject cancelled, size_t options = 0) {
    std::unique_ptr<NativeCancelScope> cancellation;
    try {
        auto* llm = reinterpret_cast<Llm*>(handle);
        if (!llm) throw std::runtime_error("模型尚未加载");
        cancellation = std::make_unique<NativeCancelScope>(env, llm, cancelled, options ? "photo" : "views");
        if (cancellation->cancelled()) return cancellation->cancelledResult();
        std::string prompt(env->GetArrayLength(promptBytes), '\0');
        env->GetByteArrayRegion(promptBytes, 0, static_cast<jsize>(prompt.size()), reinterpret_cast<jbyte*>(prompt.data()));
        if (env->ExceptionCheck()) return nullptr;
        llm->reset();
        std::vector<int> digitTokens;
        const auto candidateCount = options ? options : paths.size();
        if (candidateCount < 1 || candidateCount > kMaxLocalChoices) throw std::runtime_error("LOCAL_VIEW_INPUT");
        for (size_t i = 1; i <= candidateCount; ++i) {
            const auto digit = std::to_string(i);
            const auto tokens = llm->tokenizer_encode(digit);
            if (tokens.size() != 1 || llm->tokenizer_decode(tokens[0]) != digit || llm->is_stop(tokens[0]))
                throw std::runtime_error("LOCAL_VIEW_CHOICE_UNSUPPORTED");
            digitTokens.push_back(tokens[0]);
        }
        const auto visualPrompt = localVisualPrompt(paths, prompt);
        __android_log_print(ANDROID_LOG_INFO, "VlmOffline", "MNN 开始编号决策 images=%zu candidates=%zu method=constrained_logits promptBytes=%zu", paths.size(), candidateCount, visualPrompt.size());
        std::ostringstream ignoredText;
        // 0 只作预填充。此分支读取 logits 作一次编号选择，不继续 generate(current_token=-1)。
        llm->response(visualPrompt, &ignoredText, "", 0);
        if (env->ExceptionCheck()) return nullptr;
        if (cancellation->cancelled()) return cancellation->cancelledResult();
        const auto* context = llm->getContext();
        if (context->status == MNN::Transformer::LlmStatus::TIMEOUT) {
            env->ThrowNew(env->FindClass("java/util/concurrent/TimeoutException"), "MNN inference timeout");
            return nullptr;
        }
        if (context->status == MNN::Transformer::LlmStatus::INTERNAL_ERROR || context->all_seq_len <= 0)
            throw std::runtime_error("LOCAL_VIEW_CHOICE_INVALID");
        const auto outputs = llm->getOutputs();
        if (outputs.empty() || outputs[0].get() == nullptr) throw std::runtime_error("LOCAL_VIEW_CHOICE_INVALID");
        const auto* info = outputs[0]->getInfo();
        if (!info || info->dim.empty() || info->dim.back() <= 0) throw std::runtime_error("LOCAL_VIEW_CHOICE_INVALID");
        const auto vocabulary = static_cast<size_t>(info->dim.back());
        __android_log_print(ANDROID_LOG_INFO, "VlmOffline", "MNN 编号输出检查 vocabulary=%zu elements=%zu type=%d bits=%d promptTokens=%d", vocabulary, info->size, static_cast<int>(info->type.code), static_cast<int>(info->type.bits), context->prompt_len);
        if (info->type.code != halide_type_float || info->type.bits != 32)
            throw std::runtime_error("LOCAL_VIEW_CHOICE_UNSUPPORTED");
        const auto offset = localViewLogitOffset(info->size, vocabulary, context->prompt_len);
        const auto* logits = outputs[0]->readMap<float>();
        if (!logits) throw std::runtime_error("LOCAL_VIEW_CHOICE_INVALID");
        const auto choice = chooseLocalView(logits + offset, vocabulary, digitTokens);
        std::ostringstream scores;
        for (size_t i = 0; i < choice.scores.size(); ++i) {
            if (i) scores << ',';
            scores << i + 1 << ':' << choice.scores[i];
        }
        if (cancellation->cancelled()) return cancellation->cancelledResult();
        __android_log_print(ANDROID_LOG_INFO, "VlmOffline", "MNN 编号选择结束 method=constrained_logits images=%zu selected=%d scores=%s visionMs=%lld prefillMs=%lld generatedTokens=%zu", paths.size(), choice.ordinal, scores.str().c_str(), static_cast<long long>(context->vision_us / 1000), static_cast<long long>(context->prefill_us / 1000), context->output_tokens.size());
        std::string digit = std::to_string(choice.ordinal);
        if (!options) {
            const auto ranking = rankLocalViews(choice);
            digit.clear();
            for (auto ordinal : ranking) {
                if (!digit.empty()) digit += ',';
                digit += std::to_string(ordinal);
            }
            __android_log_print(ANDROID_LOG_INFO, "VlmOffline", "MNN 选景前三名 ranking=%s prefillCalls=1", digit.c_str());
        }
        auto result = env->NewByteArray(static_cast<jsize>(digit.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(digit.size()), reinterpret_cast<const jbyte*>(digit.data()));
        return result;
    } catch (const std::exception& error) {
        if (cancellation && cancellation->cancelled()) return cancellation->cancelledResult();
        fail(env, error.what()); return nullptr;
    }
}

/** 单图兼容入口，保持原有视觉提示前缀。 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_framer_sense_feature_camera_vlm_data_MnnNative_infer(JNIEnv* env, jobject, jlong handle, jstring path, jbyteArray prompt, jobject cancelled, jobject progress) {
    try { return inferImages(env, handle, {pathString(env, path)}, prompt, cancelled, progress); }
    catch (const std::exception& error) { fail(env, error.what()); return nullptr; }
}

/** 单图环境描述，单独扩大输出长度；其他协议继续使用原有输出上限。 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_framer_sense_feature_camera_vlm_data_MnnNative_describeScene(JNIEnv* env, jobject, jlong handle, jstring path, jbyteArray prompt, jobject cancelled, jobject progress) {
    try { return inferImages(env, handle, {pathString(env, path)}, prompt, cancelled, progress, kMaxSceneDescriptionTokens); }
    catch (const std::exception& error) { fail(env, error.what()); return nullptr; }
}

/** 本地多图入口，禁止超限路径或媒体标签注入。 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_framer_sense_feature_camera_vlm_data_MnnNative_inferImages(JNIEnv* env, jobject, jlong handle, jobjectArray images, jbyteArray prompt, jobject cancelled, jobject progress) {
    try {
        const auto count = env->GetArrayLength(images);
        if (count < 1 || count > static_cast<jsize>(kMaxLocalImages)) throw std::runtime_error("LOCAL_VIEW_INPUT");
        std::vector<std::string> paths;
        for (jsize i = 0; i < count; ++i) {
            auto path = static_cast<jstring>(env->GetObjectArrayElement(images, i));
            if (!path) throw std::runtime_error("LOCAL_VIEW_INPUT");
            paths.push_back(pathString(env, path));
            env->DeleteLocalRef(path);
        }
        return chooseView(env, handle, paths, prompt, cancelled);
    } catch (const std::exception& error) { fail(env, error.what()); return nullptr; }
}

/** 释放已空闲的模型句柄。
 * @param env JNI 环境。
 * @param self Kotlin 单例。
 * @param handle 只能在推理任务结束后释放的句柄。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_framer_sense_feature_camera_vlm_data_MnnNative_release(JNIEnv* env, jobject self, jlong handle) {
    delete reinterpret_cast<Llm*>(handle);
}

/** 单图摄影组合六选一，复用真实 logits 编号选择，不复制图片或自由拼中文姿势。 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_framer_sense_feature_camera_vlm_data_MnnNative_choosePhoto(JNIEnv* env, jobject, jlong handle, jstring path, jbyteArray prompt, jobject cancelled) {
    try { return chooseView(env, handle, {pathString(env, path)}, prompt, cancelled, 6); }
    catch (const std::exception& error) { fail(env, error.what()); return nullptr; }
}
