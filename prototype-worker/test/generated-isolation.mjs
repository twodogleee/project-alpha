/** 显式在本机编译并启动 Chromium：验证生成/CSP负例和旧样板兼容，不属于快速单元测试。@author Andersen */
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { buildGenerated, buildFixture } from '../src/runner.mjs';
import { defaultArtifactRoot } from '../src/config.mjs';

/** 固定合成输入不冒充模型输出；使用独立临时工程运行。 */
const request = JSON.parse(await readFile(new URL('./fixtures/generated-request.json', import.meta.url), 'utf8'));
/** 原有样板指针不应被生成验收改变。 */
const pointer = await readFile(resolve(defaultArtifactRoot, 'current.json'), 'utf8').catch(() => null);
/** 成功路径实际编译、浏览器交互及封存。 */
const generated = await buildGenerated(request);
/** 不允许依赖实际预览会拒绝的动态执行和持久浏览器存储。 */
const rejected = [];
for (const [name, snippet, diagnosticPattern] of [
  ['eval', "eval('1 + 1');", /unsafe-eval|Content Security Policy|EvalError/i],
  ['new Function', "new Function('return 1')();", /unsafe-eval|Content Security Policy|EvalError/i],
  ['localStorage', "localStorage.setItem('prototype-probe','x');", /localStorage|sandbox|SecurityError/i],
  ['sessionStorage', "sessionStorage.setItem('prototype-probe','x');", /sessionStorage|sandbox|SecurityError/i],
]) {
  const probe = structuredClone(request);
  probe.files[1].content = probe.files[1].content.replace('<script setup>', `<script setup>\n${snippet}`);
  try { await buildGenerated(probe); throw new Error(`${name} 不应通过隔离验收`); }
  catch (error) {
    assert.equal(error.code, 'PROTOTYPE_PREVIEW_FAILED');
    assert.match(String(error.diagnostic), diagnosticPattern);
    rejected.push({ name, code: error.code, browserPolicyEvidence: true });
  }
}
/** 旧样板回归使用独立产物目录，不更新用户正在使用的样板 current。 */
const fixtureRoot = await mkdtemp(resolve(tmpdir(), 'alpha-p4-fixture-regression-'));
let fixture;
try {
  await writeFile(resolve(fixtureRoot, 'toolchain.json'), await readFile(resolve(defaultArtifactRoot, 'toolchain.json')));
  fixture = await buildFixture({ artifactRoot: fixtureRoot });
} finally { await rm(fixtureRoot, { recursive: true, force: true }); }
assert.equal(await readFile(resolve(defaultArtifactRoot, 'current.json'), 'utf8').catch(() => null), pointer);
process.stdout.write(JSON.stringify({ generatedArtifactId: generated.id, generatedChecks: generated.manifest.validation.cases.length, rejected, fixtureChecks: fixture.manifest.validation.cases.length, pointerUnchanged: true }, null, 2) + '\n');
