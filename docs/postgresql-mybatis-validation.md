# PostgreSQL / MyBatis Plus 验证记录

验证日期：2026-10-12。连接用户提供的本地 PostgreSQL，服务版本为 16.4。所有业务验收使用明确标记的 demo 模板，不调用付费模型。

## 自动化结果

| 验证 | 实际结果 |
| --- | --- |
| `./gradlew :backend:test --console=plain` | 25 个测试，0 失败、0 错误、0 跳过 |
| `./gradlew :backend:postgresTest --console=plain` | 5 个真实 PostgreSQL 测试，0 失败、0 错误、0 跳过 |
| assembleApp 内的前端测试 | 5 个通过 |
| `npm --prefix frontend run build` | Vite 生产构建通过 |
| `./gradlew --no-daemon assembleApp --console=plain` | 后端检查、前端测试、生产 JAR 打包通过 |

本次工具执行环境的默认 PATH 没有 npm，初次整体验证在启动 npm 时失败。加入本机已有的 Node.js v24.15.0 路径，并使用新的单次 Gradle 进程后通过；没有修改项目构建逻辑来绑定本机路径。整体验证复用已通过的后端测试和前端构建，另外显式重跑了 Vite 构建。

真实 PostgreSQL 测试在随机 `alpha_it_<UUID>` schema 中执行正式 Flyway V1 与真实 Mapper，验证：

1. JSONB 存储为对象，中文、引号与换行可完整往返；updated_at 为 timestamp with time zone；迁移版本 1 已成功。
2. 确认与澄清回答保存到 v2，v1 保持不变。
3. 两个并发事务更新相同 revision，仅一个成功，历史只追加一次。
4. 版本唯一约束冲突时，当前文档更新回滚。
5. 创建超过 100 个项目后，MyBatis Plus PostgreSQL 分页插件限制摘要数量为 100。

测试完成已清理随机测试 schema，查询确认无遗留 alpha_it_ schema；不读写 Zero 的业务表。

## 实际 JAR 与开发 schema 验证

在工程根目录用已构建 JAR 启动，临时端口 18081。未通过命令行传入数据库密码，验证实际应用自动读取根目录私有 `.env`。

1. 成功连接 localhost:5432/postgres，Flyway 创建 当时的开发 schema、flyway_schema_history、project_documents、project_versions，执行 V1。
2. 健康检查 UP，初始项目列表为空，JAR 的 static/index.html 正常返回。
3. 通过实际 API 创建临时 demo 项目 v1，确认需求、填写回答并保存 v2；历史 v1 与原响应完全一致。
4. 用旧 expectedRevision 再保存，返回 409 VERSION_CONFLICT。
5. 正常停止并重新启动同一 JAR。Flyway 校验通过，识别 schema 已是 v1，无需重新迁移。
6. 重启后读取当前 v2、历史 v1 和 v2，完整 payload 与重启前逐项相等。
7. 停止验收进程，仅按本次项目 UUID 和名称校验后清理临时项目的 2 条版本、1 条当前记录。开发 schema 保留，项目数回到 0。

本次没有重新执行浏览器交互验收；前端未改动，初版交互记录保留在 [草稿验收记录](draft-review-validation.md)。本次实际验证 JAR 静态页面响应与 API，不据此宣称完成了新一轮浏览器验收。

## 打包与私有配置检查

- JAR 包含 MyBatis Plus 3.5.7、dynamic-datasource 4.3.1、PostgreSQL JDBC 42.7.8、Flyway core/PostgreSQL 11.7.2、Mapper XML、V1 迁移、前端静态资源。
- JAR 不包含 H2 驱动，H2 仅为测试运行时依赖。
- 根目录私有 `.env` 被 Git 忽略，权限 0600；不包含在 JAR。
- 凭证检查排除文档中的工作区用户名路径后，没有发现数据库密码作为凭证写入可跟踪源码、示例配置或应用打包资源。
- 未发现旧 data/alpha.mv.db，本次没有执行旧 H2 数据导入。

## 未验证与限制

没有执行线上部署、真实模型调用、多实例负载测试、认证授权、用户/团队隔离、Graph 检查点恢复或旧数据迁移。真实 PostgreSQL 并发事务已验证，用于防止多窗口保存误覆盖。用户已明确多人使用不纳入需求规划，多人使用相关未验证项不作为后续功能待办。

构建有 Gradle 9 兼容性弃用提示；启动有 Spring/Flyway 转换器提前初始化的 BeanPostProcessor 提示。实际迁移、事务、健康检查及重启读取均通过，尚未专门处理上述依赖提示。
