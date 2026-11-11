# 工作空间第一阶段实现设计

日期：2026-10-13。作者：Andersen。状态：已实现并验证。

## 范围与用户决定

本轮实现项目、会话、需求版本的关系和桌面交互。用户允许不保留旧需求评审功能和历史数据，因此替换旧 Workbench，不导入旧评审文档，不兼容旧项目 ID。旧表和旧后端接口仍保留为历史实现，新工作空间只读取新目录，不在两套接口间同步数据。系统设置及加密模型配置继续沿用。

正式需求评审、已确认基线、PRD 定稿、原型、开发与验收属于下一阶段；本轮不会生成已确认需求或假装完成生命周期。仅面向单用户桌面端，不加入团队、成员、权限或移动端需求。

## 对象和写入边界

| 对象 | 归属和职责 | 写入方式 |
| --- | --- | --- |
| 项目 | 持续维护的产品，拥有背景、多个会话、多个需求版本 | 创建不调用模型，背景编辑不重写历史 |
| 会话 | 必须属于项目，可探索或关联同项目的一个版本 | 多轮消息独立持久化；改名、归档、恢复不结束交付 |
| 会话草稿 | 讨论中的编辑产物，不是正式需求基线 | 候选先显式应用到本地副本，再人工保存 |
| 需求版本 | 用户命名的一次交付范围，有独立 UUID、目标和状态 | 手工创建，或从探索会话当前保存稿形成 |
| 保存记录 | 当前草稿或版本内部的不可变修订 | 保存和显式同步增加修订，不创建新业务版本 |
| 模型任务 | 一次 CHAT 或 DRAFT 命令及其结果 | 用户消息先保存，再后台执行，终态可查询 |

一个会话最多关联一个需求版本，关联后不支持改绑；一个版本可以拥有多个会话。关联会话本身不覆盖范围。会话草稿保存与版本范围保存独立，只有显式同步才使用保存稿替换范围；同步记录会话 ID 和草稿修订来源。

同项目版本名称去除首尾空白后唯一，改名不改变 UUID 或路由。重复提交同一已保存稿、名称和目标的形成命令返回同一版本。版本当前阶段固定为 REVIEW，前端显示“需求范围”；生命周期只允许 ACTIVE / CANCELED。取消需要原因，已取消范围只读。只有已取消版本可以归档，恢复保留取消状态。本轮没有任意写 lifecycle 或阶段推进/完成 API。

项目存在活动版本或执行中任务时不能归档。会话执行中不能归档，归档后不允许继续发送或保存草稿；父项目归档后先恢复项目，再恢复子对象。已取消关联版本不能继续模型任务或草稿编辑。归档为可恢复目录状态，不物理删除。

## 桌面交互

固定左侧导航展示项目及当前项目最近 12 个会话，并提供项目概览、全部项目、归档记录和系统设置。侧栏“新建会话”打开当前项目的创建表单，不自动创建空白记录。

项目概览将项目背景、需求版本卡片、会话列表分别展示。新会话可选择活动版本；新版本只填写名称与目标，随后在版本详情补充范围。创建项目、会话、版本均不隐式调用模型。

会话主区持续讨论，侧栏编辑需求草稿。整理候选、应用候选、保存草稿、形成版本、同步范围为独立动作。消息标注当次运行的模式和模型，本地模板明确显示“未调用大模型”。运行任务不依赖页面生命周期，切换会话或刷新可继续读取状态；未发送消息和未保存草稿有离开保护。

版本详情拥有范围编辑器、保存记录、关联会话和四阶段路径。PRD、原型、开发与验收标注“尚未接入”。历史记录只读，打开历史不覆盖当前副本。下载为需求范围草稿，不称为正式 PRD。并发冲突保留本地输入，要求明确重新载入后合并。

Hash 路由：`/projects`、`/projects/:projectId`、`/projects/:projectId/sessions/:sessionId`、`/projects/:projectId/versions/:versionId`、`/archives`；旧 `/workspace` 重定向项目目录。

## 数据库

V4 新增结构，V1–V3 不改写：

| 表 | 内容 |
| --- | --- |
| workspace_projects | 名称、背景、revision、创建/更新/归档时间 |
| work_sessions | 项目/可空版本外键、标题、草稿当前正文与修订、元数据 revision、消息序号 |
| session_messages | 会话、稳定序号、角色、纯文本、模式/模型、run_id |
| workspace_runs | 项目/会话、幂等 request_id、任务种类、输入与固定上下文、生成基准、状态/结果/安全错误 |
| session_draft_revisions | 会话草稿不可变正文、修订、可空来源任务 |
| requirement_versions | 项目、唯一业务名称、目标/范围、revision、阶段/生命周期、形成来源、取消原因、归档时间 |
| requirement_version_revisions | 不可变范围快照、名称/目标、保存修订和同步来源 |

同项目关联使用复合外键，禁止跨项目会话/版本绑定；消息序号、幂等请求和历史修订分别有唯一约束。正文用 text，关系与状态为独立字段，不把自由文本拆成已确认需求结构。时间为 timestamptz，Mapper 使用 MyBatis Plus。分页每页 50，历史消息使用 before 序号，前端按稳定 ID 去重合并。

## 服务、事务与执行

WorkspaceController 负责校验与响应，WorkspaceService 负责关系/业务规则，WorkspaceRepository 和 Mapper 负责持久化。所有同项目写入在短事务中锁定项目根，并校验 expectedRevision；当前对象与历史记录在同一事务写入。此锁保护单用户多窗口的并发，不引入多人产品规划。

CHAT/DRAFT 请求先保存用户消息和 QUEUED 任务，再交给四线程执行器。每会话最多一个未结束任务；相同 session/request_id 的重试返回已有任务，不再次收费调用；相同请求标识对应不同命令会拒绝。模型调用在数据库事务外，执行时从数据库模型目录读取一次配置快照，凭证只保留在模型客户端，不写入任务上下文或响应。

模型上下文固定项目背景修订、关联版本修订及近期消息。最多 20 条消息、消息正文总预算 24000 字符，背景最多 6000，版本目标 4000、范围 8000。完整历史仍保存在数据库。CHAT 使用文本对话；DRAFT 使用结构化分析并校验后生成 Markdown，需求候选、澄清问题、未接受建议分别表达。

任务终态为 SUCCEEDED / FAILED / INTERRUPTED。服务重启将遗留 QUEUED/RUNNING 标记 INTERRUPTED，输入保留，只有明确重试才再次调用模型；这不是 Graph 检查点恢复。失败只返回安全说明，不暴露上游错误或自动退回模板。

会话底部只突出显示最新任务的失败或中断，支持关闭本次查看的提示，轮询不会重新展开同一次已关闭的提示。新任务排队、执行或成功后收起旧提示；新的失败仍正常提醒。已加载的历史失败归属原用户消息，以折叠详情保留安全说明和显式重试入口。关闭提示不修改服务器任务记录；重试历史请求不清空输入框内其他尚未发送的内容。

生成候选携带草稿修订与关联版本修订。应用保存检查当前生成基准，草稿已变化、版本范围已变化或探索会话已关联其他目标时返回 WORKSPACE_REVISION_CONFLICT；手工合并可重新保存。同步同时校验目标版本 revision 与当前已保存草稿 revision。

## API

统一前缀 `/api/alpha/workspace`，统一 `{code,message,data}` 响应。以下参数对象详见 WorkspaceRequests，分页响应使用 `{items,hasMore}`。

| 方法/路径 | 含义与主要契约 |
| --- | --- |
| GET / POST projects；GET / PUT projects/{id} | 目录、创建、读取、背景更新；编辑携带 expectedRevision |
| PUT projects/{id}/archive | expectedRevision、archived |
| GET / POST projects/{id}/sessions | 会话目录及创建；可空 versionId |
| GET sessions/{id}；GET sessions/{id}/messages?before=... | 当前会话、近期任务与消息历史 |
| PUT sessions/{id}/title、/archive、/version | 改名、归档恢复、探索会话一次关联 |
| POST sessions/{id}/runs | requestId、kind CHAT/DRAFT、content；202，返回持久化 run |
| GET runs/{id} | 安全任务状态；不返回 inputText/contextText/凭证 |
| PUT sessions/{id}/draft | expectedRevision、content、可空 sourceRunId |
| POST sessions/{id}/promote | expectedRevision、draftRevision、label、goal |
| POST sessions/{id}/sync | 目标 expectedRevision、draftRevision |
| GET / POST projects/{id}/versions | 版本目录、手工创建 label/goal/content |
| GET / PUT versions/{id} | 独立版本详情、保存范围，expectedRevision/label/goal/content |
| POST versions/{id}/cancel | expectedRevision、reason |
| PUT versions/{id}/archive | expectedRevision、archived |
| GET versions/{id}/revisions[/{revision}] | 分页历史与指定只读快照 |
| GET archives | 三类归档目录，page 分页 |

HTTP 400 用于输入/关联不合法，404 为不存在，409 用于并发与状态冲突。数据库实体不直接返回客户端，输入上下文与密文不能从工作空间接口读取。

## 后续接续

阶段 B 在当前需求版本上增加结构化需求、阻塞问题、人工确认基线、PRD 产物/修订和明确阶段门槛；随后接入原型与开发验收。第一阶段保存记录不自动升级为已确认基线，需通过后续显式确认命令。
