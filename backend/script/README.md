# Alpha 数据库版本脚本

本目录保留业务版本 `v0.1.0` 的 PRD 模板、回收状态和原型持久化独立增量与完整升级 SQL。应用正式迁移由 `backend/src/main/resources/db/migration/` 下的 Flyway V1–V13 管理；这里的 SQL 用于审阅、受控独立执行及与 Flyway 内容比对，不应与应用迁移无区别地重复执行。

## 脚本与前置条件

| 文件 | 适用状态与用途 | 已纳入完整升级 |
| --- | --- | --- |
| [v0.1.0-prd-templates.sql](v0.1.0/v0.1.0-prd-templates.sql) | 前置 V1–V8；对应 V9，新增 `prd_templates` 与 `prd_template_versions` | 是 |
| [v0.1.0-prd-template-deletion.sql](v0.1.0/v0.1.0-prd-template-deletion.sql) | 前置 V9；对应 V10，在 `prd_templates` 增加 `deleted` 删除状态 | 是 |
| [v0.1.0-session-recycle-bin.sql](v0.1.0/v0.1.0-session-recycle-bin.sql) | 前置 V10；对应 V11，会话及独立文档的回收时间与查询索引 | 是 |
| [v0.1.0-document-recycle-bin.sql](v0.1.0/v0.1.0-document-recycle-bin.sql) | 前置 V11；对应 V12，记录文档单独删除来源，增加文档回收站索引 | 是 |
| [v0.1.0-prototype-persistence.sql](v0.1.0/v0.1.0-prototype-persistence.sql) | 前置 V12；对应 V13，新增原型六表、同聚合归属约束及工作空间活动任务唯一索引 | 是 |
| [v0.1.0-upgrade.sql](v0.1.0/v0.1.0-upgrade.sql) | 前置 V1–V8；按表中顺序执行五个增量，即 V9–V13 | 完整升级入口 |

完整升级脚本没有包含 V1–V8，不是空库初始化，也不应在尚未创建 Alpha 业务 schema 的任意数据库直接运行。V10 保持为独立后续迁移，不改写已应用的 V9 文件。

## 常规执行方式

应用启用 Flyway 时，从项目根目录启动新版后端，由 Flyway 在配置的 Alpha schema 内按顺序应用未执行迁移，并记录 `flyway_schema_history`。从已记录版本依次补到 V13；已到 V12 时仅执行 V13，已到 V13 时校验并跳过。**通常无需再手工执行本目录脚本。**

数据库连接从后端私有配置或环境取得，凭证不写入脚本。`ALPHA_DATABASE_SCHEMA` 对应 Flyway 的 `schemas` / `default-schema`；数据源 URL 的 `currentSchema` 必须与它一致。SQL 使用未加 schema 前缀的表名，独立执行前应明确同一 Alpha schema / `search_path`，不得默认沿用其他项目的连接或 schema。

需要受控独立执行时，由操作者确认目标数据库、当前版本与迁移记录处理方式。已有 V12 时只补原型持久化增量；较早版本按表中明确前置条件依次执行。从 V8 升级可使用完整升级，或明确按 V9 → V10 → V11 → V12 → V13 分别执行，不能同时重复选择完整与独立入口。独立 SQL 不写 Flyway 历史，不能将 SQL 成功等同于 Flyway 已记录迁移，也不应随意手工伪造迁移记录。PostgreSQL 下应在事务中执行所选 DDL，使同批结构变更共同成功或回滚。

## 重复执行与数据保护

- V9 使用 `CREATE TABLE IF NOT EXISTS`，仅创建缺失表及对应约束，不覆盖模板、业务正文或发布记录；不会修复已存在同名表的异常字段和约束。
- V10 使用 `ADD COLUMN IF NOT EXISTS`，为原有记录提供 `deleted=false`，不改已有列的删除状态。已删除模板不会因为重复执行脚本而恢复；若同名列已存在但类型或约束错误，需要另行核查。
- V11 仅补会话、版本的可空 `deleted_at` 和回收站索引，重复执行不恢复已删会话，不改变归档、正文、历史或定稿。
- V12 为版本增加 `independently_deleted=false` 默认值；已有 V11 回收记录仍按会话联动恢复。单独删除文档置真，恢复文档清除标志；重复 SQL 不覆盖该标志。
- V13 创建原型聚合、来源、修订、任务扩展、确认和控制命令六表，扩展通用任务种类为 `PROTOTYPE`，补充同文档/项目/聚合的复合外键。重复执行补缺表、索引和命名约束，不重写快照、当前指针、任务结果或人工确认；同名表异常字段仍需另行核查。
- V13 的部分唯一索引限制同项目最多一个 `QUEUED` / `RUNNING` 工作空间任务。若历史数据已有多个活动任务，升级会失败而不擅自终结任何任务；应先核对这些执行的实际状态。跨旧 PRD 任务表的互斥仍由应用的统一项目根锁检查。H2 快速测试使用等价生成列约束，正式 PostgreSQL 表不增加该辅助列。
- 删除是可恢复的业务状态，不执行物理删表或删行。恢复保留原模板 ID、当前草稿、全部发布修订以及既有 PRD 引用。
- 已发布 V9–V12 的内容保持稳定；后续结构变更追加迁移，不能修改既有迁移以绕过校验。

## 验证范围

自动测试在隔离数据库 / 随机 PostgreSQL schema 中验证迁移、重复执行及原有内容保留；不代表开发或生产业务 schema 已迁移。实际升级应核对目标 schema 的 Flyway 成功历史、模板及回收字段、原型六表和活动任务唯一索引，再验证对应目录读取。服务启动成功一项不能代替迁移状态核对。

对应功能与验证记录见 [模板删除与恢复](../../docs/unified-workspace-design.md#模板删除与恢复2026-10-28) 和 [原型 P3 业务持久化](../../docs/prototype-persistence-implementation.md)。
