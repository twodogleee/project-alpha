package com._36knight.alpha.prototype;

/** 模型边界的固定诊断，不携带上游正文、请求内容、密钥或原始异常链。@author Andersen */
public final class PrototypeModelException extends RuntimeException {
    /** 平台允许持久化的稳定错误分类。 */
    private final String code;

    /** 仅由本包选择固定分类与说明，禁止传入模型正文构建异常。 */
    PrototypeModelException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    /** 返回固定分类用于任务失败状态，不返回底层异常细节。 */
    public String code() {
        return code;
    }
}
