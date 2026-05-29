# AGENTS.md

本文件是 Framer_Sense 仓库的轻量代理规则入口。项目细节不要重复写在这里，统一维护在 `docs/PROJECT_CONTEXT.md`。

## 必须遵守

- 后续处理本仓库任务时，默认使用简体中文回复。
- 开始实现前，先阅读 `docs/PROJECT_CONTEXT.md`，再按任务范围查看相关源码。
- 若 `README.md`、`ARCHITECTURE.md`、`docs/NAVIGATION.md`、`docs/HOME_MODULE.md` 与当前源码或 `docs/PROJECT_CONTEXT.md` 不一致，以源码和 `docs/PROJECT_CONTEXT.md` 为准。
- 不要修改与当前任务无关的文件。
- 不要还原用户已有改动；如果工作区存在未跟踪或已修改文件，先确认它们是否与任务相关。
- 架构默认约束：拍照模块需要按照 MVI 实现；除拍照模块外，其余所有页面默认按照 MVVM 实现。除非用户或任务文档明确指定某个非拍照页面使用 MVI，否则不要在非拍照页面引入 MVI。
- 后续需要测试时，只编写或更新自动化测试代码，不主动运行 Gradle/Android 自动化测试；由用户自行在本地执行验证。若用户明确要求运行测试，则按用户要求执行。

## 文档同步规则

- 只要代码更改影响项目结构、模块职责、功能行为、导航流程、数据流、依赖、构建命令或测试方式，就必须同步更新对应 Markdown 文档。
- 文档同步范围不限于 `AGENTS.md`；优先更新最贴近变更内容的文档，例如 `docs/PROJECT_CONTEXT.md`、`README.md`、`ARCHITECTURE.md` 或 `docs/` 下的专题文档。
- `AGENTS.md` 只记录代理级规则；项目架构、模块说明、常用命令和注意事项统一写入 `docs/PROJECT_CONTEXT.md`。

## 项目上下文入口

- 项目概览、模块边界、UI 与导航、数据流、开发约定、常用命令和注意事项见 `docs/PROJECT_CONTEXT.md`。
- 旧文档可作为历史记录，但后续编程优先参考 `docs/PROJECT_CONTEXT.md` 和实际源码。
