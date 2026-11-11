/** 原型 ZIP 下载响应的边界校验；JSON 错误仍按普通文本反馈，不下载伪装的错误页。@author Andersen */

/** 与后端导出上限一致，防止一次下载在浏览器无限分配内存。 */
export const prototypeExportMaxBytes = 80 * 1024 * 1024;

/** 下载名只接受 ASCII 安全 basename，拒绝路径、控制字符和非 ZIP 后缀。 */
export function prototypeExportFilename(disposition, fallback) {
  const match = /(?:^|;)\s*filename="?([^";]+)"?(?:;|$)/i.exec(disposition || '');
  const value = match?.[1]?.trim();
  return value && /^[A-Za-z0-9][A-Za-z0-9._-]{0,140}\.zip$/.test(value) ? value : fallback;
}

/** 读取过程中强制限额，Content-Length 缺失或不可信时同样受限。 */
async function boundedBlob(response, maxBytes, type) {
  const announced = Number(response.headers.get('content-length'));
  if (Number.isFinite(announced) && announced > maxBytes) { await response.body?.cancel(); throw new Error('导出文件超过允许大小'); }
  const reader = response.body?.getReader();
  if (!reader) throw new Error('导出响应为空，请重试');
  const chunks = []; let bytes = 0;
  try {
    while (true) {
      const item = await reader.read();
      if (item.done) break;
      bytes += item.value.byteLength;
      if (bytes > maxBytes) { await reader.cancel(); throw new Error('导出文件超过允许大小'); }
      chunks.push(item.value);
    }
  } finally { reader.releaseLock(); }
  return new Blob(chunks, { type });
}

/** ZIP 成功和安全业务错误分开处理；不将 HTML、宿主异常或空包交给下载器。 */
export async function prototypeExportResponse(response, fallback) {
  const type = (response.headers.get('content-type') || '').split(';')[0].trim().toLowerCase();
  if (!response.ok || type === 'application/json') {
    let body;
    try { body = JSON.parse(await (await boundedBlob(response, 256 * 1024, 'application/json')).text()); } catch { body = null; }
    const error = new Error(typeof body?.message === 'string' ? body.message.slice(0, 600) : '导出失败，请刷新修订状态后重试');
    error.code = typeof body?.code === 'string' ? body.code.slice(0, 80) : 'PROTOTYPE_EXPORT_FAILED';
    throw error;
  }
  if (type !== 'application/zip') { await response.body?.cancel(); throw new Error('导出响应不是 ZIP 文件，请检查服务后重试'); }
  const blob = await boundedBlob(response, prototypeExportMaxBytes, type);
  if (!blob.size) throw new Error('导出响应为空，请重试');
  return { blob, filename: prototypeExportFilename(response.headers.get('content-disposition'), fallback) };
}
