/**
 * 本地联调原型的双源预览服务；只读取已封存静态产物，不提供文件写入或业务代理。
 * P3 接入时由 isAvailable 回调校验文档读取资格；本模块不承担业务持久化。
 * @author Andersen
 */
import { createServer } from 'node:http';
import { lstat, readdir, readFile, realpath } from 'node:fs/promises';
import { extname, resolve, sep } from 'node:path';
import { randomBytes } from 'node:crypto';
import { STATIC_MIME_TYPES, MAX_STATIC_FILES, MAX_STATIC_BYTES, assertStaticPath } from './static-policy.mjs';

/** 独立预览会话最长保留一小时；默认十五分钟。 */
const MAX_TTL_MS = 60 * 60 * 1000;

/** HTML 只插入服务器生成的标识及地址；外部文本以属性转义保护。 */
function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[character]);
}

/** 将可信配置嵌入脚本时禁止提前结束 script 元素。 */
function scriptJson(value) {
  return JSON.stringify(value).replace(/</g, '\\u003c').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
}

/** 启动前建立有界内存快照；后续修改目录不会改变正在预览的修订。 */
async function snapshotArtifact(artifactDirectory) {
  /** 路径仅由可信调用方传入，不接受 HTTP 参数或模型路径。 */
  const root = resolve(artifactDirectory);
  /** 根目录也拒绝符号链接，不能将预览入口映射到宿主资料。 */
  const rootInfo = await lstat(root);
  if (!rootInfo.isDirectory() || rootInfo.isSymbolicLink() || await realpath(root) !== root) {
    throw new Error('PROTOTYPE_ARTIFACT_REJECTED: 产物必须位于真实目录中');
  }
  /** 相对路径映射到独立 Buffer，仅服务本次验证快照。 */
  const files = new Map();
  /** 累计字节数在读取前和读取后都校验。 */
  let totalBytes = 0;

  /** 只递归常规目录并拒绝特殊文件、隐藏内容及未知格式。 */
  async function visit(directory, relativeDirectory = '', depth = 0) {
    if (depth > 12) throw new Error('PROTOTYPE_ARTIFACT_REJECTED: 产物目录过深');
    /** 文件系统顺序不影响预览清单；排序便于诊断和确定性测试。 */
    const entries = (await readdir(directory)).sort();
    for (const name of entries) {
      if (name.startsWith('.') || /[\\\x00-\x1f%?#]/.test(name)) throw new Error('PROTOTYPE_ARTIFACT_REJECTED: 非法文件名');
      /** 相对路径只由已检查的目录项组成。 */
      const relativePath = relativeDirectory ? `${relativeDirectory}/${name}` : name;
      /** 绝对路径解析后仍须位于固定根内。 */
      const absolutePath = resolve(directory, name);
      /** lstat 拒绝链接，不沿链接递归。 */
      const info = await lstat(absolutePath);
      if (info.isSymbolicLink() || !absolutePath.startsWith(root + sep) || await realpath(absolutePath) !== absolutePath) {
        throw new Error('PROTOTYPE_ARTIFACT_REJECTED: 禁止符号链接');
      }
      if (info.isDirectory()) {
        assertStaticPath(relativePath, { directory: true });
        await visit(absolutePath, relativePath, depth + 1);
        continue;
      }
      if (!info.isFile()) throw new Error('PROTOTYPE_ARTIFACT_REJECTED: 不支持的产物格式');
      assertStaticPath(relativePath);
      if (files.size >= MAX_STATIC_FILES || info.size > MAX_STATIC_BYTES - totalBytes) {
        throw new Error('PROTOTYPE_ARTIFACT_REJECTED: 产物超过限额');
      }
      /** 服务独立内存副本，避免请求期间被文件替换。 */
      const content = await readFile(absolutePath);
      totalBytes += content.length;
      if (totalBytes > MAX_STATIC_BYTES || content.length !== info.size) {
        throw new Error('PROTOTYPE_ARTIFACT_REJECTED: 产物读取期间变化');
      }
      files.set(relativePath, Object.freeze({ content, mime: STATIC_MIME_TYPES[extname(name).toLowerCase()] }));
    }
  }
  await visit(root);
  if (!files.has('index.html')) throw new Error('PROTOTYPE_ARTIFACT_REJECTED: 缺少 index.html');
  return files;
}

/** Host 必须等于本服务的固定 loopback 地址，阻止 DNS rebinding。 */
function acceptsHost(request, origin) {
  return request.headers.host === new URL(origin).host;
}

/** 在 URL 规范化前拒绝点段、编码分隔符、重复编码和绝对请求目标。 */
function requestPath(request) {
  /** 原始 request-target 不允许 query 承载隐含动作。 */
  const raw = request.url || '';
  if (!raw.startsWith('/') || raw.startsWith('//') || /[\\?#\x00-\x20]/.test(raw)) return null;
  /** 只解码一次；剩余百分号一律不支持，避免双重解码差异。 */
  let decoded;
  try { decoded = decodeURIComponent(raw); } catch { return null; }
  if (/[\\%\x00-\x20]/.test(decoded) || /%2f|%5c/i.test(raw)) return null;
  if (decoded.split('/').some((part) => part === '.' || part === '..')) return null;
  return decoded;
}

/** 所有响应禁缓存和 referrer，错误响应同样不泄露令牌或路径。 */
function setCommonHeaders(response) {
  response.setHeader('Cache-Control', 'no-store');
  response.setHeader('Referrer-Policy', 'no-referrer');
  response.setHeader('X-Content-Type-Options', 'nosniff');
  response.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=(), payment=(), usb=(), serial=(), bluetooth=(), clipboard-read=(), clipboard-write=()');
}

/** 按 GET/HEAD 语义返回受控内容，不显示内部异常。 */
function respond(request, response, status, body, contentType = 'text/plain; charset=utf-8') {
  response.statusCode = status;
  response.setHeader('Content-Type', contentType);
  response.end(request.method === 'HEAD' ? undefined : body);
}

/** 消息身份、源窗口与 opaque origin 同时核对，不接受额外的命令字段。 */
export function isPreviewMessage(event, expected) {
  if (!event || event.source !== expected.source || event.origin !== 'null') return false;
  /** 只解析无嵌套对象的受限消息，不执行任何载荷。 */
  const value = event.data;
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  if (Object.keys(value).some((key) => !['type', 'sessionId', 'revisionId', 'path', 'message'].includes(key))) return false;
  if (!['READY', 'NAVIGATED', 'SELECT_PAGE', 'ERROR'].includes(value.type)) return false;
  if (value.sessionId !== expected.sessionId || value.revisionId !== expected.revisionId) return false;
  if (value.path !== undefined && (typeof value.path !== 'string' || value.path.length > 220 || !value.path.startsWith('/'))) return false;
  if (value.message !== undefined && (typeof value.message !== 'string' || value.message.length > 300)) return false;
  return true;
}

/** 平台桥接只上报就绪、导航和错误；History 包装保留原返回值和异常语义。 */
function bridgeScript(configuration) {
  return `(() => {
    const config = ${scriptJson(configuration)};
    const report = (type, detail = {}) => {
      if (window.parent !== window) window.parent.postMessage({ type, sessionId: config.sessionId, revisionId: config.revisionId, ...detail }, config.hostOrigin);
    };
    const path = () => (location.hash.slice(1) || '/').slice(0, 220);
    let ready = false;
    const navigated = () => { if (ready) report('NAVIGATED', { path: path() }); };
    for (const name of ['pushState', 'replaceState']) {
      const original = history[name];
      history[name] = function (...args) {
        const result = Reflect.apply(original, this, args);
        navigated();
        return result;
      };
    }
    addEventListener('DOMContentLoaded', () => { ready = true; report('READY', { path: path() }); }, { once: true });
    addEventListener('hashchange', navigated);
    addEventListener('popstate', navigated);
    addEventListener('error', () => report('ERROR', { message: '预览页面发生运行错误，请查看构建验证记录。' }));
    addEventListener('unhandledrejection', () => report('ERROR', { message: '预览页面发生异步错误，请查看构建验证记录。' }));
  })();`;
}

/** 预览页面始终由平台包装；外层 frame-src 防止子文档自导航到外部地址。 */
function hostDocument({ frameUrl, previewUrl, previewOrigin, sessionId, revisionId, nonce, expiresAt }) {
  /** 生成桥接接收器的配置，不注入业务页面或模型文本。 */
  const configuration = scriptJson({ sessionId, revisionId });
  /** 本地中文工作台明确显示北京时间，避免将 UTC 时间误读为本机到期时间。 */
  const expiryLabel = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(expiresAt));
  return `<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Alpha 原型模板联调</title><style nonce="${nonce}">
    *{box-sizing:border-box}body{margin:0;background:#f1f5f9;color:#0f172a;font:14px system-ui,-apple-system,sans-serif}header{height:76px;padding:12px 24px;display:flex;align-items:center;gap:24px;background:white;border-bottom:1px solid #cbd5e1}h1{font-size:17px;margin:0 0 5px}p{margin:0;color:#64748b;font-size:12px}nav{margin-left:auto;display:flex;gap:18px;align-items:center}a{color:#1d4ed8}iframe{display:block;width:100%;height:calc(100vh - 76px);border:0;background:white}#preview-status{font-size:12px;color:#475569}
  </style></head><body><header><div><h1>管理端原型 · 本地模板联调</h1><p>未调用大模型 · 模拟数据仅保留于本次预览 · 会话到期 ${escapeHtml(expiryLabel)}（北京时间）</p></div><nav><span id="preview-status" role="status">正在加载预览</span><a data-testid="open-window" href="${escapeHtml(previewUrl)}" target="_blank" rel="noopener noreferrer">独立窗口</a></nav></header><iframe id="prototype-frame" title="管理端原型预览" src="${escapeHtml(frameUrl)}" sandbox="allow-scripts" referrerpolicy="no-referrer"></iframe><script nonce="${nonce}">
    const config = ${configuration};
    const frame = document.getElementById('prototype-frame');
    const status = document.getElementById('preview-status');
    const accepts = ${isPreviewMessage.toString()};
    addEventListener('message', (event) => {
      if (!accepts(event, { ...config, source: frame.contentWindow })) return;
      status.textContent = event.data.type === 'ERROR' ? event.data.message || '预览错误' : '预览就绪' + (event.data.path ? ' · ' + event.data.path : '');
    });
  </script></body></html>`;
}

/** 只监听明确的 loopback，不允许配置公网地址。 */
function listen(server, host, port) {
  return new Promise((resolveListen, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => {
      server.removeListener('error', reject);
      resolveListen(`http://${host}:${server.address().port}`);
    });
  });
}

/** 停止连接以便测试与 CLI 信号处理可靠退出。 */
function closeServer(server) {
  return new Promise((resolveClose) => {
    server.closeAllConnections();
    server.close(() => resolveClose());
  });
}

/**
 * 启动一个固定修订的双源预览会话。artifactDirectory 必须已由构建服务校验。
 * isAvailable 在每个页面/资源请求执行；未来用于校验父文档状态，失败关闭访问。
 * 返回的 previewUrl 是独立窗口包装页，frameUrl 仅供受 sandbox 限制的内层使用。
 */
export async function startPreview({ artifactDirectory, revisionId = 'fixture', host = '127.0.0.1', hostPort = 18181, previewPort = 18182, ttlMs = 15 * 60 * 1000, isAvailable = async () => true, now = () => Date.now() }) {
  if (host !== '127.0.0.1') throw new Error('PROTOTYPE_PREVIEW_REJECTED: 只允许 IPv4 loopback');
  if (![hostPort, previewPort].every((port) => Number.isInteger(port) && port >= 0 && port <= 65535)) throw new Error('PROTOTYPE_PREVIEW_REJECTED: 端口无效');
  if (hostPort !== 0 && hostPort === previewPort) throw new Error('PROTOTYPE_PREVIEW_REJECTED: 预览和工作台必须不同源');
  if (!Number.isSafeInteger(ttlMs) || ttlMs < 1 || ttlMs > MAX_TTL_MS) throw new Error('PROTOTYPE_PREVIEW_REJECTED: 会话期限无效');
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(revisionId)) throw new Error('PROTOTYPE_PREVIEW_REJECTED: 修订标识无效');
  if (typeof isAvailable !== 'function' || typeof now !== 'function') throw new Error('PROTOTYPE_PREVIEW_REJECTED: 读取资格检查无效');
  /** 产物载入后保持不变，即使磁盘上的路径随后被替换。 */
  const files = await snapshotArtifact(artifactDirectory);
  /** URL 令牌仅用于一个修订、一个短期会话，不复用为用户身份。 */
  const sessionId = randomBytes(24).toString('hex');
  /** nonce 独立于 URL 令牌，保护平台插入的内联桥接。 */
  const nonce = randomBytes(24).toString('base64');
  /** 会话到期时间固定，不因资源请求续期。 */
  const expiresAt = now() + ttlMs;
  /** 主动撤销后所有后续请求都拒绝。 */
  let revoked = false;
  /** 两个服务启动完成后才允许处理资源。 */
  let ready = false;
  /** 外层 origin 由实际绑定端口确定，测试允许系统分配端口。 */
  let hostOrigin;
  /** 原型 origin 与外层严格分开。 */
  let previewOrigin;

  /** 每次请求重新核对父对象资格，回调错误按拒绝访问处理。 */
  async function available() {
    if (!ready || revoked || now() >= expiresAt) return false;
    try { return await isAvailable({ sessionId, revisionId, expiresAt }) === true; } catch { return false; }
  }

  /** 共用请求检查不允许跨源写入、异常 Host 或未授权文件路径。 */
  async function authorize(request, response, origin) {
    setCommonHeaders(response);
    if (!acceptsHost(request, origin)) { respond(request, response, 403, '预览地址无效'); return null; }
    if (!['GET', 'HEAD'].includes(request.method)) { response.setHeader('Allow', 'GET, HEAD'); respond(request, response, 405, '不支持的请求'); return null; }
    /** URL 解析不使用会消除点段的 URL.pathname。 */
    const path = requestPath(request);
    if (!path) { respond(request, response, 400, '无效路径'); return null; }
    if (!await available()) { respond(request, response, 410, '预览会话已过期或已撤销'); return null; }
    return path;
  }

  /** 外层只提供平台生成的包装页，任何业务代码都不在该源运行。 */
  const hostServer = createServer(async (request, response) => {
    try {
      /** 只有两个精确路由能读取同一预览会话。 */
      const path = await authorize(request, response, hostOrigin);
      if (path === null) return;
      if (path !== `/preview/${sessionId}` && path !== `/window/${sessionId}`) { respond(request, response, 404, '预览不存在'); return; }
      response.setHeader('Content-Security-Policy', `default-src 'none'; script-src 'nonce-${nonce}'; style-src 'nonce-${nonce}'; frame-src ${previewOrigin}; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; worker-src 'none'`);
      respond(request, response, 200, hostDocument({ frameUrl: `${previewOrigin}/p/${sessionId}/index.html`, previewUrl: `${hostOrigin}/window/${sessionId}`, previewOrigin, sessionId, revisionId, nonce, expiresAt }), 'text/html; charset=utf-8');
    } catch { if (!response.headersSent) respond(request, response, 503, '预览暂不可用'); else response.end(); }
  });

  /** 内层只读取带会话令牌的静态快照，不回退任意路径到 index.html。 */
  const previewServer = createServer(async (request, response) => {
    try {
      /** 请求必须携带当前短期会话路径。 */
      const path = await authorize(request, response, previewOrigin);
      if (path === null) return;
      /** 静态资源不对未授权源开放目录，不接收任何 credentials。 */
      const prefix = `/p/${sessionId}/`;
      if (!path.startsWith(prefix)) { respond(request, response, 404, '预览不存在'); return; }
      /** 只从内存清单获取文件，HTTP 路径不参与磁盘读取。 */
      const file = files.get(path.slice(prefix.length));
      if (!file) { respond(request, response, 404, '资源不存在'); return; }
      // 顶层原型缺少包装层 frame-src；必须通过受控 iframe 加载 HTML。
      if (file.mime.startsWith('text/html') && request.headers['sec-fetch-dest'] !== 'iframe') {
        respond(request, response, 403, '请从原型预览窗口打开');
        return;
      }
      response.setHeader('Access-Control-Allow-Origin', 'null');
      response.setHeader('Cross-Origin-Resource-Policy', 'cross-origin');
      response.setHeader('Content-Security-Policy', `default-src 'none'; script-src 'self' 'nonce-${nonce}'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-src 'none'; frame-ancestors ${hostOrigin}; worker-src 'none'; sandbox allow-scripts`);
      /** HTML 统一插入平台桥接，不接受模型提供的父页面控制协议。 */
      let body = file.content;
      if (file.mime.startsWith('text/html')) {
        /** 外部文本不在宿主应用渲染，只在本隔离 HTML 文档中运行。 */
        const html = file.content.toString('utf8');
        /** nonce 在服务端生成，不从构建输入中读取。 */
        const bridge = `<script nonce="${nonce}">${bridgeScript({ hostOrigin, sessionId, revisionId })}</script>`;
        body = html.includes('<head>') ? html.replace('<head>', `<head>${bridge}`) : bridge + html;
      }
      respond(request, response, 200, body, file.mime);
    } catch { if (!response.headersSent) respond(request, response, 503, '预览暂不可用'); else response.end(); }
  });

  try {
    hostOrigin = await listen(hostServer, host, hostPort);
    previewOrigin = await listen(previewServer, host, previewPort);
    ready = true;
  } catch (error) {
    await Promise.all([closeServer(hostServer), closeServer(previewServer)]);
    throw error;
  }
  return Object.freeze({
    /** 含说明和操作入口的主包装页。 */
    hostUrl: `${hostOrigin}/preview/${sessionId}`,
    /** 独立窗口仍经过包装层 frame-src 与 opaque sandbox。 */
    previewUrl: `${hostOrigin}/window/${sessionId}`,
    /** iframe 内层资源入口，不作为独立窗口入口。 */
    frameUrl: `${previewOrigin}/p/${sessionId}/index.html`,
    /** 测试及服务端会话映射的短期身份。 */
    sessionId,
    /** 撤销当前会话，不删除封存产物。 */
    revokeSession() { revoked = true; },
    /** 关闭本次会话的两个监听端口。 */
    async close() { revoked = true; await Promise.all([closeServer(hostServer), closeServer(previewServer)]); },
  });
}
