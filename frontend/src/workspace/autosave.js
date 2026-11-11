/** 串行自动保存队列，合并输入且不以迟到响应覆盖当前编辑。@author Andersen */
export function createAutosave({ snapshot, persist, applied, failed, delay = 700 }) {
  /** 服务器已接受的输入快照。 */
  let saved = "";
  /** 去抖计时器与唯一执行 Promise。 */
  let timer = null, inFlight = null;
  /** 失败停止自动重试，卸载停止队列。 */
  let blocked = false, disposed = false;
  /** 标记已接受的快照，不修改编辑副本。 */
  function reset(value) { saved = value; blocked = false; }
  /** 判断本地编辑是否尚未持久化。 */
  function dirty() { return snapshot() !== saved; }
  /** 串行保存最新输入，响应期间的编辑进入下一次提交。 */
  async function drain() {
    while (!disposed && !blocked && dirty()) {
      const sent = snapshot();
      try {
        const result = await persist(JSON.parse(sent));
        if (disposed) return false;
        saved = sent;
        applied(result, snapshot() === sent);
      } catch (error) {
        blocked = true;
        if (!disposed) failed(error);
        return false;
      }
    }
    return !disposed && !blocked;
  }
  /** 发送消息或离开前刷完所有编辑，失败保留输入。 */
  async function flush() {
    clearTimeout(timer);
    if (disposed || blocked) return false;
    if (!inFlight) inFlight = drain().finally(() => { inFlight = null; });
    return inFlight;
  }
  /** 合并频繁输入，不产生额外保存按钮或确认。 */
  function schedule() { clearTimeout(timer); if (!blocked && !disposed) timer = setTimeout(flush, delay); }
  /** 网络失败需明确重试，修订冲突仍由后端拒绝。 */
  function retry() { blocked = false; return flush(); }
  /** 用户修正输入格式后恢复去抖，不能用于解除并发冲突。 */
  function resume() { blocked = false; schedule(); }
  /** 不取消已发出的命令，忽略卸载后的响应。 */
  function dispose() { disposed = true; clearTimeout(timer); }
  return { reset, dirty, flush, schedule, retry, resume, dispose };
}
