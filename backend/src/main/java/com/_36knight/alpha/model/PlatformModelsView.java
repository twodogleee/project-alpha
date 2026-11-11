package com._36knight.alpha.model;

import java.util.List;

/**
 * 临时上游目录，只返回可保存的模型标识，不回传原始正文或凭证。
 * @param revision 获取前校验的数据库目录版本，不代表上游版本
 * @param models 去重排序后的模型标识，空数组表示平台未返回可用模型
 * @author Andersen
 */
public record PlatformModelsView(long revision, List<String> models) { }
