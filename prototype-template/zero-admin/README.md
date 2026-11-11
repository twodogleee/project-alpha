# Zero 管理端原型模板

`zero-admin@2` 是 Alpha 原型能力采用的独立管理端工程模板。固定采用 Zero 的表格、查询、CRUD、详情、反馈组件及主题，通过原型专用入口替换认证、菜单后端、用户账号及浏览器持久化接线。

本目录的人员页面为**本地模板联调样板，未调用大模型**。全部数据由本次预览内存维护，刷新恢复初始状态，不代表目标产品业务需求已经确认。

## 运行与验证

从仓库根目录执行：

```bash
npm --prefix prototype-template/zero-admin ci --ignore-scripts
npm --prefix prototype-template/zero-admin test
npm --prefix prototype-template/zero-admin run build
```

`dist/` 使用相对静态资源路径，可挂载在受限预览会话目录。业务路由为 `#/personnel` 和 `#/personnel/person-001`。上述宿主构建仅用于维护者可信模板的开发验证；生成代码必须通过 `prototype-worker` 隔离构建，不以 npm 命令替代隔离。

## 来源与范围

- `ZERO_BASELINE` 记录上游版本与已核验的提交号；`ZERO_OVERRIDES.yml` 声明裁剪范围和替换策略。
- `zero-source.json` 列出 31 个实际采用或摘取文件及摘要，改名适配的 CSS 变量使用 `--project-alpha-*`，清单保留适配前后的内容摘要。
- `src/styles/zero-theme.css` 是上游 `src/App.vue` 的完整 style 提取并适配项目 CSS 变量前缀，非整个 App 复制。
- `src/constants/storage.js` 仅提供中立键兼容 Store；入口不安装持久化插件，不读取 Zero Token、账号或环境配置。
- 上游声明的后端与认证接口版本要求对应的接线在本模板禁用。此采用范围不表示整套 Zero 前后端兼容，也不把 Alpha 工作台加入 Zero 基线。

平台维护 `template.json`、构建与依赖锁、路由入口、外壳和公共组件。生成目录及组件调用边界见 [组件契约](component-contracts.md)。Worker 的工具链记录固定容器镜像、完整模板摘要和实际验证产物；后续模板变更必须形成新 revision，不能静默改变已有原型。
