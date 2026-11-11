/** 项目、会话与业务版本接口；路径与响应解包集中管理。@author Andersen */
import { request } from "./http.js";
import { prepareWorkspaceResponse } from "./prd-catalog.js";
/** 工作空间独立前缀，不读取旧评审项目。 */
const root = "/workspace";
/** 编码稳定标识，避免页面拼接服务路径。 */
const key = (id) => encodeURIComponent(id);
/** 通用有界查询，只传递明确的分页和归属参数。 */
const get = (url, params, signal) =>
  request({ method: "GET", url: root + url, params, signal }).then(prepareWorkspaceResponse);
/** 通用命令不控制页面交互。 */
const command = (method, url, data) =>
  request({ method, url: root + url, data }).then(prepareWorkspaceResponse);
/** 项目分页目录。 */ export const listWorkspaceProjects = (page = 1, signal) =>
  get("/projects", { page }, signal);
/** 创建产品容器。 */ export const newProject = (data) =>
  command("POST", "/projects", data);
/** 项目背景。 */ export const loadProject = (id, signal) =>
  get(`/projects/${key(id)}`, null, signal);
/** 更新产品信息。 */ export const editProject = (id, data) =>
  command("PUT", `/projects/${key(id)}`, data);
/** 归档或恢复项目。 */ export const archiveProject = (id, data) =>
  command("PUT", `/projects/${key(id)}/archive`, data);
/** 项目内会话分页。 */ export const listSessions = (id, params = {}, signal) =>
  get(`/projects/${key(id)}/sessions`, params, signal);
/** 新建可选关联版本的会话。 */ export const newSession = (id, data) =>
  command("POST", `/projects/${key(id)}/sessions`, data);
/** 读取会话、所属项目、关联版本与任务。 */ export const loadSession = (
  id,
  signal,
) => get(`/sessions/${key(id)}`, null, signal);
/** 较早消息分页。 */ export const loadMessages = (id, before, signal) =>
  get(`/sessions/${key(id)}/messages`, { before }, signal);
/** 会话改名。 */ export const renameSession = (id, data) =>
  command("PUT", `/sessions/${key(id)}/title`, data);
/** 会话归档不结束交付版本。 */ export const archiveSession = (id, data) =>
  command("PUT", `/sessions/${key(id)}/archive`, data);
/** 探索会话关联同项目版本。 */ export const linkVersion = (id, data) =>
  command("PUT", `/sessions/${key(id)}/version`, data);
/** 提交幂等模型任务。 */ export const submitRun = (id, data) =>
  command("POST", `/sessions/${key(id)}/runs`, data);
/** 刷新后接管真实任务状态。 */ export const loadRun = (id, signal) =>
  get(`/runs/${key(id)}`, null, signal);
/** 显式保存候选草稿。 */ export const saveDraft = (id, data) =>
  command("PUT", `/sessions/${key(id)}/draft`, data);
/** 以已保存草稿形成业务版本。 */ export const promoteDraft = (id, data) =>
  command("POST", `/sessions/${key(id)}/promote`, data);
/** 同步草稿至关联版本。 */ export const syncDraft = (id, data) =>
  command("POST", `/sessions/${key(id)}/sync`, data);
/** 项目内业务版本目录。 */ export const listRequirementVersions = (
  id,
  params = {},
  signal,
) => get(`/projects/${key(id)}/versions`, params, signal);
/** 手工定义版本，无需模型生成。 */ export const newVersion = (id, data) =>
  command("POST", `/projects/${key(id)}/versions`, data);
/** 版本详情。 */ export const loadVersion = (id, signal) =>
  get(`/versions/${key(id)}`, null, signal);
/** 保存范围的内部修订。 */ export const editVersion = (id, data) =>
  command("PUT", `/versions/${key(id)}`, data);
/** 取消需明确原因。 */ export const cancelVersion = (id, data) =>
  command("POST", `/versions/${key(id)}/cancel`, data);
/** 已取消版本归档或恢复。 */ export const archiveVersion = (id, data) =>
  command("PUT", `/versions/${key(id)}/archive`, data);
/** 范围保存记录分页。 */ export const listRevisions = (id, page = 1, signal) =>
  get(`/versions/${key(id)}/revisions`, { page }, signal);
/** 归档目录分页。 */ export const loadArchives = (page = 1, signal) =>
  get("/archives", { page }, signal);
/** 正式产物读取，返回服务器门槛与确认来源。 */ export const loadLifecycle = (
  id,
  signal,
) => get(`/versions/${key(id)}/lifecycle`, null, signal);
/** 保存结构化评审，确认状态由后端校正。 */ export const saveReview = (
  id,
  data,
) => command("PUT", `/versions/${key(id)}/lifecycle/review`, data);
/** 显式人工确认评审基线并进入 PRD。 */ export const confirmReview = (
  id,
  data,
) => command("POST", `/versions/${key(id)}/lifecycle/review/confirm`, data);
/** 保留历史并重新评审上游变更。 */ export const reopenReview = (id, data) =>
  command("POST", `/versions/${key(id)}/lifecycle/reopen`, data);
/** 保存引用具体评审基线的 PRD。 */ export const savePrd = (id, data) =>
  command("PUT", `/versions/${key(id)}/lifecycle/prd`, data);
/** 显式确认 PRD 基线，保留原型阶段里程碑。 */ export const confirmPrd = (
  id,
  data,
) => command("POST", `/versions/${key(id)}/lifecycle/prd/confirm`, data);
/** 后台生成候选，不自动保存。 */ export const generatePrd = (id, data) =>
  command("POST", `/versions/${key(id)}/lifecycle/prd/runs`, data);
/** 按产物种类读取保存记录与变更原因。 */ export const lifecycleHistory = (
  id,
  kind,
  page = 1,
  signal,
) => get(`/versions/${key(id)}/lifecycle/history`, { kind, page }, signal);
/** 不可变确认基线分页，包含正文和来源。 */ export const lifecycleBaselines = (
  id,
  page = 1,
  signal,
) => get(`/versions/${key(id)}/lifecycle/baselines`, { page }, signal);
/** 显式打开统一会话，必要时建立一次交付草稿。 */
export const openWorkbench = (id) => command("POST", `/sessions/${key(id)}/workbench/open`);
/** 旧版本入口复用最近会话。 */
export const openVersionWorkbench = (id) => command("POST", `/versions/${key(id)}/workbench/open`);
/** 读取产物与持久化任务。 */
export const loadWorkbench = (id) => get(`/sessions/${key(id)}/workbench`);
/** 双修订校验的自动保存。 */
export const saveWorkbench = (id, data) => command("PUT", `/sessions/${key(id)}/workbench`, data);
/** 会话增量生成或编辑同一份 PRD。 */
export const submitWorkbench = (id, data) => command("POST", `/sessions/${key(id)}/workbench/runs`, data);
/** 只读当前会话中指定消息的参考附件，响应不包含完整模型运行上下文。 */
export const loadWorkbenchAttachments = (id, runId) => get(`/sessions/${key(id)}/workbench/runs/${key(runId)}/attachments`);
/** 明确采纳或忽略建议，正文与建议始终分开。 */
export const decideWorkbenchSuggestion = (id, data) => command("POST", `/sessions/${key(id)}/workbench/suggestions`, data);
/** 继续编辑已确认文档。 */
export const resumeWorkbenchPrd = (id, data) => command("POST", `/sessions/${key(id)}/workbench/prd/edit`, data);
/** PRD 人工定稿保持独立，原型生成可引用本次不可变定稿。 */
export const confirmWorkbenchPrd = (id, data) => command("POST", `/sessions/${key(id)}/workbench/prd/confirm`, data);
/** 删除前取得文档共享范围，不隐式创建文档。 */
export const sessionDeletionPreview = (id) => get(`/sessions/${key(id)}/deletion-preview`);
/** 删除或恢复会话，正文与历史不物理删除。 */
export const deleteSession = (id, data) => command('PUT', `/sessions/${key(id)}/deletion`, data);
/** 回收站会话独立分页。 */
export const loadDeletedSessions = (page = 1, signal) => get('/trash/sessions', { page }, signal);
/** 文档删除前读取正文修订与受影响会话数量。 */
export const versionDeletionPreview = (id) => get(`/versions/${key(id)}/deletion-preview`);
/** 单独删除或恢复文档，不删除关联会话。 */
export const deleteVersion = (id, data) => command('PUT', `/versions/${key(id)}/deletion`, data);
/** 已删除文档独立分页。 */
export const loadDeletedVersions = (page = 1, signal) => get('/trash/versions', { page }, signal);
