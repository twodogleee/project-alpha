/** 工作空间页面共享加载与离开保护，不缓存业务真值。@author Andersen */
import { ref, onBeforeUnmount } from "vue";
import { onBeforeRouteLeave, onBeforeRouteUpdate } from "vue-router";
import { confirmAction } from "@/lib/feedback.js";
/** 可取消的最新读取和串行命令；旧页面响应不会回写新页面。 */
export function useWorkspacePage() {
  const busy = ref(false),
    loading = ref(false),
    error = ref(""),
    errorCode = ref("");
  let controller,
    disposed = false,
    generation = 0;
  /** 读取时取消上一轮请求；提交命令不会被当作可撤销网络操作。 */
  async function read(load, apply) {
    controller?.abort();
    controller = new AbortController();
    const signal = controller.signal,
      current = ++generation;
    loading.value = true;
    error.value = "";
    errorCode.value = "";
    try {
      const result = await load(signal);
      if (!disposed && current === generation) apply(result);
    } catch (e) {
      if (!disposed && current === generation && !signal.aborted) {
        error.value = e.message;
        errorCode.value = e.code || "";
      }
    } finally {
      if (!disposed && current === generation) loading.value = false;
    }
  }
  /** 返回成功结果或 undefined；失败留在页面，保留输入。 */
  async function act(work) {
    if (busy.value) return;
    busy.value = true;
    error.value = "";
    errorCode.value = "";
    try {
      return await work();
    } catch (e) {
      if (!disposed) {
        error.value = e.message;
        errorCode.value = e.code || "";
      }
    } finally {
      if (!disposed) busy.value = false;
    }
  }
  onBeforeUnmount(() => {
    disposed = true;
    generation++;
    controller?.abort();
  });
  return { busy, loading, error, errorCode, read, act };
}
/** 弹窗取消、关闭按钮和 ESC 复用未保存保护；成功提交由页面直接关闭。 */
export function useDialogClose(busy, changed, setOpen) {
  /** 只有明确放弃本地输入才关闭，提交期间不关闭。 */
  return async function close(value) {
    if (value) {
      setOpen(true);
      return;
    }
    if (busy.value) return;
    if (
      changed() &&
      !(await confirmAction({
        title: "放弃未保存输入",
        description: "关闭后本次表单输入会被放弃。",
        action: "放弃输入",
      }))
    )
      return;
    setOpen(false);
  };
}
/** 未保存编辑需明确放弃；持久化模型任务由服务器继续，不作为离开阻塞。 */
export function useWorkspaceLeave(dirty, busy, discard = () => {}) {
  /** 路由与原生关闭共享同一编辑判断。 */
  async function leave() {
    if (busy.value) return false;
    if (!dirty.value) return true;
    const accepted = await confirmAction({
      title: "有未保存内容",
      description:
        "离开会放弃当前输入。已提交的会话消息和模型任务仍保存在服务器。",
      action: "放弃并离开",
    });
    if (accepted) discard();
    return accepted;
  }
  /** 浏览器关闭时保护本地编辑。 */
  function beforeUnload(event) {
    if (dirty.value || busy.value) {
      event.preventDefault();
      event.returnValue = "";
    }
  }
  onBeforeRouteLeave(leave);
  onBeforeRouteUpdate(leave);
  window.addEventListener("beforeunload", beforeUnload);
  onBeforeUnmount(() =>
    window.removeEventListener("beforeunload", beforeUnload),
  );
}
