package com._36knight.alpha.exception;

/**
 * 工作流或模型结果不可用时的业务异常，对外不暴露上游错误原文。
 *
 * @author Andersen
 */
public class AgentExecutionException extends RuntimeException {
    /**
     * 保存排障原因，由统一异常处理转换为安全响应。
     */
    public AgentExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
