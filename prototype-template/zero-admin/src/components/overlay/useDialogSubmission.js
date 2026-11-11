/**
 * 弹层提交互斥：同步加锁覆盖校验和请求，关闭后直到下次打开才允许再次提交。
 * @author Andersen
 */
import { computed, nextTick, onScopeDispose, ref, toValue, watch } from "vue";

/**
 * 为命令弹窗和 CRUD 容器管理一次打开期间的提交与关闭状态。
 * action 必须返回覆盖完整校验和请求的 Promise；事件兼容模式仍需调用方同步设置 saving。
 * @param {object} options 响应式可见性、外部忙碌和禁用状态，支持 getter。
 * @returns {object} busy、blocked 状态以及 run、markClosing 方法。
 */
export const useDialogSubmission = ({ visible, saving = false, disabled = false }) => {
  /** 当前异步动作持锁；重新打开也不能清除仍在执行的动作。 */
  const pending = ref(false);
  /** 关闭请求同步锁定入口，避免等待父组件更新和退出动画的窗口。 */
  const closing = ref(false);
  /** 可见性每次变化即切换周期，组件销毁后也使旧请求失效。 */
  let generation = 0;
  /** 请求期间阻止关闭，并向按钮提供加载反馈。 */
  const busy = computed(() => pending.value || toValue(saving));
  /** 隐藏、退出动画、异步处理和业务禁用均不允许提交。 */
  const blocked = computed(() => !toValue(visible) || closing.value || busy.value || toValue(disabled));

  watch(() => toValue(visible), (open) => {
    generation += 1;
    closing.value = !open;
  }, { flush: "sync" });

  onScopeDispose(() => { generation += 1; });

  /** 在发出关闭事件前锁定本次打开周期。 */
  const markClosing = () => { closing.value = true; };

  /**
   * 同步加锁并向 action 提供周期有效性检查；旧周期的结果和异常不交给新弹窗。
   * onError 在当前周期内同步通知，避免 finally 等待渲染后才派发过期异常。
   * 实际请求完成之前不释放互斥锁，关闭或重开不会使写请求并行执行。
   */
  const run = async (action, onError) => {
    if (blocked.value) return false;
    /** 本次动作所属的打开周期。 */
    const submittedGeneration = generation;
    /** 业务异步返回后，仅仍然可见的原周期可以产生组件事件。 */
    const isCurrent = () => submittedGeneration === generation && toValue(visible);
    pending.value = true;
    try {
      const result = await action(isCurrent);
      return isCurrent() ? result : false;
    } catch (error) {
      if (!isCurrent()) return false;
      if (typeof onError === "function") {
        onError(error);
        return false;
      }
      throw error;
    } finally {
      await nextTick();
      pending.value = false;
    }
  };

  return { busy, blocked, run, markClosing };
};
