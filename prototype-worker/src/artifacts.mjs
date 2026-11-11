/** 不可变产物封存和最后可用预览指针；暂不承担 P3 业务身份与数据库事务。@author Andersen */
import { mkdir, readFile, writeFile, rename, rm, lstat } from 'node:fs/promises';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { defaultArtifactRoot, limits, protocolVersion } from './config.mjs';
import { readTree, treeDigest, writeTree, sha256, assertRelativePath } from './files.mjs';
import { PrototypeError } from './errors.mjs';
import { assertStaticPath, MAX_STATIC_FILES } from './static-policy.mjs';

/** 解码执行器响应时重新检查路径、内容哈希、类型与总字节数。 */
export function decodeResultFiles(entries, maxBytes, { screenshot = false } = {}) {
  if (!Array.isArray(entries) || entries.length > (screenshot ? 20 : MAX_STATIC_FILES)) throw new PrototypeError('PROTOTYPE_ARTIFACT_UNAVAILABLE', '产物清单无效');
  let total = 0;
  const seen = new Set();
  return entries.map(entry => {
    assertRelativePath(entry.path);
    assertStaticPath(entry.path);
    if (seen.has(entry.path) || (screenshot && !/\.png$/.test(entry.path))) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '产物路径重复或类型无效');
    seen.add(entry.path);
    if (typeof entry.data !== 'string' || entry.data.length % 4 !== 0 || /[^A-Za-z0-9+/=]/.test(entry.data) || entry.data.length > Math.ceil(maxBytes / 3) * 4) throw new PrototypeError('PROTOTYPE_ARTIFACT_UNAVAILABLE', '产物内容编码无效');
    const content = Buffer.from(entry.data, 'base64');
    if (content.toString('base64') !== entry.data) throw new PrototypeError('PROTOTYPE_ARTIFACT_UNAVAILABLE', '产物内容不是规范 base64');
    total += content.length;
    if (total > maxBytes || content.length !== entry.bytes || sha256(content) !== entry.sha256) throw new PrototypeError('PROTOTYPE_ARTIFACT_UNAVAILABLE', '产物摘要或大小不一致');
    return { path: entry.path, content, sha256: entry.sha256, bytes: content.length };
  });
}

/** 封存后仅样板可更新本地 current；业务生成的当前指针始终由 P3 数据库管理。 */
export async function publishArtifact({ artifactRoot = defaultArtifactRoot, result, sourceFiles, templateDigest, toolchainId, mode = 'demo', generation = 'trusted-fixture', updateCurrent = true }) {
  if (!new Set(['demo/trusted-fixture', 'synthetic/test-fixture', 'live/model']).has(`${mode}/${generation}`) || (mode !== 'demo' && updateCurrent)) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '产物来源或指针操作无效');
  if (result.protocolVersion !== protocolVersion || result.status !== 'PASSED' || result.validation?.status !== 'PASSED') throw new PrototypeError('PROTOTYPE_PREVIEW_FAILED', '候选没有通过浏览器验证');
  if (mode !== 'demo' && (!Array.isArray(result.validation.cases) || !result.validation.cases.length || result.validation.cases.some(entry => entry.status !== 'PASSED'))) throw new PrototypeError('PROTOTYPE_PREVIEW_FAILED', '生成产物必须具有非空且全部通过的平台验证记录');
  const dist = decodeResultFiles(result.files, limits.distBytes);
  const screenshots = decodeResultFiles(result.screenshots || [], limits.screenshotBytes, { screenshot: true });
  if (screenshots.length > 20 || !dist.some(file => file.path === 'index.html')) throw new PrototypeError('PROTOTYPE_ARTIFACT_UNAVAILABLE', '预览入口或截图数量无效');
  const sourceDigest = treeDigest(sourceFiles);
  const manifest = {
    protocolVersion,
    mode,
    generation,
    notice: mode === 'live' ? '真实模型生成；业务行为使用模拟数据，尚未人工确认需求完整性' : mode === 'synthetic' ? '合成模型测试，未调用真实大模型；业务行为使用模拟数据' : '本地模板联调，未调用大模型；业务行为使用模拟数据',
    templateDigest,
    sourceDigest,
    toolchainId,
    validation: result.validation,
    files: dist.map(({ path, sha256: hash, bytes }) => ({ path, sha256: hash, bytes })),
    screenshots: screenshots.map(({ path, sha256: hash, bytes }) => ({ path, sha256: hash, bytes })),
    sources: sourceFiles.map(({ path, sha256: hash, bytes }) => ({ path, sha256: hash, bytes })),
  };
  const manifestText = JSON.stringify(manifest, null, 2) + '\n';
  const id = sha256(manifestText);
  await mkdir(resolve(artifactRoot, 'artifacts'), { recursive: true });
  const staging = resolve(artifactRoot, 'artifacts', `.staging-${randomUUID()}`);
  const destination = resolve(artifactRoot, 'artifacts', id);
  try {
    await writeTree(resolve(staging, 'dist'), dist);
    await writeTree(resolve(staging, 'screenshots'), screenshots);
    await writeTree(resolve(staging, 'source'), sourceFiles);
    await writeFile(resolve(staging, 'artifact.json'), manifestText, { flag: 'wx', mode: 0o600 });
    try { await rename(staging, destination); }
    catch (error) {
      if (!['EEXIST', 'ENOTEMPTY'].includes(error.code)) throw error;
      await validateArtifact({ artifactRoot, id });
      await rm(staging, { recursive: true, force: true });
    }
    await validateArtifact({ artifactRoot, id });
    if (updateCurrent) {
      const pointer = resolve(artifactRoot, `current-${randomUUID()}.json`);
      await writeFile(pointer, JSON.stringify({ id }) + '\n', { flag: 'wx', mode: 0o600 });
      await rename(pointer, resolve(artifactRoot, 'current.json'));
    }
    return { id, distDir: resolve(destination, 'dist'), manifest };
  } catch (error) {
    await rm(staging, { recursive: true, force: true });
    throw error;
  }
}

/** 核验封存内容，包括来源文件；被修改或丢失时禁止预览。 */
export async function validateArtifact({ artifactRoot = defaultArtifactRoot, id }) {
  if (!/^[a-f0-9]{64}$/.test(id || '')) throw new PrototypeError('PROTOTYPE_ARTIFACT_UNAVAILABLE', '产物标识无效');
  try {
    const directory = resolve(artifactRoot, 'artifacts', id);
    if ((await lstat(directory)).isSymbolicLink() || (await lstat(resolve(directory, 'artifact.json'))).isSymbolicLink()) throw new Error('symbolic artifact');
    const text = await readFile(resolve(directory, 'artifact.json'), 'utf8');
    if (Buffer.byteLength(text) > limits.logBytes || sha256(text) !== id) throw new Error('manifest mismatch');
    const manifest = JSON.parse(text);
    for (const [folder, key, maxBytes] of [['dist', 'files', limits.distBytes], ['screenshots', 'screenshots', limits.screenshotBytes], ['source', 'sources', limits.templateBytes]]) {
      const files = await readTree(resolve(directory, folder), { maxFiles: 1500, maxBytes });
      if (folder === 'dist') {
        if (files.length > MAX_STATIC_FILES) throw new Error('too many static files');
        files.forEach(file => assertStaticPath(file.path));
      }
      if (treeDigest(files) !== treeDigest(manifest[key])) throw new Error('file mismatch');
    }
    return { id, distDir: resolve(directory, 'dist'), manifest };
  } catch {
    throw new PrototypeError('PROTOTYPE_ARTIFACT_UNAVAILABLE', '封存产物缺失、损坏或被修改');
  }
}

/** 读取最后成功的本地样板，不接受客户端任意路径。 */
export async function readCurrentArtifact({ artifactRoot = defaultArtifactRoot } = {}) {
  try {
    const pointer = resolve(artifactRoot, 'current.json');
    if ((await lstat(pointer)).isSymbolicLink()) throw new Error('symbolic pointer');
    const content = await readFile(pointer, 'utf8');
    if (content.length > 1024) throw new Error('pointer too large');
    return await validateArtifact({ artifactRoot, id: JSON.parse(content).id });
  } catch (error) {
    if (error instanceof PrototypeError) throw error;
    throw new PrototypeError('PROTOTYPE_ARTIFACT_UNAVAILABLE', '尚无可用的本地样板产物，请先完成隔离构建');
  }
}
