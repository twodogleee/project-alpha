package com._36knight.alpha.model;

import com._36knight.alpha.agent.RequirementAnalyzer;

/**
 * 模型平台适配入口，测试可以替换工厂而不冒充真实平台验收。@author Andersen
 */
public interface ModelAnalyzerFactory {
    /**
     * 创建绑定指定平台、模型和凭证的分析器，不执行外部调用。
     */
    RequirementAnalyzer create(ModelConfigurationSettings settings);
}
