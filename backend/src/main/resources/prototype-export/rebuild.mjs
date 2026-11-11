/** 独立交付包按修订类型重建。宿主修订使用本地锁定依赖，旧容器修订保留原重建方式。@author Andersen */
import { lstatSync, writeFileSync, mkdtempSync, mkdirSync, chmodSync, symlinkSync, rmSync, openSync, readSync, closeSync, fstatSync, constants } from 'node:fs';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash, randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';

/** 交付包根目录固定为脚本所在目录，不接受外部源码路径。 */
const root = dirname(fileURLToPath(import.meta.url));
/** 限长读取清单，避免损坏交付包导致无界读取。 */
const bounded = (file, limit) => {
  const descriptor = openSync(file, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const stat = fstatSync(descriptor);
    if (!stat.isFile() || stat.size > limit) throw new Error('交付文件不完整或超过大小限制');
    const data = Buffer.alloc(stat.size + 1);
    let size = 0, count;
    while (size < data.length && (count = readSync(descriptor, data, size, data.length - size, null)) > 0) size += count;
    if (size !== stat.size) throw new Error('交付文件已改变');
    return data.subarray(0, size);
  } finally { closeSync(descriptor); }
};
/** 固定路径校验及目录链接检查；归属仅来自已经导出的清单。 */
const sourcePath = name => {
  if (typeof name !== 'string' || !name.startsWith('source/') || name.includes('\\') || name.includes(':') || name.split('/').some(part => !part || part.startsWith('.') || /[\x00-\x1f]/.test(part))) throw new Error('源码路径不安全');
  const file = resolve(root, name);
  if (!file.startsWith(root + sep)) throw new Error('源码路径不安全');
  for (let current = dirname(file); current !== root; current = dirname(current)) {
    const stat = lstatSync(current);
    if (!stat.isDirectory() || stat.isSymbolicLink()) throw new Error('源码目录不安全');
  }
  return file;
};

/** 唯一容器名可在 CLI 失败、超时或中断时清理；不触碰已有容器。 */
const containerName = `alpha-export-${randomUUID()}`;
/** 临时输入只存已重新核验的字节，不直接挂载解压目录。 */
let snapshotRoot;
let containerStarted = false;
/** 同步 Docker 子进程超时或输出超限也尝试停止确切容器。 */
const cleanup = () => {
  if (containerStarted) spawnSync('docker', ['rm', '-f', containerName], { stdio: 'ignore', timeout: 10000 });
  if (snapshotRoot) { rmSync(snapshotRoot, { recursive: true, force: true }); snapshotRoot = undefined; }
};
/** 中断不允许留下继续运行的生成构建。 */
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => { cleanup(); process.exit(signal === 'SIGINT' ? 130 : 143); });

try {
  /** 清单中的完整工具链内容身份；只使用本机已有镜像。 */
  const manifest = JSON.parse(bounded(resolve(root, 'export-manifest.json'), 4 * 1024 * 1024));
  if (manifest.format !== 'source' || !/^(?:host-)?sha256:[a-f0-9]{64}$/.test(manifest.toolchainId) || !Array.isArray(manifest.files)) throw new Error('交付清单无效');
  let total = 0;
  /** 校验封存源码字节后才启动隔离构建。 */
  const entries = manifest.files.filter(entry => typeof entry.path === 'string' && entry.path.startsWith('source/'));
  if (!entries.length || entries.length > 1500) throw new Error('源码数量无效');
  snapshotRoot = mkdtempSync(resolve(tmpdir(), 'alpha-export-')); chmodSync(snapshotRoot, manifest.toolchainId.startsWith('host-sha256:') ? 0o700 : 0o755);
  const seen = new Set();
  for (const entry of entries) {
    if (seen.has(entry.path)) throw new Error('源码清单重复');
    seen.add(entry.path);
    if (!Number.isSafeInteger(entry.bytes) || entry.bytes < 0 || (total += entry.bytes) > 15 * 1024 * 1024) throw new Error('源码大小无效');
    const bytes = bounded(sourcePath(entry.path), entry.bytes);
    if (bytes.length !== entry.bytes || createHash('sha256').update(bytes).digest('hex') !== entry.sha256) throw new Error('源码摘要与交付清单不一致');
    const snapshotFile = resolve(snapshotRoot, entry.path);
    mkdirSync(dirname(snapshotFile), { recursive: true, mode: 0o755 });
    writeFileSync(snapshotFile, bytes, { flag: 'wx', mode: 0o644 });
  }
  let result;
  if (manifest.toolchainId.startsWith('host-sha256:')) {
    // 开发者先在解包目录安装锁定依赖；临时工程仅包含清单核验过的源码。
    const modules = resolve(root, 'source/node_modules');
    const project = resolve(snapshotRoot, 'source');
    symlinkSync(modules, resolve(project, 'node_modules'), process.platform === 'win32' ? 'junction' : 'dir');
    const env = { PATH: process.env.PATH, HOME: snapshotRoot, TMPDIR: snapshotRoot, NODE_ENV: 'production' };
    if (process.platform === 'win32') env.SystemRoot = process.env.SystemRoot;
    const compile = spawnSync(process.execPath, [resolve(modules, 'vite/bin/vite.js'), 'build', '--outDir', resolve(snapshotRoot, 'dist'), '--emptyOutDir'],
      { cwd: project, env, timeout: 150000, maxBuffer: 1024 * 1024 });
    if (compile.error || compile.status !== 0) throw new Error('请检查 source/ 的锁定依赖及本机 Node 版本');
    const flags = process.platform === 'darwin' ? ['--no-mac-metadata', '--no-xattrs'] : [];
    result = spawnSync('tar', [...flags, '-C', snapshotRoot, '-czf', '-', 'dist'], {
      env: { ...env, COPYFILE_DISABLE: '1' }, timeout: 30000, maxBuffer: 64 * 1024 * 1024,
    });
    if (result.error || result.status !== 0 || !result.stdout?.length) throw new Error('静态归档失败');
  } else {
    /** 固定容器脚本；生成的 Vite 配置只能在该受限容器执行。 */
    const program = `
      const fs = require('node:fs');
      const cp = require('node:child_process');
      fs.cpSync('/input/source', '/work/project', { recursive: true, dereference: false });
      fs.symlinkSync('/opt/template/node_modules', '/work/project/node_modules');
      const build = cp.spawnSync(process.execPath, ['/opt/template/node_modules/vite/bin/vite.js', 'build', '--outDir', '/work/dist'], { cwd: '/work/project', stdio: ['ignore', 2, 2], timeout: 150000 });
      if (build.error || build.status !== 0) process.exit(1);
      const tar = cp.spawnSync('tar', ['-C', '/work', '-czf', '-', 'dist'], { stdio: ['ignore', 1, 2], timeout: 30000 });
      if (tar.error || tar.status !== 0) process.exit(1);
    `;
    /** 最多接收 64 MiB 压缩输出；日志走 stderr，stdout 仅承载 tar。 */
    containerStarted = true;
    result = spawnSync('docker', ['run', '--rm', '--name', containerName, '--pull', 'never', '--network', 'none', '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--user', '1000:1000', '--cpus', '2', '--memory', '2g', '--pids-limit', '128', '--tmpfs', '/work:rw,nosuid,nodev,size=384m,uid=1000,gid=1000', '--tmpfs', '/tmp:rw,nosuid,nodev,size=128m,uid=1000,gid=1000', '--mount', `type=bind,source=${snapshotRoot},target=/input,readonly`, '--entrypoint', 'node', manifest.toolchainId, '-e', program], { stdio: ['ignore', 'pipe', 'inherit'], maxBuffer: 64 * 1024 * 1024, timeout: 180000 });
    if (result.error || result.status !== 0 || !result.stdout?.length) throw new Error('固定工具链重建失败，请确认 Docker 与清单指定的本机镜像可用');
  }
  writeFileSync(resolve(root, 'rebuilt-static.tar.gz'), result.stdout, { flag: constants.O_WRONLY | constants.O_CREAT | constants.O_TRUNC | constants.O_NOFOLLOW, mode: 0o644 });
  process.stdout.write('已生成 rebuilt-static.tar.gz。执行 tar -xzf rebuilt-static.tar.gz，再运行 node serve.mjs dist。重建成功不代表重新通过浏览器业务验收。\n');
} catch (error) {
  // 不透传 Docker/文件系统对象中的宿主路径或任意交付正文。
  process.stderr.write('重建未完成：请核对交付包完整性，并按 README 准备该修订需要的本机工具链。\n');
  process.exitCode = 1;
} finally { cleanup(); }
