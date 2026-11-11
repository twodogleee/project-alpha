/** 共享应用外壳向业务页面提供公开目录和刷新动作。@author Andersen */
import { inject } from "vue";

/** 注入键限定在当前应用外壳内，不在模块级缓存业务数据。 */
export const workspaceKey = Symbol("alpha-workspace");

/** 页面复用外壳的公开运行信息和项目列表。 */
export function useWorkspace() {
  return inject(workspaceKey);
}
