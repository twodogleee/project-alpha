package com._36knight.alpha.model;

/**
 * 测试结果不包含模型生成正文或凭证，测试不会保存表单。
 *
 * @param connected true 表示真实接口返回有效结构化结果；demo 为 false
 * @param mode      被测试的表单模式
 * @param model     被测试的模型
 * @param message   对用户公开的结果说明
 * @author Andersen
 */
public record ModelConnectionResult(boolean connected, String mode, String model, String message) {
}
