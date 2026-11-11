package com._36knight.alpha.vo;

import java.util.List;

/**
 * 工作台可读取的非敏感运行信息。
 * @param application 项目标识
 * @param aiMode 当前模型模式
 * @param model 当前云模型名称，联调模式为 local-template
 * @param stages 已接通的工作流阶段
 * @author Andersen
 */
public record RuntimeInfoResponse(String application, String aiMode, String model, List<String> stages) {
}
