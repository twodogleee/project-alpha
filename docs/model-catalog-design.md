# 系统设置：接入平台与模型目录

## 正式需求

用户要求平台和模型列表由数据库管理，支持新增、编辑、删除，并选择当前启用模型。继续按单用户工作台规划，模型 API Key 单向提交、后端加密保存。

- 系统设置包含“接入平台”和“模型配置”两个子项；前端列表完全读取数据库。
- 平台维护名称、协议、API 基础地址及可选模型列表地址。协议是已实现的百炼原生或 OpenAI 兼容适配能力，不代表一个硬编码的平台名单。
- 模型维护显示名称、关联平台、API 模型标识及独立 API Key。允许先保存尚未配置密钥的模型，启用和连接测试时必须有可解密密钥。
- 当前运行模式和唯一启用模型由数据库独立维护；保存模型不会自动启用，启用不会自动调用平台，下一次生成生效。
- 保留同一模型同一平台的密钥，移动平台需要新密钥或明确清除；已有密钥不回显。
- 有模型引用的平台不能删除；当前真实模式正在启用的模型不能删除或清空密钥，先切换模型或切到 demo。demo 下删除已选模型同时清空选择。
- 平台协议或地址变化前必须清除其所有模型密钥，防止转交旧凭证。数据库外键限制平台/模型的悬空引用。
- 所有修改使用目录 revision，检测同一用户多窗口冲突；平台、模型和选择变更在同一事务边界串行校验。
- 显式测试当前模型表单，不保存、不创建项目；失败保留编辑，隐藏上游错误。关闭和重新载入保护未保存内容。

## 数据与迁移设计

`model_platforms` 保存平台名称、协议、地址、可空 `model_catalog_url` 及更新时间；`model_profiles` 保存显示名称、平台外键、模型标识、密钥密文及更新时间；`model_runtime_selection` 固定一行，维护 revision、mode 和 active_model_id。

V6 只补充模型列表地址字段，不修改已有平台、模型、密文和运行选择。旧平台地址为空，用户明确配置后才可获取。MyBatis Plus 使用 ALWAYS 更新策略，确保空值实际清空数据库字段；保存该字段与其他目录变更共用 revision。

Flyway V3 创建目录并一次性提供百炼、OpenAI、DeepSeek 三个平台及无密钥的 qwen-plus 模型作为可编辑、可删除的数据库初始数据。之后不会因删除而自动重建。

V2 中已有单份配置复制为独立的“已迁移平台”及“原模型配置”，保留原地址、模型和 AES-GCM 密文，沿用原加密主密钥；不修改旧表数据。原 ai 配置有已保存密文时保持启用。原密钥只来自环境、数据库无密文时切到 demo 并保留模型供用户补充 API Key，避免声称不存在的凭证已迁移。

升级后运行时只读取目录与选择表。旧 `ALPHA_AI_*` / `DASHSCOPE_API_KEY` 不再作为动态目录回退，数据库连接及加密主密钥仍来自后端私有配置。旧单份配置写接口与静态前端预设替换为目录接口。

事务写入先锁定 selection 固定行，校验目录 revision，再执行关系校验和变更，最后递增 revision。公开目录与运行快照使用可重复读事务，避免读到来自不同保存版本的平台、模型和启用选择；模型调用在事务外执行。

## 接口与页面

前缀 `/api/alpha/model-config`：GET 获取公开目录；POST/PUT/DELETE `/platforms`、`/platforms/{id}`；POST/PUT/DELETE `/models`、`/models/{id}`；PUT `/runtime` 保存当前模式和模型；POST `/test?modelId=...` 测试当前模型表单，新增时不传 modelId。

保存和删除均携带 expectedRevision（删除为查询参数）。响应只返回公开目录，无密钥明文、片段、密文或主密钥路径。平台名称全局唯一，模型显示名称在所属平台内唯一。

系统设置使用独立页面 `/settings/platforms`、`/settings/models`；新增/编辑通过右侧抽屉操作，各自维护编辑副本。离开需求或设置页面前保护未保存内容。模型页明确区分“保存模型配置”和“应用运行设置”，当前生效状态单独展示。现行布局见 [界面重设计](interface-redesign-design.md)。

## 验证与范围

验证真实 PostgreSQL 的 V2 到 V3 数据迁移、密文恢复、外键及并发修改；验证 CRUD、启用、删除限制、目标变更密钥规则、损坏密钥恢复和运行中的快照。模型适配使用本机 HTTP 模拟平台，不调用真实付费账号。

不包含多人使用、自动模型目录同步、自动模型路由、自动回退、认证或线上部署。

## 公开字段与请求

目录响应含 `revision`、`runtime {mode, activeModelId}`、`platforms [{id,name,protocol,baseUrl,modelCatalogUrl,updatedAt}]`、`models [{id,platformId,name,model,apiKeyConfigured,updatedAt}]`。密钥状态仅代表存在密文，真正可用性由启用时解密校验和显式连接测试确认。

- 平台保存：`expectedRevision, name, protocol, baseUrl, modelCatalogUrl`；目录地址为空或 null 时清除。
- 模型保存/测试：`expectedRevision, platformId, name, model, apiKey, clearApiKey`；apiKey 仅接受输入，JSON 响应不会序列化。
- 运行选择：`expectedRevision, mode, activeModelId`；demo 下允许预选模型或为空，ai 必须选择有可解密密钥的模型。
- 名称最长 120 字符，地址最长 500 字符，模型 API 标识最长 120 字符，API Key 最长 4096 字符。目录 revision 初始为 1，每次成功写入递增；测试不递增。

错误使用统一响应：400 INVALID_MODEL_CONFIG / INVALID_INPUT / INVALID_JSON，404 PLATFORM_NOT_FOUND / MODEL_NOT_FOUND，409 MODEL_CONFIG_CONFLICT / PLATFORM_IN_USE / MODEL_IN_USE，503 MODEL_SECRET_UNAVAILABLE，502 MODEL_CONNECTION_FAILED / MODEL_CATALOG_FETCH_FAILED。失败不返回完整上游正文或凭证。

## 显式获取上游模型列表（2026-10-13）

`POST /api/alpha/model-config/platforms/{id}/catalog` 请求为 `{expectedRevision, modelId?, apiKey?, clearApiKey}`，返回 `{revision, models:["model-id"]}`。modelId 为当前编辑模型，不指定时只能使用本次输入密钥或匿名请求。新输入密钥优先；原密钥只在同模型、同平台且未选择清除时解密使用，不借用启用模型或其他模型的凭证。请求只读校验目录版本，不需要模型名称或标识，不保存输入，不递增版本。

应用服务先在只读可重复读事务中校验版本、读取平台 URL 和解密凭证，然后在事务外发起 GET。地址必须与平台 baseUrl 同源，远程只允许 HTTPS，本机回环允许 HTTP；不接受 URL 账号、查询参数、片段。不跟随 3xx，不传播密钥到其他地址。响应头与正文共用 15 秒整体限制，成功正文限制 1 MiB，错误正文限制 20000 字节，data 原始数组限制 1000 条；超过限制明确失败。只接受 `data[].id` 字符串，对合法 id 排序去重并过滤疑似凭证，其余上游字段不返回页面。

模型编辑抽屉显示已保存的列表地址，提供获取/刷新按钮与选择框，并保留手工模型标识输入。获取失败清空旧列表、保留表单和错误原因；空数组明确提示没有可用模型。更换平台、输入密钥或清除策略时废弃旧名单。获取、测试、保存有各自明确的按钮状态；请求期间防止重复提交、切换或关闭，卸载取消前端请求。服务端有界请求仍以固定快照运行，前端取消不承诺中断平台请求。

获取列表不推理、不批量导入、不启用任何模型；手动选择只写入当前编辑副本，保存仍检查最新目录 revision，启用仍单独应用。平台修改列表地址无需清除密钥，前提是同源；原有更换协议或 baseUrl 的密钥隔离规则继续生效。
