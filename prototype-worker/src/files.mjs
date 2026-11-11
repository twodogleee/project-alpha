/** 有界文件读取、完整性摘要和生成目录检查；不执行待检查源码。@author Andersen */
import { createHash } from 'node:crypto';
import { lstat, readdir, readFile, mkdir, writeFile, realpath } from 'node:fs/promises';
import { dirname, resolve, relative, posix, isAbsolute } from 'node:path';
import { parse as parseJs } from 'acorn';
import { parse as parseVue } from '@vue/compiler-sfc';
import { PrototypeError } from './errors.mjs';
import { limits } from './config.mjs';

/** 原型可写目录不会包含配置和平台组件。 */
const generatedRoots = ['src/features/generated/', 'src/mock/generated/'];
/** 开发缓存不进入准备上下文、任务输入或模板指纹。 */
const omittedNames = new Set(['node_modules', 'dist', '.git', '.DS_Store']);
/** 固定组件入口公开的名称；错误名称在编译前作为可修复契约错误返回。 */
const platformComponents = new Set(['ETable', 'QueryPanel', 'CrudDialog', 'CrudDrawer', 'CrudDetail', 'CrudDetailItem', 'AsyncState', 'StatusTag']);

/** 对文本或字节计算内容标识。 */
export function sha256(value) { return createHash('sha256').update(value).digest('hex'); }

/** 文件名必须采用普通相对 POSIX 路径，拒绝路径正规化后产生歧义。 */
export function assertRelativePath(path) {
  if (typeof path !== 'string' || !path || path.includes('\\') || /[\x00-\x1f\x7f]/.test(path)
    || path.includes(':') || isAbsolute(path) || path.startsWith('/') || path.split('/').some(part => !part || part === '.' || part === '..')) {
    throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '文件路径不是受支持的相对路径');
  }
  return path;
}

/** 读取可信模板或封存产物；任何符号链接或特殊文件都拒绝，不跨越根目录。 */
export async function readTree(directory, { maxFiles = limits.templateFiles, maxBytes = limits.templateBytes, omitDevelopment = false } = {}) {
  const files = [];
  let bytes = 0;
  const rootStat = await lstat(directory);
  if (!rootStat.isDirectory() || rootStat.isSymbolicLink()) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '输入根目录不是普通目录');
  const canonicalRoot = await realpath(directory);
  /** 递归前先 lstat，每一个分量都只能是普通文件或目录。 */
  async function visit(prefix) {
    for (const name of (await readdir(resolve(directory, prefix))).sort()) {
      if (omitDevelopment && omittedNames.has(name)) continue;
      const path = assertRelativePath(prefix ? `${prefix}/${name}` : name);
      if (omitDevelopment && (name === '.env' || name.startsWith('.env.'))) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '模板不能包含环境配置文件');
      const absolute = resolve(directory, path);
      const stat = await lstat(absolute);
      if (stat.isSymbolicLink() || (!stat.isDirectory() && !stat.isFile())) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '输入中包含符号链接或特殊文件');
      const resolved = await realpath(absolute);
      if (relative(canonicalRoot, resolved).startsWith('..')) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '文件越出输入目录');
      if (stat.isDirectory()) await visit(path);
      else {
        bytes += stat.size;
        if (files.length >= maxFiles || bytes > maxBytes) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '文件数量或总大小超过限制');
        const content = await readFile(absolute);
        if (content.length !== stat.size) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '检查期间文件发生变化');
        files.push({ path, content, sha256: sha256(content), bytes: content.length });
      }
    }
  }
  await visit('');
  return files;
}

/** 路径、摘要和大小共同构成稳定的树指纹。 */
export function treeDigest(files) {
  return sha256(JSON.stringify(files.map(({ path, sha256: hash, bytes }) => ({ path, sha256: hash, bytes })).sort((a, b) => a.path.localeCompare(b.path))));
}

/** 将已检查内容写到全新目录，不从外部包提取符号链接或可执行元数据。 */
export async function writeTree(directory, files) {
  await mkdir(directory, { recursive: true });
  const seen = new Set();
  for (const file of files) {
    assertRelativePath(file.path);
    if (seen.has(file.path)) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '文件路径重复');
    seen.add(file.path);
    await mkdir(dirname(resolve(directory, file.path)), { recursive: true });
    await writeFile(resolve(directory, file.path), file.content, { flag: 'wx', mode: 0o600 });
  }
}

/** 解析 AST 识别导入；动态表达式和 CommonJS require 不属于生成契约。 */
function collectImports(source, filename) {
  let ast;
  try { ast = parseJs(source, { ecmaVersion: 'latest', sourceType: 'module' }); }
  catch { throw new PrototypeError('PROTOTYPE_FILE_REJECTED', `生成脚本无法解析：${filename}`); }
  const imports = [];
  /** 只遍历语法节点，不访问或执行属性值。 */
  function walk(node) {
    if (!node || typeof node !== 'object') return;
    if (['ImportDeclaration', 'ExportNamedDeclaration', 'ExportAllDeclaration'].includes(node.type) && node.source) imports.push(node.source.value);
    if (node.type === 'ImportDeclaration' && node.source.value === '@/platform/components.js') {
      if (node.specifiers.some(specifier => specifier.type !== 'ImportSpecifier' || !platformComponents.has(specifier.imported.name))) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '公共组件导入不符合固定契约');
    }
    if (node.type === 'ImportExpression') {
      if (node.source.type !== 'Literal' || typeof node.source.value !== 'string') throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '不允许计算式动态导入');
      imports.push(node.source.value);
    }
    if (node.type === 'CallExpression' && node.callee?.type === 'Identifier' && node.callee.name === 'require') throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '不允许 CommonJS require');
    if (node.type === 'MemberExpression' && node.object?.type === 'MetaProperty' && node.object.meta?.name === 'import') throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '生成源码不能使用 import.meta 计算资源入口');
    for (const value of Object.values(node)) {
      if (Array.isArray(value)) value.forEach(walk);
      else if (value && typeof value === 'object') walk(value);
    }
  }
  walk(ast);
  return imports;
}

/** 检查生成目录的文件类型、导入边界与 Vue 外部文本展示约束。 */
export function validateGeneratedFiles(files, template) {
  const available = new Set(files.map(file => file.path));
  const generated = files.filter(file => generatedRoots.some(root => file.path.startsWith(root)));
  if (generated.length > limits.generatedFiles || generated.reduce((sum, file) => sum + file.bytes, 0) > limits.generatedBytes) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '生成文件总量超过限制');
  const allowed = new Set(template.allowedImports || []);
  for (const file of generated) {
    if (!/\.(vue|js|json)$/.test(file.path) || file.bytes > limits.generatedFileBytes) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '生成文件类型或大小不受支持');
    // 工具会沿目录查找包和编译配置；生成区只能提交页面和业务模块。
    if (file.path.split('/').some(part => part === 'node_modules' || part.startsWith('.') || /^(?:package(?:-lock)?\.json|(?:postcss|tailwind|vite|babel)\.config\..+)$/i.test(part))) {
      throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '生成目录不能包含工具配置、包描述或隐藏文件');
    }
    const text = file.content.toString('utf8');
    if (file.path.endsWith('.json')) {
      try { JSON.parse(text); } catch { throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '模拟数据不是有效 JSON'); }
      continue;
    }
    let scripts = [text];
    if (file.path.endsWith('.vue')) {
      const parsed = parseVue(text);
      if (parsed.errors.length) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', 'Vue 页面结构无效');
      if (parsed.descriptor.customBlocks.length || (parsed.descriptor.template?.lang && parsed.descriptor.template.lang !== 'html')) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '生成页面仅支持标准 Vue 模板');
      if (parsed.descriptor.template?.src || parsed.descriptor.script?.src || parsed.descriptor.scriptSetup?.src) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '生成页面不能使用外部 SFC 源文件');
      if (parsed.descriptor.styles.some(style => style.src || (style.lang && style.lang !== 'css'))) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '生成页面样式只支持内联 CSS');
      if (parsed.descriptor.styles.some(style => /@(?:import|config|plugin|source)\b|url\s*\(|image-set\s*\(/i.test(style.content))) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '生成样式不能引入外部文件或资源');
      if (/\bv-html\b/i.test(parsed.descriptor.template?.content || '')) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '生成页面不允许 v-html');
      if (/<\s*(script|iframe|object|embed|link|meta)\b/i.test(parsed.descriptor.template?.content || '')) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '生成页面含不支持的嵌入元素');
      if ([parsed.descriptor.script, parsed.descriptor.scriptSetup].some(block => block?.lang && block.lang !== 'js')) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '一期生成脚本仅支持 JavaScript');
      scripts = [parsed.descriptor.script?.content || '', parsed.descriptor.scriptSetup?.content || ''];
    }
    for (const source of scripts) {
      for (const imported of collectImports(source, file.path)) {
        if (typeof imported !== 'string') throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '导入标识无效');
        if (allowed.has(imported)) continue;
        let target;
        if (imported.startsWith('./') || imported.startsWith('../')) target = posix.normalize(posix.join(posix.dirname(file.path), imported));
        else if (imported.startsWith('@/')) target = `src/${imported.slice(2)}`;
        if (!target || !generatedRoots.some(root => target.startsWith(root)) || !available.has(target)) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', `生成文件引用了未允许模块：${file.path}`);
      }
    }
  }
}

/** 生成补丁必须显式在允许目录内操作，且对旧文件给出精确基准摘要。 */
export function validatePatch(operations, files) {
  if (!Array.isArray(operations) || operations.length > limits.generatedFiles) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '文件操作数量无效');
  const current = new Map(files.map(file => [file.path, file]));
  const seen = new Set();
  for (const operation of operations) {
    assertRelativePath(operation.path);
    if (!generatedRoots.some(root => operation.path.startsWith(root)) || seen.has(operation.path)) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '补丁超出生成目录或路径重复');
    seen.add(operation.path);
    const old = current.get(operation.path);
    if (!['CREATE', 'REPLACE', 'DELETE'].includes(operation.kind) || (operation.kind === 'CREATE' ? !!old : !old || old.sha256 !== operation.expectedSha256)) throw new PrototypeError('PROTOTYPE_REVISION_CONFLICT', '文件操作与原型基准不一致');
    if (operation.kind !== 'DELETE' && (typeof operation.content !== 'string' || Buffer.byteLength(operation.content) > limits.generatedFileBytes)) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '生成内容超限或无效');
  }
}
