# Project Alpha

**基于 Spring AI Alibaba 的产品经理工作台，将需求讨论、PRD 编辑、页面原型和人工确认连接在同一个会话中。**

Alpha 面向本地单用户桌面使用。你可以从一句需求开始，与模型共同完善产品需求文档（PRD），按模块或页面生成可交互原型，再核对来源、恢复历史版本、确认并导出指定修订。前后端在同一仓库维护，既可分别开发，也可打包成一个 Spring Boot JAR。

本项目也用于结合实际业务学习 Spring AI 与 Spring AI Alibaba：观察一次模型调用如何组织上下文、结构化输出如何进入业务校验，以及 Graph 如何组织生成步骤。当前业务能力与后续 Agent 扩展的边界见下文。

## 当前能力

| 领域 | 已有功能 |
| --- | --- |
| 工作空间 | 项目背景、持续会话、自定义需求版本、归档、回收站与恢复 |
| PRD 编辑 | 会话增量编辑同一份文档，支持全文、模块和页面讨论；正文自动保存，待确认事项与可选建议分别维护 |
| 修订与确认 | 保存历史、不可变定稿、并发修订检查；迟到的模型结果不会覆盖期间发生的人工修改 |
| PRD 模板 | 内置管理系统、App / 小程序、通用产品模板；自定义模板草稿、版本发布、删除与恢复 |
| 模型接入 | 百炼原生与 OpenAI 兼容协议，平台和模型目录管理、连接测试、动态切换、后端加密保存 API Key |
| 页面原型 | 基于固定 Zero 管理端模板规划页面、生成源码、执行构建与 Chromium 验证，支持页面级修改和受控预览 |
| 原型交付 | 历史恢复为新修订、绑定 PRD 定稿及确切页面范围的人工确认、指定修订源码包与静态包导出 |
| 持久化 | PostgreSQL、MyBatis Plus、Flyway；业务任务、消息、PRD、原型来源及修订独立保存 |

典型流程：

```text
新建项目与会话 → 讨论并编辑 PRD → 选择页面生成原型
                                  ↓
              核对来源与历史 → 人工确认 → 导出指定修订
```

首次初始化默认使用**本地模板联调模式**，无需模型密钥，但仍需要 PostgreSQL。该模式明确标记“未调用大模型”，只用于联调；真实 PRD 编辑与原型生成需配置并启用模型。

当前未接入资料上传/RAG、Skill、MCP、Graph 持久化检查点恢复、SSE 或 DOCX/PDF 导出。任务持久化不等于 Graph 恢复，重启后的未完成任务按中断处理并由用户显式重试。原型确认不代表开发交付或生产验收，阶段仍保留为 `PROTOTYPE`。应用默认只监听本机，尚无身份认证；团队、租户和成员权限不在当前产品范围内。

## 技术栈与目录

| 层次 | 技术与版本 |
| --- | --- |
| 后端运行环境 | Java 21、Gradle Wrapper 8.14.3、Spring Boot 3.5.8 |
| 模型调用 | Spring AI 1.1.2、Spring AI Alibaba DashScope 1.1.2.1 |
| Agent / Graph | Spring AI Alibaba Agent Framework 1.1.2.0 |
| 数据访问 | PostgreSQL、MyBatis Plus 3.5.7、dynamic-datasource 4.3.1、Flyway |
| 工作台前端 | Vue 3.5.13、Vue Router 4.5.0、Vite 5.4.21、shadcn-vue、Tailwind CSS 4 |
| 原型模板与执行器 | Vue 3、Element Plus、Vite、Node.js、Playwright / Chromium |

依赖以 Gradle 配置和各目录的 `package-lock.json` 为准。工作台与生成原型使用不同的 UI 组件体系。

```text
project-alpha/
├── backend/                    # Spring Boot、业务服务、模型调用、Graph 与测试
│   ├── src/main/resources/     # 应用配置、Flyway 迁移、PRD 模板
│   └── script/                 # 数据库脚本说明及独立升级材料
├── frontend/                   # Vue 产品经理工作台
├── prototype-template/zero-admin/ # 固定原型模板、组件契约与来源清单
├── prototype-worker/           # 原型源码检查、构建与浏览器验证
├── docs/                       # 架构、设计、实现及历史验证记录
├── scripts/                    # 开发启动与导出验证工具
├── .env.example                # 后端私有配置的空值示例
├── build.gradle                # 统一构建、测试与前端资源打包
└── package.json                # 开发命令入口
```

## 快速启动

### 1. 准备环境

- JDK 21，使用仓库自带 Gradle Wrapper。
- Node.js 22+ 与 npm；仅开发工作台前端时最低要求为 Node.js 20.19。
- 可连接的 PostgreSQL，数据库需预先存在，账号需具备创建 schema 和业务表的权限。
- 使用原型生成能力时，还需本机 Chromium 或可用的 Playwright Chromium 缓存。

```bash
git clone https://github.com/twodogleee/project-alpha.git
cd project-alpha
cp .env.example .env
npm run install:frontend
```

编辑根目录 `.env` 中的数据库连接。默认目标为 `localhost:5432/postgres`，用户为 `postgres`，schema 为 `project_alpha_dev`，密码在本机填写。自定义数据库 URL 时，`currentSchema` 与 `ALPHA_DATABASE_SCHEMA` 必须一致。首次启动由 Flyway 创建 schema 并执行缺失迁移。

后端读取启动工作目录的 `.env`，系统环境变量优先。`.env` 不进入版本控制或 JAR；已有本地配置时保留原文件。

### 2. 分别启动后端与前端

在仓库根目录的终端一执行：

```bash
npm run dev:backend
```

等待后端输出 `Started AlphaApplication`，再在终端二执行：

```bash
npm run dev
```

| 入口 | 默认地址 |
| --- | --- |
| 工作台 | <http://127.0.0.1:5178> |
| 后端运行信息 | <http://127.0.0.1:18080/api/alpha/runtime> |
| 健康检查 | <http://127.0.0.1:18080/actuator/health> |

`npm run dev` 只启动前端，等同于 `npm run dev:frontend`。也可用 `npm run dev:all` 同时启动两端；Ctrl+C 会清理两个进程。后端已在 IDE 中启动时，仅启动前端即可。

Windows 单独启动后端使用 `gradlew.bat :backend:bootRun --console=plain`；前端命令不变。IDE 的后端工作目录设为仓库根目录。修改 Java 源码后重启后端，前端使用 Vite 热更新。

前端默认将 `/api` 代理到 `http://127.0.0.1:18080`。后端端口通过 `.env` 中的 `ALPHA_SERVER_PORT` 调整；自定义前端代理可参考 [frontend/.env.example](frontend/.env.example)。

### 3. 开始使用

1. 创建项目和会话，填写原始需求，或直接编辑 PRD。
2. 按模块和页面补充规则、异常与验收标准；回答待确认事项，明确采纳需要写入正文的建议。
3. 配置真实模型后，可通过会话增量修改 PRD，再选择范围生成或更新原型。
4. 核对原型的来源、页面范围与验证结果；需要确认原型时，先保证其绑定有效的 PRD 定稿。
5. 查看历史、恢复为新修订，或导出指定修订。恢复不会继承旧确认，下载也不会自动确认。

## 配置真实模型

在工作台“系统设置”中依次操作：

1. **接入平台**：设置协议、API 基础地址和可选的模型列表地址。
2. **模型配置**：设置所属平台、API 模型标识及 API Key；可显式测试连接。
3. **当前启用选择**：切换为真实模式、选择模型并应用。保存模型配置和启用模型是两个独立动作。

平台、模型、API Key 和当前选择均保存在数据库中，下一次生成使用新选择，无需重启。模型调用失败会报告错误，不自动回退为模板结果。测试连接会发起真实请求，可能产生费用。

| 接入协议 | 基础地址形式 |
| --- | --- |
| 百炼原生 | 服务根地址，如 `https://dashscope.aliyuncs.com`，不含 `/api/v1` |
| OpenAI 兼容 | API 基础路径，如 `https://api.openai.com/v1`，不含完整 `/chat/completions` |

可选的模型列表地址需与基础地址同源，目录采用 OpenAI `data[].id` 格式。没有目录服务时可手工填写模型标识。具体协议、编辑与错误处理见[模型目录设计](docs/model-catalog-design.md)及[模型配置设计](docs/model-configuration-design.md)。

已有 API Key 不回显、不写浏览器存储，由后端使用 AES-256-GCM 加密。默认首次保存密钥时生成私有文件 `data/model-config.key`；也可在后端配置 `ALPHA_MODEL_CONFIG_ENCRYPTION_KEY` 或 `ALPHA_MODEL_CONFIG_KEY_FILE`。**备份或迁移模型配置时，同时保管原加密主密钥。** 旧密文迁移及配置边界见相关设计文档和 `.env.example`。

## 原型生成与预览

原型使用固定的 Zero 管理端模板和本机 Worker。模型在 Java 后端生成计划与源码，Worker 负责受限源码检查、固定构建和 Chromium 验证；模型密钥不传给 Worker。工作台只展示通过验证的候选，业务确认仍由用户发起。

先安装依赖并准备本机工具链：

```bash
npm run prototype:install
npm run prototype:prepare
npm run prototype:doctor
```

`prepare` 检查依赖、试启已有浏览器并记录版本指纹，不下载浏览器。自动检测失败时，通过 `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` 指定 Chromium 可执行文件。后端启动时从私有 `.env` 读取；独立运行 CLI 时在终端设置。更换浏览器或工具链后重新执行 `prepare`。

以下命令运行本地样板，适合先检查工具链：

```bash
npm run prototype:test
npm run prototype:build
npm run prototype:preview
```

样板明确标记为模板联调，不是模型生成结果。预览入口以终端输出为准，Ctrl+C 停止。真实生成从工作台的“原型”入口发起，需要已启用模型及可用工具链。

原型产物保存于 `data/prototypes/`，业务来源、修订和确认保存在 PostgreSQL。预览及导出页面使用内存模拟数据，不连接实际业务后端。源码包可按包内说明重建；静态包通过附带的 HTTP 服务打开，不使用 `file://`。

实现和协议见 [Worker 说明](prototype-worker/README.md)、[组件契约](prototype-template/zero-admin/component-contracts.md)、[原型生成](docs/prototype-generation-implementation.md)、[工作台接入](docs/prototype-workbench-implementation.md)及[版本与交付](docs/prototype-delivery-implementation.md)。

## 验证与打包

### 常规验证

```bash
./gradlew :backend:test
npm --prefix frontend test
npm --prefix frontend run build
npm run prototype:test
```

常规后端测试使用 H2 和模拟模型平台，不依赖 PostgreSQL，也不调用付费模型。原型模板与 Worker 测试单独运行；完整浏览器与真实模型验证另有显式入口。

### 构建单 JAR

```bash
./gradlew assembleApp
java -jar backend/build/libs/project-alpha.jar
```

`assembleApp` 执行后端检查、前端测试和构建，将前端静态资源打入 JAR。它会安装前端锁定依赖；开发 `bootRun` 不触发前端构建。请从仓库根目录启动 JAR，以读取私有配置并定位 Worker 和模板。单 JAR 的工作台入口为 <http://127.0.0.1:18080>。

原型 Worker、模板和浏览器仍是外部运行依赖，未打入 JAR；需要原型能力时保留相应目录并完成工具链准备。

### 按需集成验证

| 命令 | 验证范围与前提 |
| --- | --- |
| `./gradlew :backend:postgresTest` | 使用可用的 PostgreSQL，在随机隔离 schema 中验证迁移与持久化；不包含在默认打包任务中 |
| `./gradlew :backend:prototypeBrowserTest` | 启动隔离 H2 与合成执行器的工作台浏览器验收夹具；不调用付费模型 |
| `ALPHA_PROTOTYPE_LIVE_TEST=true ./gradlew :backend:prototypeLiveTest --console=plain` | 显式使用当前启用模型、PostgreSQL、Vite 和 Chromium 验证真实闭环，会产生模型调用 |

`docs/` 中的验证记录描述当时使用的环境与结果，不代表每次克隆已自动完成验收。数据库迁移由 Flyway 应用未执行版本，独立 SQL 的用途和前提见[数据库脚本说明](backend/script/README.md)。

## 源码导读

Java 源码包根为 `backend/src/main/java/com/_36knight/alpha/`。

| 想理解的问题 | 从哪里开始 |
| --- | --- |
| 一轮 PRD 编辑怎样固定上下文并处理修订冲突？ | `workspace/WorkbenchService.java`、`agent/WorkbenchWorkflow.java` |
| 模型实际收到什么，回答怎样转换为 Java 对象？ | `agent/SpringAiRequirementAnalyzer.java` 的 `editWorkbench`、`workspace/WorkbenchContracts.java` |
| 页面计划与文件生成怎样衔接？ | `prototype/PrototypeWorkflow.java`，观察 `plan_pages → generate_files` |
| 模型怎样被装配和动态切换？ | `config/AgentModelConfiguration.java` 及模型配置相关服务 |
| 模型怎样请求只读页面规则查询？ | `agent/tool/PageRuleTools.java`、`workspace/PageRuleQueryService.java`，见[工具实现与实验](docs/页面规则查询工具.md) |
| 规则和异常怎样被验证？ | `backend/src/test/java/com/_36knight/alpha/` 下的 Workbench、Prototype、Model 相关测试 |

当前主要业务入口采用模型调用与显式 Graph。新增的只读页面规则工具通过独立 ChatClient 集成测试验证，可供后续评审流程接入；尚未自动注册到现有工作台对话。依赖 Agent Framework 不表示已经实现自主工具循环、多 Agent、RAG、Skill 或 MCP；这些能力适合作为后续独立扩展，按实际代码与运行记录说明完成状态。

## 文档导航与排错

| 文档 | 内容 |
| --- | --- |
| [架构与接口](docs/architecture.md) | 业务边界、接口及持久化说明 |
| [统一工作台](docs/unified-workspace-design.md) | 同一份 PRD 的编辑、模板、回收与历史兼容 |
| [模型目录](docs/model-catalog-design.md) | 平台、模型、当前启用选择与密钥处理 |
| [原型版本与交付](docs/prototype-delivery-implementation.md) | 恢复、确认、导出及阶段验证记录 |
| [PRD 示例](docs/prd-examples/personnel-management-prd.md) | 人员管理需求案例 |
| [UI 组件说明](frontend/src/components/ui/README.md) | 工作台组件来源与维护方式 |
| [Zero 模板说明](prototype-template/zero-admin/README.md) | 原型组件采用范围、基线与来源清单 |

较早的阶段设计保留在 `docs/` 中供追踪演进；当前使用范围以本 README 和对应能力的实现记录为准。

- **前端出现 `ECONNREFUSED`**：确认后端已启动，再检查前端代理与后端端口。
- **数据库启动失败**：检查 `.env`、数据库是否存在、schema 配置及账号权限。
- **模型请求失败**：按页面诊断编号查后端日志，核对协议、地址、模型标识和账户状态。
- **原型不可用**：确认真实模型已启用，运行 `prototype:doctor` 检查 Node、模板与浏览器准备状态。

后端同时输出控制台与 `logs/app.log`，目录可通过 `ALPHA_LOG_DIR` 调整。日志按天和 100 MB 滚动，保留 3 天，归档总量上限 1 GB。诊断记录使用编号关联页面错误，避免记录 API Key、完整模型正文或完整上游响应。

```bash
tail -f logs/app.log
```
