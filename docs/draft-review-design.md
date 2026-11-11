# 需求草稿评审与持久化设计

## 分层与数据流

工作台 → ProjectController → ProjectService → ProjectRepository → MyBatis Plus Mapper → PostgreSQL。
只有创建时调用原有 RequirementWorkflow。Graph 的临时 State 不作为业务数据库，不承担历史恢复。

## 数据模型

- ProjectDocument：id、name、originalContent、mode、runId、revision、createdAt、updatedAt、title、overview、requirements、questions、suggestions、prdMarkdown。
- RequirementItem：id、text、status（PENDING/CONFIRMED）、source（GENERATED/USER/SUGGESTION）、sourceId（建议 ID 或空）。
- Clarification：id、text、answer；问题保留生成原文，回答可编辑，空白表示尚未回答。
- DesignSuggestion：id、text、status（PENDING/ACCEPTED/REJECTED）；已接受建议保留接受时内容。
- project_documents：项目 ID、名称、当前 revision、更新时间、当前 JSON。
- project_versions：存储 UUID 主键、项目 ID、revision、更新时间、不可变 JSON；项目 ID + revision 唯一，外键关联项目。

当前按项目快照保存 JSONB：便于保持历史正文与导出完全一致。以后跨需求查询/统计时，再迁移为需求明细表；不能把此实现描述为正式审批系统。

## 保存契约与规则

PUT 使用 expectedRevision 和完整可编辑字段；来源、原始输入、生成模式、运行标识、时间及版本由服务端维护。
新增需求 id 为空，由服务端分配 UUID。现有项 ID 必须属于本项目且唯一。问题和建议必须完整保留，不能移除或跨项目引用。
已确认项正文改变时强制 PENDING。接受建议由服务端增加 SUGGESTION 来源的 PENDING 需求；已接受建议及关联关系不可删除。
通过 UPDATE … WHERE revision = expectedRevision 实现原子并发校验；同一事务写版本。失败回滚，不做隐式覆盖或重试。

## API

所有响应沿用 ApiResponse，路径 `/api/alpha/projects`。

| 方法 | 路径 | 契约 |
| --- | --- | --- |
| POST | /projects | name、content → 分析并保存版本 1，201 |
| GET | /projects | 最近更新的项目摘要列表 |
| GET | /projects/{id} | 当前完整文档 |
| PUT | /projects/{id} | expectedRevision、name、title、overview、requirements、questions、suggestions → 新版本 |
| GET | /projects/{id}/versions | 版本摘要，按版本倒序 |
| GET | /projects/{id}/versions/{revision} | 只读历史完整文档 |

PUT requirements 元素：id（新项 null）、text、status；questions：id、answer；suggestions：id、text、status。
400 INVALID_INPUT：字段或关联无效；404 PROJECT_NOT_FOUND/VERSION_NOT_FOUND；409 VERSION_CONFLICT；502 AGENT_EXECUTION_FAILED；503 STORAGE_UNAVAILABLE。
列表按更新时间倒序，最多 100 个项目；版本摘要最多最近 100 个，已知历史 revision 仍可直接读取。当前规模面向本地单用户。

## 页面交互

左侧项目列表与新建入口，右侧编辑器包含需求评审、澄清问答、设计建议、PRD、版本历史。
项目 ID 写入 Hash 路由查询参数，刷新直接恢复对应项目。编辑状态与已保存响应分开维护。
保存完成才替换已保存版本和 PRD；冲突时保留编辑，用户明确选择重新载入。历史版本只读，可导出该历史版本 PRD。
切换项目、创建新项目、重载当前项目和离开页面时保护未保存编辑。保存/载入期间禁止相关编辑动作。

## 存储与运行

默认 JDBC URL 为 `jdbc:postgresql://localhost:5432/postgres?currentSchema=project_alpha_dev`。后端从根目录私有 `.env` 或系统环境变量读取连接；数据库密码不进入代码、前端或响应。
MyBatis Plus 与动态数据源沿用 Zero 的版本，使用 BaseMapper、Lambda 查询、PostgreSQL 分页插件与显式 revision 条件更新。Flyway 管理独立 Alpha schema，JSONB TypeHandler 将快照写为对象，时间字段为 timestamptz。
常规测试使用独立 H2 内存/临时文件库；显式 `postgresTest` 使用真实 PostgreSQL 的随机隔离 schema，验证后清理。旧 H2 数据保留但不自动导入。
参考边界、迁移与部署建议见 [数据库设计](postgresql-mybatis-design.md)。

## 验证

覆盖生成来源、编辑确认、正文修改撤销确认、建议处理、来源保护、项目隔离、持久化重开、不可变版本、并发保存冲突和 API 错误契约。
前端通过纯状态辅助函数测试与浏览器操作验证，生产构建检查 Vue 模板和资源。
