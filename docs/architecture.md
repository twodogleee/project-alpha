# Alpha 架构与接口

日期：2026-10-13。作者：Andersen。

## 当前职责

前端使用 Vue 3 / shadcn-vue / Tailwind CSS。AppShell 维护固定桌面导航和公开运行信息；ProjectDirectory / ProjectOverview 管理产品目录与背景；ConversationWorkspace 管理持续消息与独立草稿副本；VersionWorkspace 管理交付范围和版本身份，VersionLifecycle 管理正式评审、PRD、人工确认和来源链；SystemSettings 管理数据库平台/模型目录及编辑抽屉。路由离开、抽屉关闭和浏览器退出保护未保存输入，密钥不进入共享外壳或浏览器持久化。

```text
Vue 工作空间 → WorkspaceController → WorkspaceService → WorkspaceRepository
                                            │                  ↓
                                            │            MyBatis Plus → PostgreSQL
                                            ↓
                                    持久化消息与任务
                                            ↓
                              后台执行器 → ModelRuntimeService.snapshot()
                                            ↓
                              RequirementAnalyzer.converse / analyze
                                            ↓
                              安全终态、助手消息、候选结果
```

项目是产品容器，会话是独立持续讨论，需求版本是一次交付单元。草稿、版本范围及各自修订与任务上下文分开保存，候选结果不会自动覆盖业务数据。WorkspaceController 仅校验和响应，应用服务执行关系约束、短事务项目锁与 revision 检查；模型网络调用在事务外。

ModelCatalogService 管理平台、模型和当前选择，ModelRuntimeService 读取一致运行快照。模型 API Key 加密保存，客户端使用凭证但不将其加入上下文、任务、消息或响应。每个结果标注当次实际模式与模型，demo 明确为本地模板联调，失败不自动退回模板。

评审与 PRD 通过 LifecycleService 的人工基线命令推进，PrdWorkflow 组织固定基线、候选生成和结构校验。PRD 确认后保留原型阶段里程碑，原型生成与预览由统一工作台承接，完成交付尚未接入；已取消版本只读，归档恢复不更改生命周期。详细数据关系、任务执行、归档规则、接口和限制见 [第一阶段实现设计](workspace-phase-one-design.md)。

## 当前接口目录

| 前缀/接口 | 职责 |
| --- | --- |
| `/api/alpha/workspace/projects` | 产品背景、项目目录与归档 |
| `/api/alpha/workspace/projects/{id}/sessions` | 项目下会话创建与分页 |
| `/api/alpha/workspace/sessions/{id}` | 消息、草稿、任务、关联版本、形成与同步 |
| `/api/alpha/workspace/runs/{id}` | 安全运行状态轮询 |
| `/api/alpha/workspace/projects/{id}/versions` | 自定义业务版本创建与分页 |
| `/api/alpha/workspace/versions/{id}` | 独立需求范围、只读历史、取消、归档恢复 |
| `/api/alpha/workspace/versions/{id}/lifecycle` | 正式评审、PRD、基线、生成任务与重新评审，详见阶段 B 设计 |
| `/api/alpha/workspace/versions/{id}/prototype` | 原型聚合、来源与修订读取、生成任务及候选应用；P4 连接真实模型与受控 Worker，环境可用时执行 |
| `/api/alpha/workspace/archives` | 三类归档分页，排除已删除对象 |
| `GET /api/alpha/workspace/trash/sessions` | 已删除会话分页 |
| `GET /api/alpha/workspace/sessions/{id}/deletion-preview` | 删除影响范围、会话 / 版本 / 文档修订 |
| `PUT /api/alpha/workspace/sessions/{id}/deletion` | 项目锁内删除或恢复会话及其独立 PRD |
| `/api/alpha/runtime` | 当前公开模式与模型信息 |
| `/api/alpha/model-config` | 数据库平台、模型目录与当前启用选择，不返回凭证 |
| `/api/alpha/model-config/platforms[/{id}]` | 平台 CRUD、目录 revision 校验 |
| `/api/alpha/model-config/models[/{id}]` | 模型 CRUD、单向提交密钥 |
| `/api/alpha/model-config/runtime` | 当前启用选择，不隐式调用模型 |
| `/api/alpha/model-config/test[?modelId=...]` | 明确测试模型表单，不保存、不创建业务项目 |
| `GET /api/alpha/config/prd-templates` | 内置与自定义全部已发布版本、旧九章节定义及 `deletedTemplateIds`；不包含草稿 |
| `GET /api/alpha/config/prd-templates/management` | 自定义模板管理锁、当前草稿、最新已发布修订及删除状态 |
| `POST /api/alpha/config/prd-templates/copies` | 从指定已发布版本复制自定义草稿，支持幂等请求 |
| `POST/PUT /api/alpha/config/prd-templates/{id}/draft` | 创建下一版草稿 / 保存受限配置；不改写已发布版本 |
| `POST /api/alpha/config/prd-templates/{id}/publish` | 发布已保存草稿为不可变版本，支持幂等请求 |
| `PUT /api/alpha/config/prd-templates/{id}/deletion` | 按管理修订删除或恢复自定义模板，保留发布定义和文档引用 |
| `/actuator/health` | 服务健康状态 |

统一 `{code,message,data}` 响应。工作空间任务提交返回 202；错误 400 为输入不合法、404 为不存在、409 为状态/修订冲突。任务失败通过 FAILED 状态返回安全错误，输入消息保留。上下文、数据库实体和凭证不直接暴露。

工作空间接口字段见 [第一阶段实现设计](workspace-phase-one-design.md) 和 WorkspaceRequests。动态配置、密钥沿用与错误代码见 [模型目录设计](model-catalog-design.md)。

### PRD 模板契约（2026-10-28）

当前统一会话入口由 `UnifiedWorkspace` 承载，直接编辑同一份 PRD。内置模板仍共用 `backend/src/main/resources/prd-templates/catalog.json`：后端在启动时读取，前端在构建时导入。自定义模板由 `PrdTemplateService` 管理，`PrdTemplateRepository` 通过 MyBatis Plus 保存草稿聚合和不可变发布版本。Controller 只校验、调用服务并返回统一响应；配置页面默认阅读，编辑时在抽屉中维护独立草稿副本。

目录响应为 `{code,message,data:{legacyGlobalSections,templates,deletedTemplateIds}}`。模板包含 `id`、`revision`、`name`、`description`、`guidance`、`defaultPageType`、三个层级的章节定义、页面类型及全局 / 模块必填集合。章节是 `{key,title,hint}`；页面类型是 `{key,title,requiredSections}`。内置为 `management@1`、`mobile@1`、`general@1`，分别对应管理系统、App / 小程序和通用产品。`templates` 合并内置及所有自定义已发布版本，包括已删除模板的历史定义，不返回未发布草稿；`deletedTemplateIds` 单独表达哪些自定义身份不能新选择，亦可包含尚未发布过的身份。前端只将不可变定义注册到运行期解析器，并按删除元数据过滤可选目录；遇到未知自定义引用时先读取目录再展示正文，管理草稿不能注册为可引用模板。

PRD JSON 的 `templateVersion` 继续表示结构版本 `1/2`，新增 `templateId` 和 `templateRevision` 固定写作模板。完全缺少绑定的旧文档按 `management@1` 解析；仅 `templateVersion=1` 且为 `management@1` 时使用旧九章节标题。保存经现有双修订 `PUT /api/alpha/workspace/sessions/{id}/workbench` 完成。切换只改变两个模板引用，保留正文、模块页面 ID、页面类型、待确认事项及关联；历史和定稿解析自身绑定，不随当前草稿或目录最新版本变化。

`PrdTemplateService.resolve` 按精确身份和修订查找内置或数据库发布版本，不回落到最新版本或草稿。模型上下文、定稿校验、路径标签、历史与导出沿用文档绑定；模型不能修改模板引用。草稿可调整现有标准结构中的标题、提示、组内顺序和非基础必填集合，所有稳定键、11 种页面类型及基本完整性要求由服务端验证。

自定义删除是 `prd_templates.deleted` 状态变更，保留当前草稿与全部发布版本；内置身份拒绝删除、恢复及直接编辑。管理响应 `Managed.deleted` 区分正常与已删除目录，`PUT /{id}/deletion` 接收 `{expectedRevision,deleted}` 并返回最新管理对象。已删除模板不能新复制、开草稿、保存模板草稿或发布；恢复保留原 ID 和版本。精确版本解析不检查删除状态，保证旧文档、历史及进行中模型任务仍可阅读和使用。

PRD 保存中的新绑定检查与历史解析分离。`LifecycleService` 的统一会话保存及旧 PRD 保存均在项目事务中比较服务器已有的 `templateId + templateRevision`：相同绑定继续允许；首次绑定、换身份或换修订时，目标自定义模板必须未删除。新绑定取得模板行锁，与删除、恢复及模板保存、发布串行化。已删除目标返回 HTTP 409 / `PRD_TEMPLATE_DELETED`，不清空正文或改用其他模板；模板管理修订冲突继续返回 `PRD_TEMPLATE_CONFLICT`。

自定义聚合的 `revision` 是并发锁，`draft.revision` 和 `latestPublishedRevision` 才是模板版本，二者不能混用。复制和发布以请求 ID 幂等；发布重试不会发布后来创建的新草稿。模板升级只能追加不可变版本，不能覆盖历史。发布自定义模板后目录立即可读，无需应用重启或前端重新构建；内置资源变更仍通过共同构建发布。

Flyway V9 新增 `prd_templates` 与 `prd_template_versions` 两张表，承接自定义草稿、管理修订及发布定义。后续 V10 仅为 V9 的模板聚合表增加 `deleted BOOLEAN NOT NULL DEFAULT FALSE`，不修改已发布 V9 脚本、旧 PRD JSON、历史或模板绑定。正常启动由 Flyway 按序执行缺失迁移，不另行手工重复执行；独立 V9 / V10 增量与按序合并的 V9 + V10 升级脚本见 [数据库脚本说明](../backend/script/README.md)。这里只描述代码与迁移契约，不表示业务或生产数据库已升级。交互与验证记录见 [模板删除与恢复](unified-workspace-design.md#模板删除与恢复2026-10-28)。

## 历史实现边界

旧 AgentController / AgentService / RequirementWorkflow 仍保留 `normalize_input → analyze_requirement → render_prd` Graph 与 `/api/alpha/agent/drafts` 单次分析接口；旧 ProjectService 的评审项目接口及旧表仍保留。新工作空间不调用旧项目接口，不导入旧评审对象，也不在两个目录之间同步数据。

Graph State、业务任务持久化和正式产物是不同层次。新会话具有持久化任务与消息，重启将未结束任务标为 INTERRUPTED，需用户显式重试；尚未实现 Graph 检查点恢复。旧 Graph API 文档见 [历史草稿评审设计](draft-review-design.md)。

## 后续扩展

原型 P3 已复用 `workspace_runs` 与消息体系，六张新表承接原型、来源、修订、任务扩展、确认与恢复契约。业务服务通过短事务项目锁、完整命令幂等和执行租约管理结果；文件先经独立摘要核验再引用。P4 的 `PrototypeWorkflow` 组织模型计划与源码生成，`PrototypeGenerationRunner` 管理单槽、心跳及两轮内修复，`PrototypeBuildService` 通过固定 stdin 协议调用隔离 Worker。模型配置只在 Java 后端固定，容器没有凭证。重启或过期任务中断，不自动重试。直接 PRD 继续编辑可保留 `PROTOTYPE` 阶段里程碑；P5 在统一会话中提供原型入口、范围选择、任务轮询、页面级修改和来源/历史；Java 受控预览服务以短时修订会话和双层隔离 iframe 提供静态预览。当前实现及实际验证见 [P3 业务持久化](prototype-persistence-implementation.md) 、[P4 模型生成](prototype-generation-implementation.md) 和 [P5 工作台](prototype-workbench-implementation.md)。

P6 在原型业务服务接入历史恢复和人工确认：事务外核验冻结文件，项目锁内复核四种修订及完整命令幂等；恢复追加新修订，确认独立绑定有效 PRD 定稿和完整生成范围。`PrototypeExportService` 为指定修订建立有界文件快照后流式输出源码或静态 ZIP，提供来源、验证、归属及固定工具链重建/独立预览脚本。导出不调用模型或改变业务状态。具体接口与验证见 [P6 版本与交付](prototype-delivery-implementation.md)。

阶段 B 已建立结构化评审、阻塞事项、不可变人工基线与 PRD，原型产物已沿用具体上游基线形成独立修订和确认；后续开发交付和生产验收仍未接入。详见 [阶段 B 设计](requirement-prd-lifecycle-design.md)。保存修订、下载、模型成功均不推进阶段，原型确认继续保留 `PROTOTYPE`。单用户桌面范围保持不变，不规划团队、租户、项目成员或权限模块。

### 会话回收站（2026-10-28）

V11 为 `work_sessions`、`requirement_versions` 增加可空 `deleted_at`。删除状态独立于归档、交付取消和定稿，普通目录过滤删除状态，回收站按会话分页。删除会话不清理消息、附件、任务、模板绑定、PRD JSON 或历史。最后一个未删除的关联会话被删除时标记文档，恢复任一关联会话时清除文档删除标记；归档会话同样参与关联计数。

删除预览返回 `session`、`versionRevision`、`documentRevision`、`includesPrd`、`otherSessions`。命令包含 `expectedRevision`、`deleted`、`expectedVersionRevision`、`expectedDocumentRevision`、`includesPrd`。删除在同一项目锁内重新比对影响范围和三个修订，阻止执行中会话任务及共同回收文档的版本任务；状态过期返回409。恢复只需会话修订，`includesPrd=false`，可省略文档修订；父项目归档时先恢复项目。版本回收和恢复递增锁值并追加范围快照，正文和定稿快照不改写。

新旧写入口统一拒绝已删除会话或版本。直接打开已删会话只读；直接打开已删版本拒绝隐式创建新会话。前端统一会话菜单在侧栏、项目列表和标题使用，当前编辑页提供保存保护，失败保留输入。回收站恢复与普通目录刷新共用外壳通知。


### 文档独立回收（2026-10-28）

关联文档默认展开，可手动收起；每个文档卡片提供独立的删除入口。文档回收不删除会话、消息、附件或关联，文档删除期间所有关联会话中的 PRD 只读。回收站分别展示会话与文档，文档可只读打开并恢复。

| 接口 | 用途 |
| --- | --- |
| `GET /api/alpha/workspace/versions/{id}/deletion-preview` | 返回版本修订、正文修订和未删除关联会话数量 |
| `PUT /api/alpha/workspace/versions/{id}/deletion` | 单独删除或恢复文档 |
| `GET /api/alpha/workspace/trash/versions` | 已删除文档分页 |

删除在项目锁内校验 `expectedRevision`、`expectedDocumentRevision`、`expectedLinkedSessions`，任一变化或存在执行中任务都拒绝；恢复只校验版本修订及父项目可用状态。V12 的 `independently_deleted` 区分文档单独删除和随最后会话回收：恢复会话仅联动恢复后一类文档，不能撤销单独删除；恢复文档也不恢复已删除会话。正文、模板绑定、规则和待确认关联、历史、归档及定稿均不改写。

已删除文档的版本工作区入口只读返回已有产物，不新建会话，也不初始化缺失 PRD。前端没有会话的只读文档仍可查看正文、完整预览和已有历史。
