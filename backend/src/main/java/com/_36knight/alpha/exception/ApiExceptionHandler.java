package com._36knight.alpha.exception;

import com._36knight.alpha.vo.ApiResponse;
import com._36knight.alpha.project.ProjectException;
import com._36knight.alpha.model.ModelConfigurationException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 为输入校验和 Agent 执行失败提供统一响应，不输出需求或模型凭证。
 *
 * @author Andersen
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    /**
     * 拒绝空白或过长需求，保留一致的接口错误结构。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.failure("INVALID_INPUT", "请求字段不能为空、超限或使用无效状态，请检查输入"));
    }

    /**
     * 拒绝无法解析的请求，不把解析器原文返回客户端。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.failure("INVALID_JSON", "请求内容格式不正确"));
    }

    /**
     * 将模型或流程失败明确返回为失败，允许用户保留输入后重试。
     */
    @ExceptionHandler(AgentExecutionException.class)
    public ResponseEntity<ApiResponse<Void>> handleAgentExecution(AgentExecutionException exception) {
        String diagnosis = TaskFailureDiagnostics.report(new TaskFailureDiagnostics.Context("AGENT_HTTP", null, null, null, null, null),
            System.nanoTime(), exception);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ApiResponse.failure("AGENT_EXECUTION_FAILED", "需求处理失败；" + diagnosis));
    }

    /**
     * 项目不存在、关联错误和并发冲突均使用统一响应。
     */
    @ExceptionHandler(ProjectException.class)
    public ResponseEntity<ApiResponse<Void>> handleProject(ProjectException exception) {
        return ResponseEntity.status(exception.status()).body(ApiResponse.failure(exception.code(), exception.getMessage()));
    }

    /**
     * 模型配置冲突、输入错误及密钥/连接失败均使用安全消息。
     */
    @ExceptionHandler(ModelConfigurationException.class)
    public ResponseEntity<ApiResponse<Void>> handleModelConfiguration(ModelConfigurationException exception) {
        return ResponseEntity.status(exception.status()).body(ApiResponse.failure(exception.code(), exception.getMessage()));
    }

    /**
     * 数据库错误不输出 SQL、数据库地址或业务正文。
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleStorage(DataAccessException exception) {
        String diagnosis = TaskFailureDiagnostics.report(new TaskFailureDiagnostics.Context("STORAGE_HTTP", null, null, null, null, null),
            System.nanoTime(), exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiResponse.failure("STORAGE_UNAVAILABLE", "项目存储暂时不可用，请保留编辑内容后重试；" + diagnosis));
    }

    /**
     * 历史版本路径参数不合法时返回安全的输入错误。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handlePathInput(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.failure("INVALID_INPUT", "项目版本格式不正确"));
    }

    /**
     * 未预期运行异常使用同一诊断编号，不让默认错误页或日志泄漏异常原文。
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(RuntimeException exception) {
        String diagnosis = TaskFailureDiagnostics.report(new TaskFailureDiagnostics.Context("HTTP", null, null, null, null, null),
            System.nanoTime(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.failure("INTERNAL_ERROR", "服务处理失败；" + diagnosis));
    }
}
