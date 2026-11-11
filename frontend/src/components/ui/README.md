# shadcn-vue 基础组件

这些源码通过官方 `shadcn-vue@2.8.2` CLI 的 `new-york` 样式、JavaScript 模式生成，交互原语来自 Reka UI。项目保留组件源码，业务页面组合使用；不在基础组件中调用业务 API 或保存密钥。

官方来源：<https://github.com/unovue/shadcn-vue>，MIT 许可见本目录 `LICENSE`。业务源码作者标记与第三方版权分别维护。

当前引入 button、input、textarea、badge、checkbox、sheet、dialog、tabs、table、sonner。关闭按钮的辅助文字改为中文。主题使用 `src/styles/theme.css` 中的 CSS 变量。

`DialogContent` 增加 `overlayClass`，仅用于控制对应遮罩的样式，不转发给 Reka UI 内容原语。普通弹窗使用 z-50；全局 `ConfirmDialog` 的遮罩和内容分别使用 z-60、z-61，使表单关闭保护始终位于原弹窗上方，不依赖 Portal 的挂载顺序。新增业务弹窗应沿用普通层级。

在 `frontend/` 目录添加新组件：

```bash
npx shadcn-vue@2.8.2 add <组件名>
```

先检查本地修改，生成或升级后审阅差异；不要使用 overwrite 批量覆盖已定制组件。`components.json` 和 `jsconfig.json` 维护样式及路径约定。
