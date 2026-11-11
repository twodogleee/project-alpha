package com._36knight.alpha.prototype;

/** Worker 失败只携带固定分类及有界内部诊断，诊断不得持久化或公开返回。@author Andersen */
public final class PrototypeBuildException extends RuntimeException {
    /** 可用于业务任务终态的安全分类。 */
    private final String code;
    /** 仅供本次修复模型参考的编译/验证文本。 */
    private final String diagnostic;

    /** 不附带可能包含宿主路径或凭证的异常 cause。 */
    public PrototypeBuildException(String code, String diagnostic) {
        super(code);
        this.code = code;
        this.diagnostic = diagnostic == null ? "" : diagnostic.substring(0, Math.min(diagnostic.length(), 12000));
    }
    /** 返回固定错误分类。 */
    public String code() { return code; }
    /** 返回仅限执行内部使用的有界诊断。 */
    public String diagnostic() { return diagnostic; }
    /** 环境、资源和中断故障不消耗模型修复轮次。 */
    public boolean repairable() {
        return java.util.Set.of("PROTOTYPE_FILE_REJECTED", "PROTOTYPE_BUILD_FAILED", "PROTOTYPE_PREVIEW_FAILED").contains(code);
    }
}
