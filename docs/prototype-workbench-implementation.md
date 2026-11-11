# P5 原型工作台实现

日期：2026-10-29。作者：Andersen。

本轮将 [P4 模型生成链路](prototype-generation-implementation.md) 接入统一会话工作台。实际模型规划、源码生成、隔离构建和修订入库仍由 P4 执行；P5 增加操作界面、任务接管、按修订申请的预览及来源和历史核对。人工确认、恢复历史为新修订、源码/静态包导出属于 P6。

## 工作台行为

- 同一会话通过 PRD / 原型切换视图，URL 的 `view=prototype` 保留刷新入口。两种讨论输入分别保留在页面内存中，不互相替换或写入浏览器存储。切换视图前等待 PRD 自动保存；结果不明的原型提交需先核对原请求。
- 生成弹窗显示模板、草稿/定稿来源、模块及页面。优先采用当前有效 PRD 定稿，否则采用已保存草稿。全部范围包含不支持的类型时阻止提交，用户可明确选择受支持子集；单次最多 10 页，不静默截断。
- 首次生成使用 `GENERATE`；已有原型的“按最新 PRD 更新”使用 `SYNC_PRD`；右侧会话的页面级或整个原型修改使用 `EDIT`，沿用当前修订的冻结来源。提交前重新获取业务锁，服务端仍负责最终资格判断。
- 展示任务实际阶段、模式和结果，终态停止轮询。刷新只接管已存在任务，不重新调用模型。网络结果不明时复用完整原请求，明确失败后由用户创建新任务。已有修订在新任务失败或产生冲突候选时保留。
- 预览路由按当前修订清单映射到 `{moduleId,pageId}`；下拉选择页面可以定位到固定测试路径。页面名称、路由顺序和 DOM 文本都不充当业务身份。共享文件影响仍由 P4 的整体验证处理。
- 当前修订与历史回看分开，历史只读；回看不会更改当前指针。冲突候选需核对来源与验证信息，只有来源仍一致时才能显式采纳，服务端再次检查四种锁。
- 来源展示 `CURRENT / STALE / REVIEW_REQUIRED`、冻结范围、生成模式/模型、验证项、能力缺口和完整冻结 PRD。原型编辑不会自动改写正式 PRD；业务需求变化应先保存 PRD，再同步原型。
- 归档/取消保留已冻结修订的读取和预览，禁止业务写入。回收文档不能申请预览，既有预览的后续请求也会失效。任务持久化仍不等于 Graph 检查点恢复。

## 接口与公开数据

沿用 `/api/alpha/workspace` 和 `{code,message,data}`。新增：

| 接口 | 行为 |
| --- | --- |
| `GET prototype-templates` | 固定模板身份、版本、页面类型、支持动作、页数上限及当前执行器接收资格。GET 不探测付费模型，不返回路径或密钥。 |
| `POST versions/{v}/prototype/revisions/{r}/preview` | 可选 `{parentOrigin}` 必须在后端白名单中，返回 `sessionId/revisionId/frameUrl/windowUrl/expiresAt`。先检查归属及产物完整性。 |
| `DELETE versions/{v}/prototype/preview/{sessionId}` | 撤销该文档所属临时预览，释放内存，不删除封存产物。 |

`Detail.runs` 保持最近 20 条，补充 `runId/status/mode/model/errorMessage/errorCode/createdAt`，可独立恢复任务轮询。原有阶段、修复次数、应用状态和来源/结果引用保持不变；执行令牌、内部上下文和诊断原文不进入公开响应。

## 预览边界

`PrototypePreviewService` 在首次申请时启动 JDK HttpServer，固定监听 `127.0.0.1` 的独立端口，不承载业务 API。封存静态文件重新核对摘要后读入有界内存快照，后续资源请求不读取可变磁盘文件。默认单会话 15 分钟、最多 8 个会话、总快照 100 MiB；过期、主动撤销或应用停止释放资源。

页面有两层 iframe：

1. Alpha 内嵌平台拥有的可信外壳，位于独立预览 origin，外层权限为 `allow-scripts allow-same-origin`。它不包含模型代码，只负责固定 CSP、加载内部页面和转发受限消息。
2. 生成页面位于外壳内部，权限仅 `allow-scripts`，始终为 opaque origin。它不能访问 Alpha、父外壳 DOM、Cookie、localStorage 或 sessionStorage。外壳 `frame-src` 限制生成页面的站点导航；生成层 CSP 限制网络、动态代码、嵌套框架、表单和对象。

实际浏览器探针确认：外壳也为 opaque origin 时，生成页面的精确 `frame-ancestors` 会拒绝加载。外层保留自身独立 origin 是受信外壳的必要权限，不能用于放宽生成层。Alpha 桥接校验外壳窗口、预览精确 origin、会话、修订和字段白名单；外壳再校验内部窗口及 `origin=null`。协议只允许 `READY / NAVIGATED / SELECT_PAGE / ERROR`，不传递执行代码或业务写命令。

生成层桥接由独立的受控 `bridge.js` 提供，`script-src` 仅允许预览自身来源，不向生成 DOM 注入可读取的 nonce。固定 nonce 只存在于跨源隔离的可信外壳中，避免生成脚本复用 nonce 绕过外域脚本限制。桥接资源同样逐请求检查会话与业务资格。

独立窗口同样经过平台外壳，不直接顶层运行生成页面。每次静态请求复核 TTL、会话和父文档读取资格；生成 HTML 只接受 iframe 加载。服务拒绝非 GET/HEAD、错误 Host、查询参数、歧义路径和不存在文件。已送达浏览器内存的字节不能远程收回，但后续请求必须通过资格检查。

配置前缀为 `alpha.prototype.preview`：`allowed-origins`、`port`、`ttl`、`max-sessions`、`max-bytes`。默认允许 `http://127.0.0.1:18080` 与 Vite 的 `http://127.0.0.1:5178`。更换工作台端口需显式调整白名单；当前是单机能力，远程发布和生产托管不在本轮范围内。

## 验证方式与证据边界

后端测试覆盖目录与任务公开契约、修订归属、文档回收、文件快照及真实预览 HTTP 生命周期。前端纯状态测试覆盖范围、四锁、来源、路由映射、消息校验、幂等命令与迟到轮询；构建验证 Vue 模板和依赖完整性。

真实浏览器验收使用独立 H2、18081 后端、5179 Vite 和临时产物目录。唯一合成替身是任务执行调度器：沿用一份已通过真实 Worker 验证的 `synthetic / test-fixture` 静态样例，经过真实领取、心跳、完成、来源冻结和修订事务。编辑验收验证页面目标、来源沿用及修订入库，静态样例保持相同，不能据此声称本轮模型生成或页面代码修改已经验收；真实模型证据见 P4。

```bash
ALPHA_PROTOTYPE_BROWSER_TEST=true ./gradlew :backend:prototypeBrowserTest
VITE_DEV_PORT=5179 VITE_DEV_API_PROXY_TARGET=http://127.0.0.1:18081 npm --prefix frontend run dev
node prototype-worker/test/workbench-browser.mjs
```

默认单元测试排除 `prototype-browser`，显式验收需本机 Chrome（也可通过 `PLAYWRIGHT_CHROMIUM_EXECUTABLE` 指定）、既有合成 Worker 内容对象及空闲端口。浏览器脚本结束后写停止标志，让隔离 Spring 服务正常退出。报告和截图写入已忽略的 `backend/build/reports/prototype-browser*`，不写业务数据库、模型配置或 P2 样板指针。

P5 不增加数据库迁移。现有环境仍需 P3 的业务 schema 和 P4 的固定工具链、模型配置；本轮代码验证与隔离验收不代表已迁移现有业务库或重启现有服务。

## 本轮实际验证结果

| 检查 | 实际结果 |
| --- | --- |
| `./gradlew :backend:test` | 268 项通过，0 失败、0 跳过；含 14 项真实预览 HTTP 检查及文件快照、父对象状态和公开接口回归。 |
| `npm --prefix frontend test` | 70 项通过，含新增 7 项原型状态、命令、范围、桥接和轮询测试。 |
| `npm --prefix frontend run build` | 成功；Vite 仍提示主 bundle 超过默认 500 KiB 阈值，不影响本次构建。 |
| 独立 H2 + 真实 Chrome 工作台 | 11 项检查通过，0 页面异常、0 API 失败；最终报告时间为 2026-10-29 15:22:57（Asia/Shanghai），Chrome 156.0.8078.49。 |

浏览器覆盖：两份未发送输入保留、范围空选与复合页面选择、生成进度及刷新接管、实际 iframe 模拟数据及详情共享、来源/验证/冻结 PRD、独立窗口、页面 EDIT 与父子修订、历史只读、未知响应完整命令重放、继续编辑 PRD 后 `STALE`、归档只读，以及 820×700 窗口的滚动与操作可达性。

未知响应测试让服务端先接受请求，再中断浏览器回包；重放的两次完整 payload 相同，最终只有一个任务和一个新修订。安全探针确认生成层 nonce 数为 0，访问父/top DOM、Cookie、本地/会话存储、包装层 nonce 均得到 `SecurityError`；请求 Alpha/其他本机 origin、外域脚本及导航均被 CSP 阻断，专用本机接收端收到 0 个请求。测试全程未访问互联网或真实业务数据。

这些测试记录不会成为现有业务项目中的修订；验收服务结束后其 H2 和短时预览 URL 失效。
