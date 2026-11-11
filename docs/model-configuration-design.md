# 动态模型配置需求与设计

> 历史阶段记录：当前平台与模型目录已升级，现行需求与接口见 [模型目录设计](model-catalog-design.md)，验证见 [模型目录验证记录](model-catalog-validation.md)。

## 正式需求

用户要求增加可动态修改的模型配置模块，并明确允许在页面选择接入平台与模型，由后端加密持久化。该要求是本模块密钥接收与存储方式的明确调整：用户可以单向提交密钥，服务端不得将已有密钥返回页面、写入日志或版本控制。产品继续按单用户工作台规划。

1. 在工作台左侧“系统设置”一级菜单下提供“模型配置”子项，支持 demo 与真实模型模式；配置属于应用级设置，不归属于某个需求项目。
2. 支持百炼 DashScope、OpenAI、DeepSeek 和自定义 OpenAI 兼容接入。平台预设便于填写地址，模型名称可选填预设或自行输入，以平台账号实际支持的模型为准。
3. 可编辑模型名称、接入地址与 API Key；保存后后续生成请求立即使用新配置，无需重启后端。
4. 配置持久化到 PostgreSQL，API Key 使用 AES-256-GCM 加密；页面仅显示是否已配置与密钥来源。
5. 密钥留空表示保留同平台、同接入地址的已有密钥；更换平台/地址时需重新提供密钥，不能自动将旧密钥发给新平台。可明确清除保存的密钥。
6. 提供显式连接测试，测试当前表单，不自动保存、不生成业务项目；真实模式会调用平台，demo 不调用外部模型。
7. 配置使用 revision 检测多窗口保存冲突。进行中的生成固定使用开始时的配置，旧项目来源不因配置切换改变。
8. 失败不静默退回 demo，保存和测试错误不泄漏密钥及上游异常原文。

## 设计选择

- 单份应用级配置，平台预设不表示账号已开通模型；不增加用户、团队或租户模型。
- `model_configuration` 存储配置元数据及密钥密文；Flyway V2 建表，不修改已执行 V1。
- 首次未保存时使用后端环境配置；数据库保存配置优先。环境 DASHSCOPE_API_KEY 只用于原百炼接入地址，不隐式转交其他平台或新地址。
- 每次打开配置与开始生成均由 MyBatis Plus 读取数据库，不依赖后端启动时固定的模型客户端；环境默认值仅作为尚无数据库记录时的初始值。数据库连接与加密主密钥属于后端启动配置，不存入这张模型业务配置表。
- 加密主密钥来自后端 ALPHA_MODEL_CONFIG_ENCRYPTION_KEY（32 字节 Base64）或本地私有 data/model-config.key；首次需要加密时创建权限 0600 的本地文件。解密时缺失/损坏不能自动创建替代密钥。备份数据库时需一并保管主密钥。
- 每次生成获取一次不可变运行上下文，Graph 的分析与渲染共用该上下文。模型客户端通过 Spring AI 工厂按平台创建；不对模型名称做账户可用性承诺。
- GET /api/alpha/model-config 返回公开配置；PUT 保存；POST /test 测试提交的表单。Controller 只校验/响应，Service 负责密钥策略与业务规则，Mapper 负责存储。
- 页面使用独立配置弹窗，避免离开需求编辑器；关闭未保存配置时保护编辑。测试与保存状态分别显示，变更表单后旧测试结果失效。

## 接口契约

`GET /api/alpha/model-config` 的公开数据包括 `revision`、`mode`、`platform`、`model`、`baseUrl`、`apiKeyConfigured`、`keySource`、`source`、`updatedAt`。首次环境默认值的 revision 为 0；数据库保存版本从 1 递增。`keySource` 为 `saved|environment|none`，`source` 为 `database|environment`。已配置只表示存在凭证来源，不表示已经连接成功；响应不包含密钥、密钥片段、密文或主密钥路径。

保存及测试的表单结构相同：

```json
{
  "expectedRevision": 0,
  "mode": "ai",
  "platform": "dashscope",
  "model": "qwen-plus",
  "baseUrl": "https://dashscope.aliyuncs.com",
  "apiKey": "用户单向提交的新密钥，留空可保留同目标凭证",
  "clearApiKey": false
}
```

`mode` 为 `demo|ai`；平台为 `dashscope|openai|deepseek|custom`。模型名称最大 120 字符，允许字母、数字、点、下划线、冒号、斜杠和短横线；地址最大 500 字符，禁止账号信息、查询参数和片段，远程仅 HTTPS，本机回环允许 HTTP。百炼填原生服务根地址，兼容平台填 API 基础路径（通常包含 `/v1`），不填写完整 `/chat/completions` 端点。API Key 最大 4096 字符，不能为空白混合或包含控制字符。

`PUT` 返回保存后的公开配置；不自动调用平台。`POST /test` 返回 `connected`、`mode`、`model`、`message`；真实模式会验证结构化需求结果，demo 返回 connected=false 并明确说明没有外部调用。二者均校验 expectedRevision；连接测试不落盘密钥或主密钥文件。

| 状态 | code | 含义 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` / `INVALID_JSON` | 请求字段或 JSON 格式错误 |
| 400 | `INVALID_MODEL_CONFIG` | 模式、平台、模型、地址或密钥策略错误 |
| 409 | `MODEL_CONFIG_CONFLICT` | 当前配置已有新版本，不能覆盖 |
| 503 | `MODEL_SECRET_UNAVAILABLE` | 主密钥或密文无法安全读写，需恢复原主密钥或重新提供密钥 |
| 502 | `MODEL_CONNECTION_FAILED` | 连接测试失败，错误正文已隐藏 |

实际生成沿用工作流错误契约 `AGENT_EXECUTION_FAILED`，不泄漏底层失败正文。开始时固定配置；Graph State 只保存运行标识与公开来源，SDK 客户端保留在私有请求注册表，执行结束移除。当前不支持跨重启恢复进行中的模型调用。

## 明确排除与待确认事项

不包含多人使用、多配置路由、自动选择模型、模型市场同步、自动重试、自动回退、工具调用或付费模型效果验收。平台地址与模型名称由用户确认；本次以本地模拟平台验证适配协议，不发起用户真实付费模型请求。
