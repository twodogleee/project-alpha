# Zero 管理端原型组件契约

此目录为 `zero-admin@2` 的平台模板。人员样板是本地联调材料，未调用大模型，不构成业务 PRD 定稿。来源和精确摘要见 `zero-source.json`。

## 页面边界

- 业务文件只放 `src/features/generated/`、`src/mock/generated/`。依赖、公共组件、入口、路由清单、主题及预览桥接由平台维护。
- 通过 `@/platform/components.js` 引用白名单组件；允许 Vue、Vue Router、Element Plus 及官方图标。相对导入只指向本生成范围内的页面或数据。
- 未安装 Pinia 持久化插件；查询和表格偏好只保留本次预览内存。实体集合放在共享模拟数据模块，表单使用独立副本。
- 外部业务文本按文本插值展示，不使用 `v-html`。不使用真实网络请求、浏览器存储、OAuth 或生产认证状态。
- 原型壳不实现权限判断；角色情景只供演示，不能用来声称真实权限保护。

## 公共组件

| 组件 | 输入与事件 | 业务责任 |
| --- | --- | --- |
| `ETable` | `tableModule` 含 `queryForm/searchFields/columns/dataList/pages/callback`；callback 接收 `pageIndex/pageSize/resetQuery` | 页面拥有查询和分页，不能依赖组件改写输入；`row-key` 使用稳定实体 ID |
| `QueryPanel` | 受控 model、fields；search/reset 事件 | 下拉采用 `optionProps`，字段 attrs 可以传稳定测试标识 |
| `CrudDialog` | 新增默认弹窗；v-model、title、submit、beforeClose | submit 返回完整 Promise；校验失败返回 false；成功后由调用方关闭 |
| `CrudDrawer` | 编辑默认抽屉；v-model、title、mode、submit、beforeClose | 使用独立表单快照；beforeClose 返回 false 阻止放弃未保存修改 |
| `CrudDetail` / `CrudDetailItem` | columns、label、value、span | 详情只读，0 与 false 为有效数据；不要增加修改动作 |
| `AsyncState` | loading/error/empty，retry 事件 | 页面选择模拟情景，不伪装成真实网络调用 |
| `StatusTag` | status 或映射参数按上游组件契约传入 | 原始领域状态由业务页面转换 |

页面类型 `LIST / FORM / DETAIL` 可以组合成路由、弹窗和抽屉，不承诺一个 PRD 页面必然对应一个路由。路由来自已校验页面清单，名称变化保持稳定 ID。

## 本地样板验证入口

- `#/personnel`：26 条模拟人员，分页 20 条；查询、重置、创建、编辑、详情。
- `#/personnel/person-001`：张明的只读详情；点击“返回人员列表”。
- 查询输入 `query-name`；新增 `create-person`；字段 `person-name/person-department/person-email/person-capacity/person-status/person-notes`。
- 编辑 `row-edit-person-001`，详情 `row-detail-person-001`；保存使用 Zero 组件的“保存”按钮；校验失败显示 `form-error`。
- `scenario` 选择正常、空数据、加载和失败。数据变更只保留本次预览，刷新或 `reset-demo` 恢复种子。
- 首个角色字段、容量规则、部门字典均是样板显式配置，生成新产品时必须依据对应 PRD 替换。

## 构建与来源

`npm ci --ignore-scripts` 安装锁定依赖，`npm run build` 生成相对路径静态资源到 `dist/`。开发者可信模板的宿主构建只用于模板验证；生成代码的实际构建必须经过 Worker 的隔离执行，不能复用此开发命令绕过容器。

`npm test` 核对上游文件摘要、源码导入闭包和模拟实体的业务边界。模板更新形成新 revision，已有原型继续引用旧模板。
