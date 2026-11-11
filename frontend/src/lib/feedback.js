/** 全局确认契约；默认保留输入，所有提示均按纯文本展示。@author Andersen */
import { reactive } from "vue";

/** 单份确认状态由应用根节点渲染，不保存业务数据或凭证。 */
export const confirmation = reactive({
  open: false,
  title: "",
  description: "",
  action: "",
  destructive: false,
});
/** 当前确认的完成函数，重入时拒绝后来的动作。 */
let resolveConfirmation;

/** 返回明确确认结果；关闭、ESC 和取消都视为保留原状态。 */
export function confirmAction({
  title,
  description,
  action = "确认",
  destructive = false,
}) {
  if (confirmation.open) return Promise.resolve(false);
  Object.assign(confirmation, {
    open: true,
    title,
    description,
    action,
    destructive,
  });
  return new Promise((resolve) => {
    resolveConfirmation = resolve;
  });
}

/** 结束确认并清理回调，避免旧确认影响后续导航。 */
export function settleConfirmation(accepted) {
  confirmation.open = false;
  const resolve = resolveConfirmation;
  resolveConfirmation = null;
  resolve?.(accepted);
}
