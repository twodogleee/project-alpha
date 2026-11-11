/** 封存与预览共用同一静态产物契约，防止发布后才发现服务拒绝文件。@author Andersen */
import { extname } from 'node:path';
import { PrototypeError } from './errors.mjs';

/** 明确的公开资源格式，源码映射与可执行文档不属于预览产物。 */
export const STATIC_MIME_TYPES = Object.freeze({
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.gif': 'image/gif', '.ico': 'image/x-icon',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf',
});
/** 统一文件数量限制。 */
export const MAX_STATIC_FILES = 1000;
/** 统一静态字节限制。 */
export const MAX_STATIC_BYTES = 50 * 1024 * 1024;

/** 文件路径必须能被只解码一次的预览协议无歧义访问；目录校验不要求扩展名。 */
export function assertStaticPath(path, { directory = false } = {}) {
  if (typeof path !== 'string' || !path || /[\\\x00-\x20\x7f%?#:]/.test(path)
    || path.split('/').some(part => !part || part.startsWith('.'))
    || path.split('/').length > (directory ? 12 : 13)
    || (!directory && !STATIC_MIME_TYPES[extname(path)])) {
    throw new PrototypeError('PROTOTYPE_FILE_REJECTED', 'PROTOTYPE_ARTIFACT_REJECTED: 静态产物路径或格式不受支持');
  }
  return path;
}
