# PostgreSQL 与 MyBatis Plus 设计

## 正式需求与范围

用户指定使用本机 PostgreSQL（localhost:5432，postgres 数据库用户），参考 本机 Zero 后端工程 的数据库配置与技术，采用 MyBatis Plus。

本次将已有需求草稿存储替换为 PostgreSQL / MyBatis Plus，保留创建、编辑确认、历史快照、导出与并发冲突契约。凭证仅保存于根目录已忽略的私有 `.env`，不进入源码、JAR、前端或版本控制。数据库使用 `postgres` 库下的独立 `project_alpha_dev` schema，不修改 Zero 的 schema。

产品按单用户工作台规划，多人使用不纳入需求。采用 PostgreSQL 不引入团队协作、租户、成员或角色权限的建设任务。

## 参考 Zero 的技术边界

| Zero 参考 | Alpha 采用 |
| --- | --- |
| config.gradle | MyBatis Plus Boot 3 starter 3.5.7、dynamic-datasource Boot 3 starter 4.3.1 |
| application.yml 动态 master 数据源 | master 为主数据源、严格路由、HikariCP，PostgreSQL URL + currentSchema |
| Entity / BaseMapper | 独立持久化实体，@TableName / @TableId / @TableField，BaseMapper 查询与插入 |
| Mapper XML | revision 条件更新的显式 XML，与版本插入共享 Spring 事务 |
| PostgreSQL 分页拦截器 | PaginationInnerInterceptor(POSTGRE_SQL)，最多 100 条摘要 |

Hikari 参数保留连接检测 select 1、连接超时 3000ms、空闲超时 30000ms、最大生命周期 1800000ms、最大池 15；本地最小空闲连接取 2。未引入尚无业务支撑的数据权限拦截器、多租户插件或 Zero 认证模块，也未执行 Zero 基线同步。Flyway 是 Alpha 此次增加的结构迁移管理。

## 分层与事务

```text
ProjectController → ProjectService → ProjectRepository
                                      ├── ProjectDocumentMapper → project_documents
                                      └── ProjectVersionMapper  → project_versions
```

业务服务仍维护稳定业务 ID、确认规则、建议来源与 PRD 生成。Repository 只负责映射、序列化、查询和事务；持久化实体不作为 API 返回对象。

创建在同一事务插入当前项目与 v1。保存执行 `UPDATE project_documents ... WHERE id = ? AND revision = ?`，受影响行数必须为 1，然后插入历史快照；冲突返回 409，版本插入失败回滚当前状态。此校验由数据库完成，用于同一用户多个窗口编辑时防止误覆盖；模型调用不占用该保存事务。

## 数据结构

| 表 | 字段与约束 |
| --- | --- |
| project_documents | id varchar(36) 主键、name varchar(120)、revision bigint 且 >= 1、updated_at timestamptz、document_json jsonb |
| project_versions | id varchar(36) 存储主键、project_id 外键、revision bigint 且 >= 1、updated_at timestamptz、document_json jsonb；project_id + revision 唯一 |
| model_configuration | V2 旧单份配置，仅保留作迁移核对，V3 后不参与运行 |
| model_platforms | id、唯一 name、protocol、base_url、updated_at |
| model_profiles | id、platform_id 外键、name、model、api_key_ciphertext、updated_at；platform_id + name 唯一 |
| model_runtime_selection | 固定 id=1、目录 revision、mode、active_model_id 外键、updated_at |
| flyway_schema_history | Flyway 自动管理迁移记录 |

项目摘要索引为 `(updated_at DESC, id)`。版本唯一约束同时支持项目版本查询。项目 ID、需求项 ID、问题 ID 与建议 ID 保持原有字符串 UUID；新增版本存储 ID 不影响公开的项目 ID + revision 访问契约。

`JsonDocumentTypeHandler` 以 JDBC OTHER 写入 JSONB，使用 autoResultMap 映射读取，保证写入的是 JSON 对象。JSONB 会标准化键顺序，快照语义及正文内容保持一致，不依赖 JSON 字节顺序。updated_at 使用 UTC OffsetDateTime 与 timestamptz，API 继续返回 Instant。

本次维持完整文档快照，正式需求、设计建议、澄清问答仍在业务对象中分别维护。需求级筛选等能力如以后明确纳入需求，再评估结构化业务表；当前不增加用户、团队、租户或成员权限表。

## 本地配置与迁移

`.env.example` 展示公开默认值，不包含实际密码。Spring Boot 显式读取启动工作目录的 `.env`，系统环境变量优先。Gradle bootRun 与 postgresTest 的工作目录为工程根目录；JAR 也从根目录启动，其他目录启动需通过环境变量或显式配置提供连接。

- ALPHA_DATABASE_URL：`jdbc:postgresql://localhost:5432/postgres?currentSchema=project_alpha_dev`。
- ALPHA_DATABASE_SCHEMA：`project_alpha_dev`，必须与 URL 的 currentSchema 一致。
- ALPHA_DATABASE_USERNAME：本地为 postgres。
- ALPHA_DATABASE_PASSWORD：只放本地私有配置或部署环境，不记录实际值。

数据库必须预先存在。Flyway 首次创建 schema，并执行 `db/migration/V1__create_project_documents.sql`；后续启动校验并跳过已执行迁移。禁用 Flyway clean 和 Spring schema.sql 自动初始化。模型配置由 `V2__create_model_configuration.sql` 建表，后续结构变化继续新增迁移，不改已执行脚本。V3 创建平台/模型目录并迁移 V2 的配置与密文，主密钥及关系约束见 [模型目录设计](model-catalog-design.md)。

不删除旧 H2 文件，不自动导入旧快照。此次检查未发现根目录 data/ 下的旧开发数据库。未来如需导入旧库，应显式读取、核对项目与历史数量、校验稳定 ID 后在隔离目标验证，再切换；不能仅把 H2 URL 改为 PostgreSQL 并认为数据已经迁移。

## 验证策略

常规 `:backend:test` 使用 H2，只在测试运行时引入该驱动，验证 API、业务、文件重开与事务规则。显式 `:backend:postgresTest` 使用真实 PostgreSQL，从本地私有配置或环境读取连接，创建随机 `alpha_it_<UUID>` schema，执行与应用相同的 Flyway 脚本与 Mapper，完成后仅删除本次随机测试 schema。该任务不写入开发 schema，不调用云模型。

postgresTest 验证 JSONB 对象、中文及特殊字符、时区类型、确认及历史快照、真实并发事务、唯一约束失败回滚和 100 条分页。默认 check / assembleApp 不要求数据库连接；数据库改动需额外运行 postgresTest。

## 数据库部署与维护建议

部署维护按单用户工作台的持久化需求评估，不包含多人使用的数据模型或功能规划。

部署使用独立 Alpha 数据库或受控 schema、专用应用账号与迁移账号、统一密钥管理和 PostgreSQL 备份恢复。应用账号仅授予必要读写权限；迁移由发布流程控制。连接池规模根据数据库连接上限调整。历史快照作为版本查看与导出的依据继续保留。

多人使用、团队协作及相应用户/租户/成员权限模型不纳入需求规划。本次另不包含认证、历史恢复、Graph 检查点恢复、旧 H2 自动导入或线上部署。
