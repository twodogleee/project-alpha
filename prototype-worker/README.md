# 本机原型 Worker

工作台的生成任务与可信样板共用本机 Node、模板锁定的 Vite 和 Playwright 驱动的 Chromium。先安装项目锁定的 npm 依赖，再运行 `prepare` 核验已有浏览器。`doctor` 检查模板、执行脚本和运行时版本指纹。可通过 `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` 显式指定浏览器，Worker 不安装浏览器或拉取镜像。

从仓库根执行：

```sh
node prototype-worker/src/cli.mjs prepare
node prototype-worker/src/cli.mjs doctor
node prototype-worker/src/cli.mjs build-generated < prototype-worker/test/fixtures/generated-request.json
npm --prefix prototype-worker test
# 额外运行本机 Chromium 正例、eval/存储负例及旧样板回归。
node prototype-worker/test/generated-isolation.mjs
```

`generated-request.json` 是明确的 `synthetic / test-fixture` 人工测试材料，不是模型验收结果。真实模型任务由 Java 调用 CLI。

## P4 协议

只接受一个命令参数 `build-generated`；stdin 是单一 JSON 对象：

```json
{
  "protocolVersion": 1,
  "mode": "live",
  "generation": "model",
  "files": [{"path": "src/features/generated/Items.vue", "content": "..."}],
  "pages": [{"moduleId": "items", "pageId": "list", "title": "项目管理", "path": "/items", "component": "src/features/generated/Items.vue"}],
  "cases": [{"name": "列表内容", "path": "/items", "steps": [{"action": "assertText", "testId": "items", "text": "项目管理"}]}]
}
```

- `files` 是两个 generated 根目录的**完整文件集合**，先删除模板中的旧样板再填入；不接受补丁、摘要、大小、任意入口、配置、命令或宿主路径。最多 100 文件、每文件 256 KiB、总文本 5 MiB，扩展名仅 `.vue/.js/.json`。导入按模板白名单和文件存在性检查，SFC 不支持 TS、外部源码、外部样式、`v-html` 或嵌入浏览上下文。
- `pages` 包含 1–10 个不同复合页面身份和不同路由；P4 当前每个选定页面有独立路由，FORM 可以引用共享表单组件。组件必须位于本次文件集合内。至少一个静态菜单入口；动态路径（例如 `/items/:id`）必须提供 `menu:false` 与匹配的具体 `testPath`。
- `cases` 最多 20 项、每项 1–20 步、合计不超过 100 步。动作仅 `click/fill/assertVisible/assertText`，只接受 `testId`，不接受 CSS、脚本或任意 URL；文本断言可省略 testId，此时只检查主区域。用例只能从已声明 testPath 开始。补充用例不能关闭平台检查或自报通过。
- 平台静态生成路由、展示配置及验证清单；路由身份取 `moduleId/pageId` 的确定性摘要，计划重排或标题更改不改变身份，路由元数据保留复合页面身份。现有预览桥接仍仅发送路径，工作台可按固定修订清单映射页面。
- 宿主机在独立临时目录内执行固定 Vite 命令，并逐路由验证地址、可见非空业务内容、截图、无运行错误、无外部请求以及固定壳重置动作，随后解释补充用例。检查只读取 `.prototype-main` 内业务内容，侧栏和顶栏不能掩盖空页面。通过不等于需求语义完整。
- 浏览器验收与用户预览使用等价 CSP：不允许 `eval/new Function`、连接、Worker、外部帧或表单导航；`sandbox allow-scripts` 保持 opaque origin，浏览器存储不可用。脚本/样式/字体通过固定静态源加载，资源响应仅允许 opaque origin CORS。浏览器仍只允许访问任务自身静态资源。
- `mode/generation` 只能是 `live/model` 或 `synthetic/test-fixture`。来源由调用方声明，Worker 不持有模型密钥；Java 负责确保声明与实际模型调用一致。
- 产物目录由受信服务端环境 `ALPHA_PROTOTYPE_ARTIFACT_ROOT` 决定，默认仓库下 `data/prototypes`。stdin 不接受此字段。

成功 stdout 只输出 `{protocolVersion:1,status:"PASSED",artifactId,validation}`。失败 stderr 输出 `{code,message,diagnostic?}` 并退出 1，诊断有界、仅供 Java 内部修复。生成产物封存不更新 `current.json`，业务当前指针由数据库管理。SIGTERM/SIGINT 取消本次编译或浏览器验证并清理临时文件和执行槽，调用方应留至少 12 秒优雅取消时间，再强制终止。

Worker 只把验证通过的生成区文件放入临时工程，复用固定依赖，按白名单向子进程传递环境变量。macOS 上编译器及其子进程还受系统文件和网络访问规则约束；其他系统的编译使用本机账号权限。每次浏览器验证创建独立的临时用户资料，保留 CSP、受限页面来源、请求拦截和超时。封存清单记录实际 Chromium、Playwright、Vite、Node 版本及宿主工具链指纹。

2026-10-29 的 P4 验证中，29 项 Worker/预览回归通过；真实 Docker 下合成生成通过 5 项检查，旧样板通过 10 项检查，`eval`、`new Function`、`localStorage` 和 `sessionStorage` 四个负例均因实际浏览器 CSP/sandbox 策略失败。负例同时断言浏览器诊断，未把文件校验拒绝冒充运行时隔离证据。整个验证保留原样板指针，未调用真实模型。
