/** 文件、运行参数和产物提交边界回归，覆盖受控子进程与本机浏览器配置。 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, mkdir, symlink, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { assertRelativePath, readTree, validateGeneratedFiles, validatePatch, sha256 } from '../src/files.mjs';
import { findChromium } from '../src/host-toolchain.mjs';
import { decodeResultFiles, publishArtifact, readCurrentArtifact } from '../src/artifacts.mjs';
import { runProcess, acquireExecutionSlot } from '../src/process.mjs';

/** 创建会由测试生命周期自动清理的独立目录。 */
async function temporary(t) { const root = await mkdtemp(join(tmpdir(), 'alpha-prototype-test-')); t.after(() => rm(root, { recursive: true, force: true })); return root; }
/** 为纯函数检查构造有摘要的普通文件。 */
function file(path, text) { const content = Buffer.from(text); return { path, content, bytes: content.length, sha256: sha256(content) }; }
/** 为产物协议构造执行器输出形状。 */
function encoded(path, text) { const value = file(path, text); return { path, data: value.content.toString('base64'), bytes: value.bytes, sha256: value.sha256 }; }
/** 成功协议中的测试结果明确为合成，不能作为真实模型或浏览器记录。 */
function success(text = '<html>fixture</html>') { return { protocolVersion: 1, status: 'PASSED', validation: { status: 'PASSED', cases: [], synthetic: true }, files: [encoded('index.html', text)], screenshots: [] }; }

test('路径穿越、绝对路径、Windows路径与控制字符被拒绝', () => {
  for (const path of ['../secret', '/etc/passwd', 'a/../b', 'a//b', 'a\\b', 'C:/secret', './a', 'a\0b']) assert.throws(() => assertRelativePath(path), { code: 'PROTOTYPE_FILE_REJECTED' });
  assert.equal(assertRelativePath('src/features/generated/page.vue'), 'src/features/generated/page.vue');
});

test('文件树拒绝链接、环境文件及超量数据', async t => {
  const root = await temporary(t);
  await writeFile(join(root, 'page.js'), 'content');
  await assert.rejects(readTree(root, { maxBytes: 2 }), { code: 'PROTOTYPE_FILE_REJECTED' });
  await symlink('/etc/passwd', join(root, 'secret'));
  await assert.rejects(readTree(root), { code: 'PROTOTYPE_FILE_REJECTED' });
  await rm(join(root, 'secret'));
  await writeFile(join(root, '.env'), 'PRIVATE=value');
  await assert.rejects(readTree(root, { omitDevelopment: true }), { code: 'PROTOTYPE_FILE_REJECTED' });
});

test('生成脚本导入仅允许契约入口和真实生成文件', () => {
  const page = file('src/features/generated/page.js', "import { ref } from 'vue'; import state from '../../mock/generated/state.json';");
  const state = file('src/mock/generated/state.json', '{}');
  assert.doesNotThrow(() => validateGeneratedFiles([page, state], { allowedImports: ['vue'] }));
  for (const text of ["import fs from 'node:fs'", "import('/etc/passwd')", "import(name)", "require('fs')", "const all = import.meta.glob('/**/*')", "import missing from './missing.js'"]) {
    assert.throws(() => validateGeneratedFiles([file('src/features/generated/page.js', text)], { allowedImports: [] }), { code: 'PROTOTYPE_FILE_REJECTED' });
  }
});

test('Vue外部源、v-html与未知脚本/样式语言被拒绝', () => {
  for (const content of ['<template><div v-html="text" /></template>', '<script src="/secret.js"></script>', '<style src="../../secret.css"></style>', '<style lang="scss">a{color:red}</style>', '<script setup lang="ts">const a: number = 1;</script>', '<style>@config "./evil.js";</style>', '<template lang="pug">p demo</template>']) {
    assert.throws(() => validateGeneratedFiles([file('src/features/generated/Page.vue', content)], { allowedImports: [] }), { code: 'PROTOTYPE_FILE_REJECTED' });
  }
  assert.throws(() => validateGeneratedFiles([file('src/features/generated/postcss.config.js', 'export default {}')], { allowedImports: [] }), { code: 'PROTOTYPE_FILE_REJECTED' });
  assert.doesNotThrow(() => validateGeneratedFiles([file('src/features/generated/Page.vue', '<script setup>const text="纯文本";</script><template><p>{{text}}</p></template><style>p{color:red}</style>')], { allowedImports: [] }));
});

test('增量操作不允许改配置、重复路径或覆盖错误基准', () => {
  const original = file('src/features/generated/page.js', 'export default 1');
  assert.throws(() => validatePatch([{ kind: 'CREATE', path: 'package.json', content: '{}' }], [original]), { code: 'PROTOTYPE_FILE_REJECTED' });
  assert.throws(() => validatePatch([{ kind: 'REPLACE', path: original.path, expectedSha256: 'bad', content: '2' }], [original]), { code: 'PROTOTYPE_REVISION_CONFLICT' });
  assert.doesNotThrow(() => validatePatch([{ kind: 'DELETE', path: original.path, expectedSha256: original.sha256 }], [original]));
  assert.throws(() => validatePatch([{ kind: 'DELETE', path: original.path, expectedSha256: original.sha256 }, { kind: 'DELETE', path: original.path, expectedSha256: original.sha256 }], [original]), { code: 'PROTOTYPE_FILE_REJECTED' });
});

test('显式 Chromium 配置只接受已有本机可执行文件，错误路径不会触发下载或回退', async t => {
  assert.ok(await findChromium(process.execPath));
  for (const path of ['relative/chromium', join(await temporary(t), 'missing')]) {
    await assert.rejects(findChromium(path), { code: 'PROTOTYPE_RUNNER_UNAVAILABLE' });
  }
});

test('容器产物的大小、摘要、重复文件、特殊扩展被独立检查', () => {
  const value = encoded('index.html', 'hello');
  assert.throws(() => decodeResultFiles([value], 2));
  assert.throws(() => decodeResultFiles([{ ...value, sha256: 'bad' }], 100));
  assert.throws(() => decodeResultFiles([value, value], 100));
  assert.throws(() => decodeResultFiles([encoded('run.sh', 'echo x')], 100));
  assert.throws(() => decodeResultFiles([encoded('../index.html', 'x')], 100));
  for (const path of ['index.js.map', '.hidden.js', 'assets/a%2fb.js', 'assets/a?b.js', 'assets/a b.js']) assert.throws(() => decodeResultFiles([encoded(path, 'x')], 100));
  assert.throws(() => decodeResultFiles(Array.from({ length: 1001 }, (_, i) => encoded(`a${i}.js`, '')), 100));
  assert.equal(decodeResultFiles([encoded('large.js', 'x'.repeat(2 * 1024 * 1024))], 3 * 1024 * 1024)[0].bytes, 2 * 1024 * 1024);
});

test('失败候选不会替换当前产物，历史文件篡改时拒绝预览', async t => {
  const artifactRoot = await temporary(t);
  const sourceFiles = [file('src/App.vue', '<template>demo</template>')];
  const initial = await publishArtifact({ artifactRoot, result: success(), sourceFiles, templateDigest: 'test', toolchainId: 'test-image' });
  const pointer = await readFile(join(artifactRoot, 'current.json'), 'utf8');
  await assert.rejects(publishArtifact({ artifactRoot, result: { ...success(), status: 'FAILED' }, sourceFiles, templateDigest: 'test', toolchainId: 'test-image' }));
  await assert.rejects(publishArtifact({ artifactRoot, result: { ...success(), files: [{ ...encoded('index.html', 'x'), sha256: 'wrong' }] }, sourceFiles, templateDigest: 'test', toolchainId: 'test-image' }));
  assert.equal(await readFile(join(artifactRoot, 'current.json'), 'utf8'), pointer);
  assert.equal((await readCurrentArtifact({ artifactRoot })).id, initial.id);
  await writeFile(join(initial.distDir, 'index.html'), 'tampered');
  await assert.rejects(readCurrentArtifact({ artifactRoot }), { code: 'PROTOTYPE_ARTIFACT_UNAVAILABLE' });
});

test('再次封存完全相同内容复用对象；新结果保留旧历史', async t => {
  const artifactRoot = await temporary(t);
  const options = { artifactRoot, sourceFiles: [file('a.json', '{}')], templateDigest: 'same', toolchainId: 'image' };
  const first = await publishArtifact({ ...options, result: success() });
  const duplicate = await publishArtifact({ ...options, result: success() });
  assert.equal(first.id, duplicate.id);
  const second = await publishArtifact({ ...options, result: success('<html>updated</html>') });
  assert.notEqual(first.id, second.id);
  assert.equal((await readCurrentArtifact({ artifactRoot })).id, second.id);
  assert.equal(await readFile(join(first.distDir, 'index.html'), 'utf8'), '<html>fixture</html>');
});

test('进程超时和日志超量均失败关闭，清理回调最多一次', async () => {
  let cleanups = 0;
  await assert.rejects(runProcess(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { timeoutMs: 50, onTimeout: () => { cleanups++; } }), { code: 'PROTOTYPE_TIMEOUT' });
  assert.equal(cleanups, 1);
  await assert.rejects(runProcess(process.execPath, ['-e', 'process.stdout.write("x".repeat(100000))'], { maxOutput: 10 }), { code: 'PROTOTYPE_OUTPUT_LIMIT' });
});

test('执行槽阻止并行构建，释放后可以继续且旧释放不会删除新槽', async t => {
  const root = await temporary(t);
  const first = await acquireExecutionSlot(root);
  await assert.rejects(acquireExecutionSlot(root), { code: 'PROTOTYPE_RUNNER_BUSY' });
  await first();
  const second = await acquireExecutionSlot(root);
  await first();
  await assert.rejects(acquireExecutionSlot(root), { code: 'PROTOTYPE_RUNNER_BUSY' });
  await second();
});

test('超时不等待持有继承管道的后代进程自然退出', async () => {
  const started = Date.now();
  const code = `require('node:child_process').spawn(process.execPath,['-e','setTimeout(()=>{},1500)'],{stdio:'inherit'});setTimeout(()=>{},1500)`;
  await assert.rejects(runProcess(process.execPath, ['-e', code], { timeoutMs: 100 }), { code: 'PROTOTYPE_TIMEOUT' });
  assert.ok(Date.now() - started < 800, '超时必须在后代自然结束之前返回');
});
