/** P4 生成任务的纯数据协议与平台接线；模型不能提供入口、配置或验证代码。@author Andersen */
import { assertRelativePath, sha256, validateGeneratedFiles } from './files.mjs';
import { limits } from './config.mjs';
import { PrototypeError } from './errors.mjs';

/** 仅这两处目录属于业务生成器。 */
const roots = ['src/features/generated/', 'src/mock/generated/'];
/** 路由采用有限片段，不允许查询参数、编码转义、正则或通配符。 */
const routePattern = /^\/(?:[A-Za-z0-9_-]+|:[A-Za-z][A-Za-z0-9_]*)(?:\/(?:[A-Za-z0-9_-]+|:[A-Za-z][A-Za-z0-9_]*))*$/;
/** 实际验收地址必须是可导航的本地哈希路径。 */
const concretePathPattern = /^\/[A-Za-z0-9_-]+(?:\/[A-Za-z0-9_-]+)*$/;
/** 稳定测试标识不承载 CSS 或 JavaScript 表达式。 */
const testIdPattern = /^[A-Za-z0-9_:-]{1,120}$/;

/** 返回统一协议错误，不把完整模型源码放入异常。 */
function rejected(message) { return new PrototypeError('PROTOTYPE_FILE_REJECTED', message); }
/** 在每层拒绝未知键，避免参数被误认为已执行或进入未来命令面。 */
function exactKeys(value, allowed, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some(key => !allowed.includes(key))) throw rejected(`${label}包含未知字段或不是对象`);
}
/** 文本按字节有界，名称保留正常中文，不允许控制字符。 */
function text(value, maximum, label) {
  if (typeof value !== 'string' || !value.trim() || Buffer.byteLength(value) > maximum || /[\x00-\x1f\x7f]/.test(value)) throw rejected(`${label}无效或超限`);
  return value;
}
/** 平台计算文件摘要，忽略调用者对文件大小或摘要的任何推断。 */
function file(path, content) { const buffer = Buffer.from(content, 'utf8'); return { path, content: buffer, bytes: buffer.length, sha256: sha256(buffer) }; }

/** 完整生成文件集替换旧样板目录；每个清单页面都必须引用本次真实 Vue 文件。 */
export function validateGeneratedRequest(request, templateFiles, template) {
  exactKeys(request, ['protocolVersion', 'mode', 'generation', 'files', 'pages', 'cases'], '生成任务');
  if (request.protocolVersion !== 1 || !['live/model', 'synthetic/test-fixture'].includes(`${request.mode}/${request.generation}`)) throw rejected('生成协议或来源组合无效');
  if (!Array.isArray(request.files) || request.files.length < 1 || request.files.length > limits.generatedFiles) throw rejected('生成文件数量无效');
  const names = new Set();
  let total = 0;
  const generated = request.files.map(entry => {
    exactKeys(entry, ['path', 'content'], '生成文件');
    assertRelativePath(entry.path);
    if (!roots.some(root => entry.path.startsWith(root)) || !/\.(js|json|vue)$/.test(entry.path) || names.has(entry.path)) throw rejected('生成文件越界、重复或类型不受支持');
    if (typeof entry.content !== 'string' || Buffer.byteLength(entry.content) > limits.generatedFileBytes) throw rejected('生成文件内容无效或超过 256 KiB');
    names.add(entry.path);
    const entryFile = file(entry.path, entry.content);
    total += entryFile.bytes;
    if (total > limits.generatedBytes) throw rejected('生成文本超过 5 MiB');
    return entryFile;
  });
  if (!Array.isArray(request.pages) || request.pages.length < 1 || request.pages.length > 10) throw rejected('页面清单必须包含 1–10 项');
  const identities = new Set();
  const routes = new Set();
  const pages = request.pages.map(entry => {
    exactKeys(entry, ['moduleId', 'pageId', 'title', 'path', 'component', 'menu', 'testPath'], '页面');
    text(entry.moduleId, 200, '模块身份'); text(entry.pageId, 200, '页面身份'); text(entry.title, 200, '页面标题');
    const identity = JSON.stringify([entry.moduleId, entry.pageId]);
    if (identities.has(identity) || typeof entry.path !== 'string' || entry.path.length > 200 || !routePattern.test(entry.path) || routes.has(entry.path)) throw rejected('页面身份或路由无效/重复');
    identities.add(identity); routes.add(entry.path);
    assertRelativePath(entry.component);
    if (!entry.component.startsWith(roots[0]) || !entry.component.endsWith('.vue') || !names.has(entry.component)) throw rejected('页面组件必须引用本次生成的 Vue 文件');
    const testPath = entry.testPath || entry.path;
    if (!concretePathPattern.test(testPath) || testPath.length > 200) throw rejected('动态路由必须声明具体 testPath');
    const pattern = new RegExp(`^${entry.path.replace(/:[A-Za-z][A-Za-z0-9_]*/g, '[A-Za-z0-9_-]+')}$`);
    if (!pattern.test(testPath)) throw rejected('testPath 与页面路由不匹配');
    if (entry.menu !== undefined && typeof entry.menu !== 'boolean') throw rejected('menu 必须是布尔值');
    const menu = entry.menu !== false;
    if (menu && entry.path.includes(':')) throw rejected('菜单入口必须是静态路由');
    return { moduleId: entry.moduleId, pageId: entry.pageId, title: entry.title, path: entry.path, component: entry.component, testPath, menu, id: `generated-${sha256(identity)}` };
  });
  if (!pages.some(page => page.menu)) throw rejected('至少需要一个静态菜单入口');
  const declaredPaths = new Set(pages.map(page => page.testPath));
  const rawCases = request.cases ?? [];
  if (!Array.isArray(rawCases) || rawCases.length > 20) throw rejected('补充用例最多 20 项');
  let stepsCount = 0;
  const cases = rawCases.map(entry => {
    exactKeys(entry, ['name', 'path', 'steps'], '补充用例');
    text(entry.name, 200, '用例名称');
    if (!declaredPaths.has(entry.path) || !Array.isArray(entry.steps) || entry.steps.length < 1 || entry.steps.length > 20) throw rejected('用例只能访问已声明路由且包含 1–20 个步骤');
    stepsCount += entry.steps.length;
    if (stepsCount > 100) throw rejected('补充用例步骤总量超过 100');
    const steps = entry.steps.map(step => {
      exactKeys(step, ['action', 'testId', 'text', 'value'], '用例步骤');
      if (!['click', 'fill', 'assertText', 'assertVisible'].includes(step.action)) throw rejected('用例动作不受支持');
      if (step.testId !== undefined && !testIdPattern.test(step.testId)) throw rejected('测试标识无效');
      if (step.action !== 'assertText' && !step.testId) throw rejected('该动作必须提供测试标识');
      if (step.action === 'assertText') text(step.text, 2000, '断言文本');
      else if (step.text !== undefined) throw rejected('只有文本断言允许 text');
      if (step.action === 'fill') {
        if (typeof step.value !== 'string' || Buffer.byteLength(step.value) > 2000 || step.value.includes('\0')) throw rejected('输入值无效或超限');
      } else if (step.value !== undefined) throw rejected('只有输入动作允许 value');
      return { ...step };
    });
    return { name: entry.name, path: entry.path, steps };
  });
  const files = templateFiles.filter(entry => !roots.some(root => entry.path.startsWith(root)));
  files.push(...generated);
  validateGeneratedFiles(files, template);
  return { mode: request.mode, generation: request.generation, files, pages, cases };
}

/** 只生成已验证页面的静态导入，不将模型文本拼成可执行表达式。 */
export function composeGeneratedProject(request) {
  const { pages, cases, mode, generation } = request;
  const entry = pages.find(page => page.menu);
  const imports = pages.map((page, index) => `import Page${index} from ${JSON.stringify(`../${page.component.slice(4)}`)};`).join('\n');
  const pageCode = pages.map((page, index) => `{...${JSON.stringify({ id: page.id, moduleId: page.moduleId, pageId: page.pageId, path: page.path, title: page.title, menu: page.menu, parentPath: page.menu ? undefined : entry.path })},component:Page${index}}`).join(',\n');
  const routes = `/** 平台根据已检查页面清单组装，禁止模型直接覆盖。@author Andersen */\nimport { createRouter, createWebHashHistory } from 'vue-router';\n${imports}\nexport const pages=Object.freeze([${pageCode}]);\nexport const router=createRouter({history:createWebHashHistory(),routes:[{path:'/',redirect:${JSON.stringify(entry.path)}},...pages.map(page=>({path:page.path,name:page.id,component:page.component,meta:{title:page.title,parentPath:page.parentPath,moduleId:page.moduleId,pageId:page.pageId}})),{path:'/:pathMatch(.*)*',redirect:${JSON.stringify(entry.path)}}]});\n`;
  const provenance = mode === 'live' ? '真实模型生成 · 所有操作使用模拟数据 · 尚未人工确认需求完整性' : '合成模型测试 · 未调用真实大模型 · 所有操作使用模拟数据';
  const profile = `/** 平台原型展示身份，不含凭证。@author Andersen */\nexport const profile=Object.freeze(${JSON.stringify({ id: 'zero-admin', title: entry.title, provenance })});\n`;
  const platform = new Map([
    ['src/platform/routes.js', routes],
    ['src/project/profile.js', profile],
    ['prototype-verification.json', JSON.stringify({ protocolVersion: 1, kind: 'generated', mode, generation, pages, cases })],
  ]);
  return [...request.files.filter(entryFile => !platform.has(entryFile.path)), ...Array.from(platform, ([path, content]) => file(path, content))];
}
