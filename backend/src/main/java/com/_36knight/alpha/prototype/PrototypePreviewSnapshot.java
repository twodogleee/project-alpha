package com._36knight.alpha.prototype;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** 预览静态文件的不可变内存快照；不泄露可变字节数组，不再从磁盘提供资源。@author Andersen */
public final class PrototypePreviewSnapshot {
    /** 相对文件名映射到只由本实例拥有的内容。 */
    private final Map<String, byte[]> files;
    /** 资源内容总量，单位字节，用于会话内存配额。 */
    private final long bytes;

    /** 复制调用方数组，之后磁盘及调用方缓冲区变更不能影响已申请的预览。 */
    public PrototypePreviewSnapshot(Map<String, byte[]> source) {
        Map<String, byte[]> copy = new TreeMap<>();
        source.forEach((name, content) -> copy.put(name, content.clone()));
        files = Map.copyOf(copy);
        bytes = files.values().stream().mapToLong(content -> content.length).sum();
    }

    /** 资源总量只读，不包含按请求产生的桥接 HTML。 */
    public long bytes() { return bytes; }

    /** 未命中文件返回 -1，避免额外复制大型二进制文件。 */
    public long size(String name) { byte[] content = files.get(name); return content == null ? -1 : content.length; }

    /** HTML 入口以 UTF-8 解释，其他二进制资源不会经过文本转换。 */
    public String text(String name) { return new String(files.get(name), StandardCharsets.UTF_8); }

    /** 将内部字节发送到响应流，调用方无法取得数组并改变其他请求的内容。 */
    public void write(String name, OutputStream output) throws IOException {
        byte[] content = files.get(name), buffer = new byte[Math.min(8192, content.length)];
        for (int offset = 0; offset < content.length; offset += buffer.length) {
            int count = Math.min(buffer.length, content.length - offset);
            System.arraycopy(content, offset, buffer, 0, count);
            output.write(buffer, 0, count);
        }
    }
}
