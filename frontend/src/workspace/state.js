/** 工作空间纯状态契约；完整模型记录在服务器，不在浏览器持久化。@author Andersen */
/** 真正运行中的状态，其他状态停止轮询。 */
export const isRunning = (run) => ["QUEUED", "RUNNING"].includes(run?.status);
/** 找到执行中任务，刷新后接管；不会隐式重试失败任务。 */
export const pendingRun = (runs = []) => runs.find(isRunning) || null;
/** 失败和服务中断属于终态，只能由用户明确重试。 */
export const isFailedRun = (run) => ["FAILED", "INTERRUPTED"].includes(run?.status);
/** 接口按创建时间倒序返回任务；只突出最新失败，不让历史错误跟随后续任务一直显示。 */
export function latestRunFailure(runs = [], dismissedRunId = null) {
  const latest = runs[0];
  return isFailedRun(latest) && latest.id !== dismissedRunId ? latest : null;
}
/** 历史失败归属原用户消息，不把助手回复或其他请求误标为失败。 */
export function messageRunFailure(message, runs = []) {
  return message?.role === "USER" ? runs.find(run => run.id === message.runId && isFailedRun(run)) || null : null;
}
/** 按稳定序号合并消息，轮询和历史加载不制造重复条目。 */
export function mergeMessages(current, incoming) {
  return [
    ...new Map([...current, ...incoming].map((m) => [m.id, m])).values(),
  ].sort((a, b) => a.sequence - b.sequence);
}
/** 候选草稿必须匹配会话和生成时保存编号才能应用。 */
export function canApplyDraft(run, session, version) {
  return Boolean(
    run?.kind === "DRAFT" &&
      run.status === "SUCCEEDED" &&
      run.sessionId === session?.id &&
      run.draftRevision === session.draftRevision &&
      !session.archivedAt &&
      (run.versionId || null) === (session.versionId || null) &&
      (!run.versionId || run.versionRevision === version?.revision),
  );
}
/** 版本生命周期不是保存次数，原型已接入工作台，开发验收仍未开放。 */
export const stages = [
  { id: "REVIEW", name: "需求评审", available: true },
  { id: "PRD", name: "PRD", available: true },
  { id: "PROTOTYPE", name: "原型", available: true },
  { id: "DEVELOPMENT", name: "开发与验收", available: false },
];
/** 稳定业务路由，不依赖显示名称。 */
export const projectPath = (id) => `/projects/${encodeURIComponent(id)}`;
/** 会话路由携带归属。 */
export const sessionPath = (session) =>
  `${projectPath(session.projectId)}/sessions/${encodeURIComponent(session.id)}`;
/** 需求版本路由携带归属。 */
export const versionPath = (version) =>
  `${projectPath(version.projectId)}/versions/${encodeURIComponent(version.id)}`;
/** 一次命令重试复用标识，只有输入改变或命令成功后才产生新标识。 */
export function runCommand(
  previous,
  sessionId,
  kind,
  content,
  makeId = () => crypto.randomUUID(),
) {
  return previous?.sessionId === sessionId &&
    previous.kind === kind &&
    previous.content === content
    ? previous
    : { sessionId, kind, content, requestId: makeId() };
}
