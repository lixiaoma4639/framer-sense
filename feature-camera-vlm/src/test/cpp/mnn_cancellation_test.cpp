// 使用真正的 MNN CPU 计算图验证算子中止、空返回和同一实例恢复，不加载摄影模型。
// 默认不编译；配置 VLM_BUILD_CANCELLATION_PROBE=ON 后由用户构建并运行。
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <cassert>
#include <cmath>
#include <iostream>
#include <memory>
#include <MNN/expr/ExprCreator.hpp>
#include <MNN/expr/MathOp.hpp>
#include <MNN/expr/ExecutorScope.hpp>
#include <llm/llm.hpp>
#include "llmconfig.hpp"

using namespace MNN;
using namespace MNN::Express;
using namespace MNN::Transformer;

static std::shared_ptr<LlmConfig> config() {
    auto result = std::make_shared<LlmConfig>();
    result->config_ = ujson::json::parse(R"({"reuse_kv":false,"backend_type":"cpu","thread_num":1})");
    return result;
}

class CancellationProbe : public Llm {
public:
    CancellationProbe() : Llm(config()) {
        mContext->status = LlmStatus::RUNNING;
        setCancelCallback({});
    }
    std::shared_ptr<Module> graph() {
        ExecutorScope scope(mExecutor);
        auto input = _Input({1}, NHWC, halide_type_of<float>());
        input->setName("input");
        auto value = input;
        // 非线性链不能合并成一次线性算子；留下多个可取消边界。
        for (int i = 0; i < 32; ++i) value = _Sin(value);
        value->setName("output");
        auto bytes = Variable::save({value});
        ScheduleConfig schedule;
        schedule.type = MNN_FORWARD_CPU;
        schedule.numThread = 1;
        runtime_ = std::shared_ptr<Executor::RuntimeManager>(Executor::RuntimeManager::createRuntimeManager(schedule));
        runtime_->setMode(Interpreter::Session_Debug);
        auto module = std::shared_ptr<Module>(Module::load({"input"}, {"output"},
            reinterpret_cast<const uint8_t*>(bytes.data()), bytes.size(), runtime_), Module::destroy);
        assert(module);
        return module;
    }
    std::vector<VARP> run(Module* module, VARP input) {
        ExecutorScope scope(mExecutor);
        return forwardCancelable(module, {input});
    }
private:
    std::shared_ptr<Executor::RuntimeManager> runtime_;
};

int main() {
    CancellationProbe engine;
    auto graph = engine.graph();
    auto input = _Input({1}, NHWC, halide_type_of<float>());
    input->writeMap<float>()[0] = .5f;

    // 进入图之后才发出取消，验证不是仅在请求开始时检查标记。
    int checks = 0;
    engine.setCancelCallback([&] { return ++checks >= 6; });
    auto stopped = engine.run(graph.get(), input);
    assert(stopped.empty());
    assert(engine.getContext()->status == LlmStatus::USER_CANCEL);
    assert(checks == 6);
    assert(engine.cancellationOperatorChecks() > 0);

    engine.setCancelCallback({});
    engine.resetCancelledRequest();
    assert(engine.getContext()->status == LlmStatus::RUNNING);
    auto resumed = engine.run(graph.get(), input);
    assert(resumed.size() == 1);
    float expected = .5f;
    for (int i = 0; i < 32; ++i) expected = std::sin(expected);
    const float actual = resumed[0]->readMap<float>()[0];
    assert(std::fabs(actual - expected) < 1e-5f);

    // 下一次的取消标记不能污染再下一次；提前取消也必须安全返回。
    engine.setCancelCallback([] { return true; });
    auto early = engine.run(graph.get(), input);
    assert(early.empty());
    engine.setCancelCallback({});
    engine.resetCancelledRequest();
    auto again = engine.run(graph.get(), input);
    assert(again.size() == 1);
    assert(std::fabs(again[0]->readMap<float>()[0] - expected) < 1e-5f);
    std::cout << "MNN cancellation probe passed\n";
}
