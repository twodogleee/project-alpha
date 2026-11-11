package com._36knight.alpha.agent;

/**
 * 单次工作流固定的运行上下文，不含明文密钥，不参与数据库或检查点保存。
 *
 * @param mode     demo、dashscope 或 openai-compatible，用于真实来源标记
 * @param model    本次模型名，demo 为 local-template
 * @param analyzer 已绑定本次配置的分析能力
 * @author Andersen
 */
public record AnalysisRuntime(String mode, String model, RequirementAnalyzer analyzer) {
    /**
     * 本次渲染必须沿用开始时的模式。
     */
    public boolean isDemo() {
        return "demo".equals(mode);
    }
}
