package com._36knight.alpha.model;

import org.springframework.http.HttpStatus;

/**
 * 模型配置的安全业务错误，不携带凭证或上游正文。@author Andersen
 */
public class ModelConfigurationException extends RuntimeException {
    /**
     * 接口需要返回的业务状态。
     */
    private final HttpStatus status;
    /**
     * 稳定错误代码，供页面区分冲突和连接失败。
     */
    private final String code;

    /**
     * 只接受可公开展示的消息。
     */
    public ModelConfigurationException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * 返回业务状态。
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * 返回稳定错误代码。
     */
    public String code() {
        return code;
    }

    /**
     * 防止多个窗口静默覆盖配置。
     */
    public static ModelConfigurationException conflict() {
        return new ModelConfigurationException(HttpStatus.CONFLICT, "MODEL_CONFIG_CONFLICT", "模型配置已有新版本，请保留编辑并重新载入");
    }

    /**
     * 配置内容无效时不回显请求。
     */
    public static ModelConfigurationException invalid(String message) {
        return new ModelConfigurationException(HttpStatus.BAD_REQUEST, "INVALID_MODEL_CONFIG", message);
    }
}
