#include <jni.h>
#include <map>
#include <llm/llm.hpp>
#include <chrono>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>

using MNN::Transformer::Llm;

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
        auto llm = std::unique_ptr<Llm>(Llm::createLLM(pathString(env, configPath)));
        if (!llm) throw std::runtime_error("无法创建 MNN 模型");
        llm->set_config(R"({"backend_type":"cpu","thread_num":4,"max_all_tokens":16384,"max_new_tokens":4096,"reuse_kv":false,"timeout_ms":120000})");
        if (!llm->load()) throw std::runtime_error("MNN 模型加载失败，请检查权重和视觉组件");
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
 * @return UTF-8 输出；取消返回空输出并由 Kotlin 丢弃。
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_framer_sense_feature_camera_vlm_data_MnnNative_infer(JNIEnv* env, jobject self, jlong handle, jstring imagePath, jbyteArray promptBytes, jobject cancelled) {
    try {
        auto* llm = reinterpret_cast<Llm*>(handle);
        if (!llm) throw std::runtime_error("模型尚未加载");
        jsize length = env->GetArrayLength(promptBytes);
        std::string prompt(length, '\0');
        env->GetByteArrayRegion(promptBytes, 0, length, reinterpret_cast<jbyte*>(prompt.data()));
        const auto cancelClass = env->GetObjectClass(cancelled);
        const auto get = env->GetMethodID(cancelClass, "get", "()Z");
        std::ostringstream output;
        llm->reset();
        llm->generate_init(&output, "");
        // 用户文本不得引入额外媒体标签；只有应用生成的私有图像路径可进入视觉通道。
        std::string escaped;
        for (char c : prompt) {
            if (c == '<') escaped += "\\u003c";
            else if (c == '>') escaped += "\\u003e";
            else escaped += c;
        }
        // MNN Omni::tokenizer_encode 实际解析 img 标签并运行视觉编码器。
        const std::string visualPrompt = "<img>" + pathString(env, imagePath) + "</img>\n" + escaped;
        auto started = std::chrono::steady_clock::now();
        if (!env->CallBooleanMethod(cancelled, get)) llm->response(visualPrompt, &output, "", 1);
        for (int i = 1; i < 4096 && !llm->stoped(); ++i) {
            if (env->CallBooleanMethod(cancelled, get)) break;
            if (std::chrono::steady_clock::now() - started > std::chrono::seconds(120)) throw std::runtime_error("离线推理超时");
            llm->generate(1);
        }
        env->DeleteLocalRef(cancelClass);
        if (llm->getContext()->status == MNN::Transformer::LlmStatus::INTERNAL_ERROR || llm->getContext()->status == MNN::Transformer::LlmStatus::TIMEOUT) throw std::runtime_error("MNN 推理错误或超时");
        const std::string text = output.str();
        auto result = env->NewByteArray(static_cast<jsize>(text.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
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
