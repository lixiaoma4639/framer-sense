#include <jni.h>
#include <android/log.h>
#include <chrono>
#include <map>
#include <llm/llm.hpp>
#include <memory>
#include <ostream>
#include <streambuf>
#include <stdexcept>
#include <string>
#include <vector>
#include <cctype>

using MNN::Transformer::Llm;
// 离线协议只需要一句场景观察；较小预算避免低端 CPU 在模型忽略停止指令时长时间续写。
static constexpr int kMaxOutputTokens = 96;
// 不覆盖模型包的 sampler 配置。Qwen3-VL-2B 的导出配置使用 mixed sampler；
// 强制 greedy 会在部分 ARM CPU 上重复同一 token，直到触发输出上限。
static constexpr char kCpuConfig[] = R"({"backend_type":"cpu","thread_num":4,"max_all_tokens":16384,"max_new_tokens":96,"reuse_kv":false,"use_template":true,"timeout_ms":120000})";

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
 * @return 模型句柄；失败抛出异常并返回 0。
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_framer_sense_feature_camera_vlm_data_MnnNative_load(JNIEnv* env, jobject self, jstring configPath) {
    try {
        const auto path = pathString(env, configPath);
        auto llm = std::unique_ptr<Llm>(Llm::createLLM(path));
        if (!llm) throw std::runtime_error("无法创建 MNN 模型");
        // 当前导入的 Qwen3-VL-2B 包在 Galaxy S9 的 OpenCL 视觉预填充会无限停滞；
        // 默认 CPU 后端可稳定完成，且单次短协议避免旧实现的第二轮超时。
        llm->set_config(kCpuConfig);
        if (!llm->load()) throw std::runtime_error("MNN 模型加载失败，请检查权重和视觉组件");
        __android_log_print(ANDROID_LOG_INFO, "VlmOffline", "MNN 模型加载成功 backend=cpu");
        return reinterpret_cast<jlong>(llm.release());
    } catch (const std::exception& e) { fail(env, e.what()); return 0; }
}

/** 将图片作为 MNN Omni 的 img 标签视觉输入，分 token 检查取消。
 * @param env JNI 环境。
 * @param self Kotlin 单例。
 * @param handle 串行线程独占模型句柄。
 * @param imagePath 私有 JPEG 路径，由 Omni 读取并编码为视觉特征。
 * @param promptBytes UTF-8 导演提示词。
 * @param cancelled Java AtomicBoolean；不能在本函数执行中释放 handle。
 * @param progress 每次实际输出 token 时通知 Kotlin，加载和首 token 不设超时。
 * @return UTF-8 输出；取消返回空输出并由 Kotlin 丢弃。
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_framer_sense_feature_camera_vlm_data_MnnNative_infer(JNIEnv* env, jobject self, jlong handle, jstring imagePath, jbyteArray promptBytes, jobject cancelled, jobject progress) {
    try {
        auto* llm = reinterpret_cast<Llm*>(handle);
        if (!llm) throw std::runtime_error("模型尚未加载");
        jsize length = env->GetArrayLength(promptBytes);
        std::string prompt(length, '\0');
        env->GetByteArrayRegion(promptBytes, 0, length, reinterpret_cast<jbyte*>(prompt.data()));
        const auto cancelClass = env->GetObjectClass(cancelled);
        const auto get = env->GetMethodID(cancelClass, "get", "()Z");
        const auto progressClass = env->GetObjectClass(progress);
        const auto onToken = env->GetMethodID(progressClass, "onToken", "()V");
        if (!onToken) return nullptr;
        TokenOutputBuffer buffer(env, progress, onToken);
        std::ostream output(&buffer);
        llm->reset();
        // 用户文本不得引入额外媒体标签；只有应用生成的私有图像路径可进入视觉通道。
        std::string escaped;
        for (char c : prompt) {
            if (c == '<') escaped += "\\u003c";
            else if (c == '>') escaped += "\\u003e";
            else escaped += c;
        }
        // MNN Omni::tokenizer_encode 实际解析 img 标签并运行视觉编码器。
        const std::string visualPrompt = "<img>" + pathString(env, imagePath) + "</img>\n" + escaped;
        __android_log_print(ANDROID_LOG_INFO, "VlmOffline", "MNN 开始视觉预填充 promptBytes=%zu", visualPrompt.size());
        const auto prefillStarted = std::chrono::steady_clock::now();
        // response(..., 1) 同时完成预填充、采样并输出第一个真实 token。不能用 0：
        // 普通 Llm 的预填充不会设置 current_token，手动解码 -1 会使后续解码退化为重复符号。
        if (!env->CallBooleanMethod(cancelled, get)) llm->response(visualPrompt, &output, "", 1);
        __android_log_print(ANDROID_LOG_INFO, "VlmOffline", "MNN 视觉预填充结束 elapsedMs=%lld", static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - prefillStarted).count()));
        for (int i = 1; i < kMaxOutputTokens && !llm->stoped() && !buffer.completeJson(); ++i) {
            if (env->CallBooleanMethod(cancelled, get)) break;
            llm->generate(1);
        }
        env->DeleteLocalRef(cancelClass);
        env->DeleteLocalRef(progressClass);
        if (env->ExceptionCheck()) return nullptr;
        const auto* context = llm->getContext();
        const bool cancelledNow = env->CallBooleanMethod(cancelled, get);
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
    } catch (const std::exception& e) { fail(env, e.what()); return nullptr; }
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
