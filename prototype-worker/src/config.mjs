/** 固定 P1/P2 工具链与磁盘边界；配置不接收模型提供的命令。@author Andersen */
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

/** 当前 Worker 源码目录，与调用进程的 cwd 无关。 */
export const workerRoot = fileURLToPath(new URL('../', import.meta.url));
/** 本机开发仓库根，用于定位受信模板与本机产物。 */
export const repositoryRoot = resolve(workerRoot, '..');
/** 默认可信模板位置。 */
export const templateDirectory = resolve(process.env.ALPHA_PROTOTYPE_TEMPLATE_DIR || resolve(repositoryRoot, 'prototype-template/zero-admin'));
/** 服务端配置的产物存储；不接收模型任务中的宿主路径。 */
export const defaultArtifactRoot = resolve(process.env.ALPHA_PROTOTYPE_ARTIFACT_ROOT || resolve(repositoryRoot, 'data/prototypes'));
/** Worker 与 Java、封存读取器共用的协议版本。 */
export const protocolVersion = 1;
/** 所有字节限制在读取和进程边界执行。 */
export const limits = Object.freeze({
  templateFiles: 1500,
  templateBytes: 15 * 1024 * 1024,
  generatedFiles: 100,
  generatedFileBytes: 256 * 1024,
  generatedBytes: 5 * 1024 * 1024,
  distBytes: 50 * 1024 * 1024,
  screenshotBytes: 20 * 1024 * 1024,
  logBytes: 1024 * 1024,
  resultBytes: 100 * 1024 * 1024,
  taskTimeoutMs: 300_000,
  buildTimeoutMs: 120_000,
  browserTimeoutMs: 120_000,
});
