/** 解析宿主机已安装的浏览器和锁定依赖；只接受本地运维配置。@author Andersen */
import { constants } from 'node:fs';
import { access, readFile, readdir, realpath, stat } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, join, resolve, isAbsolute, delimiter } from 'node:path';
import { chromium } from 'playwright';
import { limits, workerRoot } from './config.mjs';
import { readTree, treeDigest, sha256 } from './files.mjs';
import { PrototypeError } from './errors.mjs';

/** 显式路径错误时直接报告，避免实际验证使用另一个浏览器。 */
export async function findChromium(explicit = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH) {
  const preferred = chromium.executablePath();
  const candidates = [];
  if (explicit) {
    if (!isAbsolute(explicit)) throw new PrototypeError('PROTOTYPE_RUNNER_UNAVAILABLE', 'Chromium 配置需要可执行文件的绝对路径');
    candidates.push(explicit);
  } else {
    candidates.push(preferred);
    // 从 Playwright 的当前平台路径推导缓存根；支持与 Worker 预期修订不同的已安装 Chromium。
    let cache = dirname(preferred);
    while (dirname(cache) !== cache && !/^chromium-\d+$/.test(cache.split(/[\\/]/).at(-1))) cache = dirname(cache);
    cache = dirname(cache);
    const entries = await readdir(cache).catch(() => []);
    const chromePaths = process.platform === 'darwin'
      ? [`chrome-mac-${process.arch}/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`, 'chrome-mac/Chromium.app/Contents/MacOS/Chromium']
      : process.platform === 'win32' ? ['chrome-win64/chrome.exe', 'chrome-win/chrome.exe'] : ['chrome-linux64/chrome', 'chrome-linux/chrome'];
    const shellPaths = process.platform === 'darwin'
      ? [`chrome-headless-shell-mac-${process.arch}/chrome-headless-shell`, 'chrome-mac/headless_shell']
      : process.platform === 'win32' ? ['chrome-headless-shell-win64/chrome-headless-shell.exe', 'chrome-win/headless_shell.exe'] : ['chrome-headless-shell-linux64/chrome-headless-shell', 'chrome-linux/headless_shell'];
    for (const entry of entries.filter(x => /^chromium(?:_headless_shell)?-\d+$/.test(x)).sort((a,b) => Number(b.split('-').at(-1)) - Number(a.split('-').at(-1)))) {
      for (const name of entry.startsWith('chromium_headless_shell') ? shellPaths : chromePaths) candidates.push(join(cache, entry, name));
    }
    if (process.platform === 'darwin') {
      for (const apps of ['/Applications', join(homedir(), 'Applications')]) {
        for (const name of ['Chromium', 'Google Chrome']) candidates.push(join(apps, name + '.app', 'Contents/MacOS', name));
      }
    } else {
      for (const bin of (process.env.PATH || '').split(delimiter).filter(Boolean)) {
        for (const name of ['chromium', 'chromium-browser', 'google-chrome', 'chrome.exe']) candidates.push(join(bin, name));
      }
    }
  }
  for (const candidate of candidates) {
    try { await access(candidate, constants.X_OK); if ((await stat(candidate)).isFile()) return await realpath(candidate); }
    catch { /* 继续检查已安装的候选。 */ }
  }
  throw new PrototypeError('PROTOTYPE_RUNNER_UNAVAILABLE', '找不到可执行的本机 Chromium，请设置 PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH');
}

/** 本地浏览器使用独立的临时用户资料，只传入启动所需的系统环境。 */
export function browserOptions(executablePath) {
  const env = {};
  for (const name of ['HOME', 'USERPROFILE', 'TMPDIR', 'TMP', 'TEMP', 'PATH', 'SystemRoot', 'LOCALAPPDATA', 'LANG', 'DISPLAY', 'XDG_RUNTIME_DIR']) {
    if (process.env[name]) env[name] = process.env[name];
  }
  return { executablePath, headless: true, chromiumSandbox: true, env, timeout: 15_000 };
}

/** 读取实际安装版本，与本地锁文件逐项核对；不触发安装。 */
async function installedVersion(root, name) {
  const lock = JSON.parse(await readFile(resolve(root, 'package-lock.json')));
  const pkg = JSON.parse(await readFile(resolve(root, 'node_modules', name, 'package.json')));
  if (pkg.version !== lock.packages['node_modules/' + name]?.version) throw new Error('依赖与锁文件不符');
  return pkg.version;
}

/** 准备与探测共用当前模板、执行器、依赖及真实浏览器版本的指纹。 */
export async function hostToolchain(templateDir, files, preparedVersion) {
  const template = JSON.parse(files.find(file => file.path === 'template.json').content);
  if (template.id !== 'zero-admin' || template.revision !== 2) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '不支持的原型模板修订');
  const executablePath = await findChromium();
  const binary = await stat(executablePath);
  const browserStamp = sha256(JSON.stringify([executablePath, binary.size, binary.mtimeMs, binary.ino]));
  let version = preparedVersion;
  if (!version) {
    // 使用临时资料完成真实握手。单独运行 Chrome --version 在部分平台可能打开日常浏览器。
    const browser = await chromium.launch(browserOptions(executablePath));
    try { await browser.newPage(); version = browser.version(); } finally { await browser.close(); }
  }
  let vite, vue, playwright;
  try {
    [vite, vue, playwright] = await Promise.all([installedVersion(templateDir, 'vite'), installedVersion(templateDir, 'vue'), installedVersion(workerRoot, 'playwright')]);
    await access(resolve(templateDir, 'node_modules/vite/bin/vite.js'), constants.R_OK);
    if (template.lockSha256 !== sha256(await readFile(resolve(templateDir, 'package-lock.json')))) throw new Error('依赖锁摘要已变化');
  } catch { throw new PrototypeError('PROTOTYPE_TOOLCHAIN_NOT_PREPARED', '请用 prototype:install 安装模板和 Worker 的锁定依赖，并核对模板锁文件摘要'); }
  const worker = await readTree(workerRoot, { omitDevelopment: true, maxBytes: limits.templateBytes });
  const metadata = { kind: 'host', node: process.version, platform: process.platform, architecture: process.arch, vite, vue, playwright, chromium: version, browserStamp,
    templateId: template.id, templateRevision: template.revision, templateDigest: treeDigest(files),
    workerDigest: treeDigest(worker.filter(file => /^(src\/|host\/|package(?:-lock)?\.json$)/.test(file.path))) };
  return { executablePath, ...metadata, toolchainId: 'host-sha256:' + sha256(JSON.stringify(metadata)) };
}
