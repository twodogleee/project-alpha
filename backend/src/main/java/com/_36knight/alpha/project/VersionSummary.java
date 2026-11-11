package com._36knight.alpha.project;

import java.time.Instant;

/**
 * 不可变版本摘要；正文按需读取。
 * @param revision 保存版本
 * @param updatedAt 保存时间
 * @author Andersen
 */
public record VersionSummary(long revision, Instant updatedAt) { }
