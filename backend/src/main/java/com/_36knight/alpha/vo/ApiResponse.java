package com._36knight.alpha.vo;

/**
 * Alpha API 的统一响应契约，HTTP 状态码仍表达请求成功与失败。
 * @param code SUCCESS 或具体错误代码
 * @param message 可向用户展示的说明
 * @param data 成功时的业务结果，失败时为空
 * @param <T> 业务响应类型
 * @author Andersen
 */
public record ApiResponse<T>(String code, String message, T data) {
    /** 包装成功的业务结果。 */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", "操作成功", data);
    }

    /** 包装可以对外展示的错误，不携带上游异常原文。 */
    public static ApiResponse<Void> failure(String code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
