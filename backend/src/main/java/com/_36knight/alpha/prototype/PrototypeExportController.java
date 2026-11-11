package com._36knight.alpha.prototype;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 指定修订的 ZIP 下载响应；归属、文件核验和内容组装全部交给应用服务。@author Andersen */
@RestController
@RequestMapping("/api/alpha/workspace/versions/{versionId}/prototype/revisions/{revisionId}/export")
public class PrototypeExportController {
    /** 有界导出服务，下载结束释放临时内存槽。 */
    private final PrototypeExportService exports;
    /** 注入服务，不在接口层访问封存目录。 */
    public PrototypeExportController(PrototypeExportService exports) { this.exports = exports; }
    /** 校验成功后发送 ZIP；失败发生在写入响应头前，沿用统一 JSON 异常契约。 */
    @GetMapping
    public void download(@PathVariable String versionId, @PathVariable String revisionId,
            @RequestParam(required = false) String format, HttpServletResponse response) throws IOException {
        try (var archive = exports.open(versionId, revisionId, format)) {
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", ContentDisposition.attachment().filename(archive.filename()).build().toString());
            response.setHeader("Cache-Control", "no-store"); response.setHeader("X-Content-Type-Options", "nosniff");
            archive.writeTo(response.getOutputStream());
        }
    }
}
