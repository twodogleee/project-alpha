/** 独立本机原型预览：静态快照、opaque iframe、禁止网络及浏览器持久化权限。@author Andersen */
import { readFileSync, readdirSync, lstatSync } from 'node:fs';
import { dirname, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';

/** 只服务导出包内明确的静态目录，不接受绝对路径。 */
const root = dirname(fileURLToPath(import.meta.url));
/** 已验证文件的只读内存快照，不暴露包内 PRD、源码、配置及宿主目录。 */
const files = new Map();
/** 与 Alpha 静态产物一致的类型白名单。 */
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.webp': 'image/webp', '.ico': 'image/x-icon', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.txt': 'text/plain; charset=utf-8' };
/** 全树节点和字节总量均受限，包括空目录。 */
let total = 0, nodes = 0;
/** 只读普通文件，拒绝目录链接、私有配置、源码映射和目录穿越。 */
const snapshot = (directory, prefix = '') => {
  if (++nodes > 10000) throw new Error('文件数量超过限制');
  const stat = lstatSync(directory);
  if (!stat.isDirectory() || stat.isSymbolicLink()) throw new Error('静态目录无效');
  for (const name of readdirSync(directory)) {
    if (!/^[A-Za-z0-9_-][A-Za-z0-9_.-]*$/.test(name) || name.endsWith('.') || ['node_modules', 'credentials'].includes(name.toLowerCase())) throw new Error('静态文件名无效');
    const path = prefix + name, file = resolve(directory, name), entry = lstatSync(file);
    if (entry.isSymbolicLink() || path.split('/').length > 32) throw new Error('静态路径无效');
    if (entry.isDirectory()) { snapshot(file, path + '/'); continue; }
    if (++nodes > 10000 || !entry.isFile() || !mime[extname(name)] || files.size >= 1000 || (total += entry.size) > 50 * 1024 * 1024) throw new Error('静态文件无效或超过大小限制');
    const bytes = readFileSync(file);
    if (bytes.length !== entry.size) throw new Error('静态文件已改变');
    files.set(path, bytes);
  }
};

try {
  /** 原始清单仅提供来源标签与摘要，绝不当 HTML、脚本或可执行参数使用。 */
  const manifestFile = resolve(root, 'export-manifest.json');
  const manifestStat = lstatSync(manifestFile);
  if (!manifestStat.isFile() || manifestStat.isSymbolicLink() || manifestStat.size > 4 * 1024 * 1024) throw new Error('清单无效');
  const manifest = JSON.parse(readFileSync(manifestFile));
  const selected = process.argv[2] || 'dist';
  if (selected !== 'dist' || !['source', 'static'].includes(manifest.format)) throw new Error('仅允许 dist 目录');
  snapshot(resolve(root, selected));
  if (!files.has('index.html')) throw new Error('缺少静态入口');
  if (manifest.format === 'static') {
    const expected = manifest.files.filter(entry => entry.path?.startsWith('dist/'));
    if (expected.length !== files.size) throw new Error('静态文件与清单不一致');
    for (const entry of expected) {
      const bytes = files.get(entry.path.slice(5));
      if (!bytes || bytes.length !== entry.bytes || createHash('sha256').update(bytes).digest('hex') !== entry.sha256) throw new Error('静态文件摘要不一致');
    }
  }
  /** 固定标签不插值模型或用户文本。 */
  const mode = { live: '真实模型生成', synthetic: '合成联调 · 未调用模型', demo: '模板联调 · 未调用模型' }[manifest.mode];
  if (!mode) throw new Error('来源方式无效');
  /** 当前本机来源只在监听端口确定后生成，用于 Host、CSP 及 Origin 约束。 */
  let origin = '', host = '';
  /** 所有响应共用防缓存、无引用、无浏览器敏感能力策略。 */
  const server = createServer((request, response) => {
    response.setHeader('Cache-Control', 'no-store'); response.setHeader('Referrer-Policy', 'no-referrer');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=(), payment=(), usb=(), clipboard-read=(), clipboard-write=()');
    response.setHeader('Content-Security-Policy', "default-src 'none'; frame-ancestors 'none'; sandbox");
    /** HEAD 保持正确长度但不输出正文。 */
    const send = (status, type, value) => {
      const body = Buffer.isBuffer(value) ? value : Buffer.from(value);
      response.writeHead(status, { 'Content-Type': type, 'Content-Length': body.length });
      response.end(request.method === 'HEAD' ? undefined : body);
    };
    if (request.headers.host !== host) return send(403, 'text/plain; charset=utf-8', '请使用终端显示的本机地址');
    if (!['GET', 'HEAD'].includes(request.method)) return send(405, 'text/plain; charset=utf-8', '仅支持只读预览');
    const path = request.url;
    if (typeof path !== 'string' || path.length > 1000 || !/^\/[A-Za-z0-9_./-]*$/.test(path) || path.includes('..') || path.includes('//')) return send(404, 'text/plain; charset=utf-8', '资源不存在');
    if (path === '/') {
      response.setHeader('Content-Security-Policy', `default-src 'none'; style-src 'unsafe-inline'; frame-src ${origin}; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`);
      response.setHeader('X-Frame-Options', 'DENY');
      return send(200, 'text/html; charset=utf-8', `<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Alpha 原型交付预览</title><style>*{box-sizing:border-box}body{margin:0;font:14px system-ui,sans-serif;color:#172033}header{height:64px;padding:12px 20px;background:#f8fafc;border-bottom:1px solid #cbd5e1}strong{display:block;margin-bottom:5px}small{color:#475569}iframe{width:100%;height:calc(100vh - 64px);border:0;display:block}</style></head><body><header><strong>Alpha 原型交付预览 · ${mode}</strong><small>业务动作使用模拟数据 · 刷新恢复种子 · 历史确认详见交付清单</small></header><iframe title="管理端原型" src="/content/index.html" sandbox="allow-scripts" referrerpolicy="no-referrer"></iframe></body></html>`);
    }
    if (!path.startsWith('/content/')) return send(404, 'text/plain; charset=utf-8', '资源不存在');
    const name = path.slice(9), bytes = files.get(name), destination = request.headers['sec-fetch-dest'];
    if (!bytes) return send(404, 'text/plain; charset=utf-8', '资源不存在');
    if (name.endsWith('.html') ? destination !== 'iframe' : ['document', 'iframe', 'frame', 'object', 'embed'].includes(destination)) return send(403, 'text/plain; charset=utf-8', '请通过预览入口打开');
    if (request.headers.origin && !['null', origin].includes(request.headers.origin)) return send(403, 'text/plain; charset=utf-8', '预览来源不匹配');
    response.setHeader('Access-Control-Allow-Origin', 'null'); response.setHeader('Cross-Origin-Resource-Policy', 'cross-origin');
    response.setHeader('Content-Security-Policy', `default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-src 'none'; frame-ancestors ${origin}; worker-src 'none'; sandbox allow-scripts`);
    return send(200, mime[extname(name)], bytes);
  });
  server.listen(0, '127.0.0.1', () => { host = `127.0.0.1:${server.address().port}`; origin = `http://${host}`; process.stdout.write(`原型交付预览：${origin}/\n`); });
  server.on('error', () => { process.stderr.write('本机预览服务无法启动。\n'); process.exitCode = 1; });
} catch (error) {
  process.stderr.write('预览未启动：请核对交付包、dist 目录、文件摘要和 Node.js 22+。\n');
  process.exitCode = 1;
}
