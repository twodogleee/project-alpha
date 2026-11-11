package com._36knight.alpha.project;

import org.springframework.http.HttpStatus;

/**
 * 可以向客户端展示的项目业务错误，不含 SQL、输入正文或上游异常。
 * @author Andersen
 */
public class ProjectException extends RuntimeException {
    /** HTTP 业务状态。 */
    private final HttpStatus status;
    /** 稳定接口错误代码。 */
    private final String code;

    /** 构造安全的业务错误消息。 */
    public ProjectException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** 返回业务 HTTP 状态。 */
    public HttpStatus status() { return status; }
    /** 返回接口错误代码。 */
    public String code() { return code; }

    /** 返回统一的版本冲突，不自动覆盖或合并。 */
    public static ProjectException conflict() {
        return new ProjectException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "项目已有新版本，请保留编辑内容并重新载入后处理");
    }
}
