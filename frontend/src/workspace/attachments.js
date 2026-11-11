/** 会话文本附件读取与有界校验；不执行 Markdown、HTML 或文件内指令。@author Andersen */
/** 本轮最多 3 份，每份 256 KB / 10 万字符，合计 15 万字符，与服务端一致。 */
export const attachmentLimits = { count: 3, bytes: 262144, characters: 100000, totalCharacters: 150000 };
/** 校验完整批次，不静默截断或覆盖同名资料。 */
export function validateAttachments(files) {
  if (files.length > attachmentLimits.count) throw new Error('每轮最多添加 3 份附件。');
  const names = new Set(); let total = 0;
  for (const file of files) {
    if (!file.name?.trim() || file.name.length > 180 || !/\.(md|txt)$/i.test(file.name) || /[/\\\x00-\x1f\x7f-\x9f]/.test(file.name)) throw new Error('仅支持文件名不超过 180 字符的 Markdown（.md）和 TXT 文件。');
    if (names.has(file.name)) throw new Error(`已添加同名文件：${file.name}`);
    names.add(file.name);
    if (!file.content?.trim()) throw new Error(`${file.name} 没有可分析的文本。`);
    if (file.content.length > attachmentLimits.characters || new TextEncoder().encode(file.content).length > attachmentLimits.bytes) throw new Error(`${file.name} 超过单份 256 KB 或 10 万字符的限制。`);
    if (/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f]/.test(file.content)) throw new Error(`${file.name} 不是有效的纯文本文件。`);
    total += file.content.length;
  }
  if (total > attachmentLimits.totalCharacters) throw new Error('附件正文合计不能超过 15 万字符，请拆分资料。');
  return files;
}
/** 从用户明确选择的本地文件读取 UTF-8 原文；全部通过后再更新待发送列表。 */
export async function readAttachments(selected, existing = []) {
  if (selected.length + existing.length > attachmentLimits.count) throw new Error('每轮最多添加 3 份附件。');
  const files = [...existing];
  for (const file of selected) {
    if (file.size > attachmentLimits.bytes) throw new Error(`${file.name} 超过 256 KB，请拆分文件。`);
    if (!/\.(md|txt)$/i.test(file.name)) throw new Error('当前支持 Markdown（.md）和 TXT 文本附件。');
    let content;
    try { content = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer()); }
    catch { throw new Error(`${file.name} 无法按 UTF-8 读取，请转换编码后重试。`); }
    files.push({ name: file.name, content });
  }
  return validateAttachments(files);
}
