/** P4 协议、平台接线及来源封存回归；本文件不把合成数据记为模型调用。@author Andersen */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { validateGeneratedRequest, composeGeneratedProject } from '../src/generated.mjs';
import { readTree, sha256, validateGeneratedFiles } from '../src/files.mjs';
import { publishArtifact } from '../src/artifacts.mjs';
import { templateDirectory } from '../src/config.mjs';

/** 每项测试拥有独立协议对象，避免可变引用掩盖验证错误。 */
function request() {
  return { protocolVersion: 1, mode: 'synthetic', generation: 'test-fixture', files: [{ path: 'src/features/generated/Items.vue', content: '<template><div data-testid="items">项目列表</div></template>' }],
    pages: [{ moduleId: 'items', pageId: 'list', title: '项目列表', path: '/items', component: 'src/features/generated/Items.vue' }], cases: [{ name: '列表文字', path: '/items', steps: [{ action: 'assertText', testId: 'items', text: '项目列表' }] }] };
}
/** 纯验证使用固定白名单，只有实际容器测试证明浏览器通过。 */
const template = { allowedImports: ['vue', '@/platform/components.js'] };

test('只接受全量生成目录、严格模式和有界文件，拒绝任意平台路径', () => {
  for (const modify of [value => { value.mode = 'live'; }, value => { value.command = 'sh'; }, value => { value.files[0].path = 'vite.config.js'; }, value => { value.files.push(value.files[0]); }, value => { value.files[0].content = 'x'.repeat(262145); }, value => { value.files = Array.from({ length: 101 }, (_, i) => ({ path: `src/mock/generated/${i}.json`, content: '{}' })); }]) {
    const value = request(); modify(value);
    assert.throws(() => validateGeneratedRequest(value, [], template), { code: 'PROTOTYPE_FILE_REJECTED' });
  }
});

test('页面复合身份、Vue文件、菜单及动态测试路径都必须完整', () => {
  for (const modify of [value => { value.pages.push(value.pages[0]); }, value => { value.pages[0].component = 'src/features/generated/Missing.vue'; }, value => { value.pages[0].path = '//outside'; }, value => { value.pages[0].menu = false; }, value => { value.pages[0].path = '/items/:id'; }, value => { value.pages[0].testPath = '/elsewhere'; }]) {
    const value = request(); modify(value);
    assert.throws(() => validateGeneratedRequest(value, [], template), { code: 'PROTOTYPE_FILE_REJECTED' });
  }
  const value = request();
  value.pages.push({ ...value.pages[0], pageId: 'detail', path: '/items/:id', menu: false, testPath: '/items/item-1' });
  assert.equal(validateGeneratedRequest(value, [], template).pages.length, 2);
});

test('补充验证仅解释固定动作和测试身份，拒绝脚本或任意URL', () => {
  for (const modify of [value => { value.cases[0].script = 'alert(1)'; }, value => { value.cases[0].path = 'https://example.com'; }, value => { value.cases[0].steps[0].action = 'evaluate'; }, value => { value.cases[0].steps[0].testId = 'x"] body'; }, value => { value.cases[0].steps = Array(21).fill(value.cases[0].steps[0]); }]) {
    const value = request(); modify(value);
    assert.throws(() => validateGeneratedRequest(value, [], template), { code: 'PROTOTYPE_FILE_REJECTED' });
  }
});

test('同一复合页面身份的路由名称不随计划顺序或标题改变', () => {
  const value = request();
  value.pages.push({ ...value.pages[0], moduleId: 'another', path: '/another', title: '第二模块' });
  const original = validateGeneratedRequest(value, [], template).pages;
  value.pages.reverse(); value.pages[0].title = '改名';
  const reordered = validateGeneratedRequest(value, [], template).pages;
  assert.equal(original[0].id, reordered[1].id);
  assert.equal(original[1].id, reordered[0].id);
  assert.notEqual(original[0].id, original[1].id);
  const routes = composeGeneratedProject(validateGeneratedRequest(value, [], template)).find(entry => entry.path === 'src/platform/routes.js').content.toString();
  assert.ok(routes.includes('moduleId:page.moduleId,pageId:page.pageId'));
});

test('平台替换旧样板并安全生成路由和来源文案，依赖与配置沿用可信模板', async () => {
  const files = await readTree(templateDirectory, { omitDevelopment: true });
  const value = request();
  value.pages[0].title = '标题";throw new Error(1);//';
  const composed = composeGeneratedProject(validateGeneratedRequest(value, files, JSON.parse((await readFile(join(templateDirectory, 'template.json'))).toString())));
  assert.ok(!composed.some(entry => entry.path.includes('PersonnelList.vue')));
  assert.equal(composed.find(entry => entry.path === 'package-lock.json').sha256, files.find(entry => entry.path === 'package-lock.json').sha256);
  const routes = composed.find(entry => entry.path === 'src/platform/routes.js').content.toString();
  assert.ok(routes.includes('../features/generated/Items.vue'));
  assert.ok(routes.includes(JSON.stringify(value.pages[0].title)));
  assert.ok(composed.find(entry => entry.path === 'src/project/profile.js').content.toString().includes('合成模型测试'));
});

test('组件名称和SFC嵌入资源在构建前检查', () => {
  for (const content of ["<script setup>import { Unknown } from '@/platform/components.js'</script>", '<template><iframe src="/"/></template>', '<style>@import "../private.css";</style>', '<style>div{background:url(file:///secret)}</style>']) {
    const file = { path: 'src/features/generated/Items.vue', content: Buffer.from(content), bytes: Buffer.byteLength(content) };
    assert.throws(() => validateGeneratedFiles([file], template), { code: 'PROTOTYPE_FILE_REJECTED' });
  }
});

test('业务产物封存不创建样板current指针且不允许伪造模式组合', async t => {
  const root = await mkdtemp(join(tmpdir(), 'alpha-generated-artifact-')); t.after(() => rm(root, { recursive: true, force: true }));
  const content = Buffer.from('<html>合成测试</html>');
  const result = { protocolVersion: 1, status: 'PASSED', validation: { status: 'PASSED', cases: [{ name: '合成测试记录', status: 'PASSED' }] }, files: [{ path: 'index.html', data: content.toString('base64'), bytes: content.length, sha256: sha256(content) }], screenshots: [] };
  const source = { path: 'src/App.vue', content, bytes: content.length, sha256: sha256(content) };
  const options = { artifactRoot: root, result, sourceFiles: [source], templateDigest: 'a'.repeat(64), toolchainId: `sha256:${'b'.repeat(64)}`, mode: 'synthetic', generation: 'test-fixture', updateCurrent: false };
  const artifact = await publishArtifact(options);
  assert.equal(artifact.manifest.mode, 'synthetic');
  await assert.rejects(readFile(join(root, 'current.json')), { code: 'ENOENT' });
  await assert.rejects(publishArtifact({ ...options, mode: 'live' }), { code: 'PROTOTYPE_FILE_REJECTED' });
  await assert.rejects(publishArtifact({ ...options, updateCurrent: true }), { code: 'PROTOTYPE_FILE_REJECTED' });
  await assert.rejects(publishArtifact({ ...options, result: { ...result, validation: { status: 'PASSED', cases: [] } } }), { code: 'PROTOTYPE_PREVIEW_FAILED' });
  await assert.rejects(publishArtifact({ ...options, result: { ...result, validation: { status: 'PASSED', cases: [{ name: '失败记录', status: 'FAILED' }] } } }), { code: 'PROTOTYPE_PREVIEW_FAILED' });
});
