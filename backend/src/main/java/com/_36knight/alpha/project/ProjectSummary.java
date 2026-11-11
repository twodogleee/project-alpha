package com._36knight.alpha.project;

import java.time.Instant;

/**
 * 项目列表摘要，不读取或返回全部需求正文。
 * @param id 项目标识
 * @param name 项目名称
 * @param revision 当前保存版本
 * @param updatedAt 最近保存时间
 * @author Andersen
 */
public record ProjectSummary(String id, String name, long revision, Instant updatedAt) { }
