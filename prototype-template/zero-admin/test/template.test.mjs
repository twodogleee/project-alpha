/** 验证固定来源、依赖闭包和原型公共边界；不替代浏览器验证。 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

/** 所有测试读取固定模板，不访问开发机上游仓库。 */
const root = fileURLToPath(new URL('../', import.meta.url));

/** 深度枚举源码文件用于导入闭包检查。 */
async function sourceFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  return (await Promise.all(entries.map(async (entry) => entry.isDirectory() ? sourceFiles(path.join(directory, entry.name)) : [path.join(directory, entry.name)]))).flat();
}

test('采用的 Zero 文件保持来源摘要，不依赖上游绝对路径', async () => {
  const source = JSON.parse(await readFile(path.join(root, 'zero-source.json'), 'utf8'));
  assert.equal(source.commit, '6e07bd5a78804b26ad2904c151e2bf3470631c4c');
  assert.ok(source.files.length >= 25);
  for (const file of source.files) {
    const content = await readFile(path.join(root, file.localPath));
    assert.equal(createHash('sha256').update(content).digest('hex'), file.sha256, file.localPath);
  }
});

test('源码静态导入形成自包含闭包，没有认证或后端依赖', async () => {
  const pkg = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8'));
  for (const file of await sourceFiles(path.join(root, 'src'))) {
    if (!/\.(js|vue|css)$/.test(file)) continue;
    const content = await readFile(file, 'utf8');
    for (const match of content.matchAll(/(?:from\s+|import\s*)["']([^"']+)["']/g)) {
      const specifier = match[1];
      if (specifier.startsWith('@/') || specifier.startsWith('.')) {
        const resolved = specifier.startsWith('@/') ? path.join(root, 'src', specifier.slice(2)) : path.resolve(path.dirname(file), specifier);
        assert.ok(resolved.startsWith(path.join(root, 'src') + path.sep), specifier);
        await readFile(resolved);
      } else {
        const packageName = specifier.startsWith('@') ? specifier.split('/').slice(0, 2).join('/') : specifier.split('/')[0];
        assert.ok(pkg.dependencies[packageName], `${file}: ${specifier}`);
      }
    }
  }
  const entry = await readFile(path.join(root, 'src/main.js'), 'utf8');
  assert.doesNotMatch(entry, /installPermissionGuards|piniaPluginPersistedstate|projectAuth/);
  const app = await readFile(path.join(root, 'src/App.vue'), 'utf8');
  assert.doesNotMatch(app, /from ['"].*generated/);
});

test('平台契约明确生成范围、模拟来源和固定版本', async () => {
  const template = JSON.parse(await readFile(path.join(root, 'template.json'), 'utf8'));
  const lock = JSON.parse(await readFile(path.join(root, 'package-lock.json'), 'utf8'));
  assert.equal(template.mode, 'demo');
  assert.deepEqual(template.writablePaths, ['src/features/generated/', 'src/mock/generated/']);
  assert.equal(lock.packages['node_modules/vue'].version, template.toolchain.vue);
  assert.equal(lock.packages['node_modules/vite'].version, template.toolchain.vite);
});
