/** 用宿主 Node 编译已核验的原型输入，并用本机 Chromium 验收。@author Andersen */
import { readFile, mkdir, writeFile, mkdtemp, rm, rename, symlink } from 'node:fs/promises';
import { resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { randomUUID } from 'node:crypto';
import { templateDirectory, defaultArtifactRoot } from './config.mjs';
import { readTree, writeTree, validateGeneratedFiles } from './files.mjs';
import { acquireExecutionSlot } from './process.mjs';
import { PrototypeError } from './errors.mjs';
import { publishArtifact } from './artifacts.mjs';
import { validateGeneratedRequest, composeGeneratedProject } from './generated.mjs';
import { hostToolchain } from './host-toolchain.mjs';
import { buildProject } from '../host/build.mjs';

/** 同时核对准备记录与即将编译的这份模板快照。 */
async function checkedToolchain(artifactRoot, templateDir, files) {
  let prepared;
  try { prepared = JSON.parse(await readFile(resolve(artifactRoot, 'toolchain.json'))); }
  catch { throw new PrototypeError('PROTOTYPE_TOOLCHAIN_NOT_PREPARED', '请先执行 npm run prototype:prepare 核验本机环境'); }
  if (prepared?.kind !== 'host' || !prepared.chromium) throw new PrototypeError('PROTOTYPE_TOOLCHAIN_NOT_PREPARED', '请执行 prototype:prepare 准备本机工具链');
  const current = await hostToolchain(templateDir, files, prepared.chromium);
  if (current.toolchainId !== prepared.toolchainId) throw new PrototypeError('PROTOTYPE_TOOLCHAIN_NOT_PREPARED', '本机工具链或模板已变化，请重新执行 prototype:prepare');
  return current;
}

/** Java 定期检查本机能力；未准备、依赖变化、浏览器缺失时均报告不可用。 */
export async function doctor({ artifactRoot = defaultArtifactRoot, templateDir = templateDirectory } = {}) {
  try {
    const files = await readTree(templateDir, { omitDevelopment: true });
    const { executablePath, ...metadata } = await checkedToolchain(artifactRoot, templateDir, files);
    return { available: true, ...metadata, supportedModes: ['demo', 'synthetic', 'live'] };
  } catch (error) {
    return { available: false, code: error instanceof PrototypeError ? error.code : 'PROTOTYPE_RUNNER_UNAVAILABLE',
      message: error instanceof PrototypeError ? error.message : '无法读取本机原型工具链，请检查模板、Node 和 Chromium' };
  }
}

/** 实际启动一次已有 Chromium，成功后原子替换本机工具链记录。 */
export async function prepareToolchain({ artifactRoot = defaultArtifactRoot, templateDir = templateDirectory } = {}) {
  const files = await readTree(templateDir, { omitDevelopment: true });
  validateGeneratedFiles(files, JSON.parse(files.find(file => file.path === 'template.json').content));
  const { executablePath, ...metadata } = await hostToolchain(templateDir, files);
  await mkdir(artifactRoot, { recursive: true });
  const temporary = resolve(artifactRoot, 'toolchain-' + randomUUID() + '.json');
  try {
    await writeFile(temporary, JSON.stringify(metadata, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    await rename(temporary, resolve(artifactRoot, 'toolchain.json'));
  } finally { await rm(temporary, { force: true }); }
  return metadata;
}

/** 样板与模型结果使用同一编译器、同一浏览器和产物校验；只有样板更新预览指针。 */
async function build(request, { artifactRoot = defaultArtifactRoot, templateDir = templateDirectory } = {}) {
  const templateFiles = await readTree(templateDir, { omitDevelopment: true });
  const template = JSON.parse(templateFiles.find(file => file.path === 'template.json').content);
  let files = templateFiles, mode = 'demo', generation = 'trusted-fixture';
  if (request !== undefined) {
    const validated = validateGeneratedRequest(request, templateFiles, template);
    files = composeGeneratedProject(validated);
    mode = validated.mode;
    generation = validated.generation;
  } else validateGeneratedFiles(files, template);
  const toolchain = await checkedToolchain(artifactRoot, templateDir, templateFiles);
  const release = await acquireExecutionSlot(artifactRoot);
  const controller = new AbortController();
  const cancel = () => controller.abort();
  process.once('SIGTERM', cancel);
  process.once('SIGINT', cancel);
  let taskRoot;
  try {
    taskRoot = await mkdtemp(resolve(tmpdir(), 'project-alpha-prototype-'));
    const project = resolve(taskRoot, 'source');
    await writeTree(project, files);
    await symlink(resolve(templateDir, 'node_modules'), resolve(project, 'node_modules'), process.platform === 'win32' ? 'junction' : 'dir');
    const result = await buildProject(taskRoot, templateDir, toolchain, controller.signal);
    controller.signal.throwIfAborted();
    return await publishArtifact({ artifactRoot, result, sourceFiles: files, templateDigest: toolchain.templateDigest,
      toolchainId: toolchain.toolchainId, mode, generation, updateCurrent: mode === 'demo' });
  } finally {
    try { if (taskRoot) await rm(taskRoot, { recursive: true, force: true }); }
    finally { await release(); process.removeListener('SIGTERM', cancel); process.removeListener('SIGINT', cancel); }
  }
}

/** 构建固定可信样板。 */
export function buildFixture(options) { return build(undefined, options); }

/** 编译生成请求；请求必须包含可校验的完整页面集合。 */
export function buildGenerated(request, options) {
  if (!request || typeof request !== 'object') throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '缺少原型生成请求');
  return build(request, options);
}
