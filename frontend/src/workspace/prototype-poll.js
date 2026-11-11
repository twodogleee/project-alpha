/** 串行轮询与上下文代次；离开页面后迟到响应不会更新新版本。@author Andersen */
export function createPrototypePoller({ read, applied, failed, pending, delay = 1800, schedule = setTimeout, cancel = clearTimeout }) {
  /** 同一代次只保留一个请求和一个定时器。 */
  let generation = 0, timer = null, inFlight = null, disposed = false;
  /** 刷新合并同一代次请求；终态停止，失败由用户显式恢复。 */
  function refresh() {
    if (disposed) return Promise.resolve(null);
    if (inFlight) return inFlight;
    cancel(timer); timer = null;
    const own = generation;
    inFlight = Promise.resolve().then(read).then(value => {
      if (disposed || own !== generation) return null;
      applied(value);
      if (pending(value)) timer = schedule(refresh, delay);
      return value;
    }).catch(error => { if (!disposed && own === generation) failed(error); return null; }).finally(() => { if (own === generation) inFlight = null; });
    return inFlight;
  }
  /** 切换版本废弃旧请求的状态应用，不把读取取消误当成任务取消。 */
  function reset() { generation++; cancel(timer); timer = null; inFlight = null; }
  /** 清理仅释放前端监听，业务任务继续由后台执行。 */
  function dispose() { reset(); disposed = true; }
  return { refresh, reset, dispose };
}
