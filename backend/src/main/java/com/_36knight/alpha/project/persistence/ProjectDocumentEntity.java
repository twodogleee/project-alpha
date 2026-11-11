package com._36knight.alpha.project.persistence;

import com.baomidou.mybatisplus.annotation.*;
import java.time.OffsetDateTime;
import org.apache.ibatis.type.JdbcType;

/**
 * 项目当前状态数据库映射，业务对象与存储实体分别维护。
 * @author Andersen
 */
@TableName(value = "project_documents", autoResultMap = true)
public class ProjectDocumentEntity {
    /** 保留业务 UUID，不由 Mapper 重新分配。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    /** 项目摘要名称。 */
    private String name;
    /** 整份文档并发版本，更新时必须校验预期版本。 */
    private Long revision;
    /** 时区明确的保存时间，对外仍返回 Instant。 */
    private OffsetDateTime updatedAt;
    /** 完整 JSON 快照，仅在需要正文时读取。 */
    @TableField(value = "document_json", typeHandler = JsonDocumentTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String documentJson;

    /** MyBatis 反射构造入口。 */
    public ProjectDocumentEntity() { }
    /** 获取业务 ID。 */
    public String getId() { return id; }
    /** 写入业务 ID。 */
    public void setId(String value) { id = value; }
    /** 获取项目名称。 */
    public String getName() { return name; }
    /** 写入项目名称。 */
    public void setName(String value) { name = value; }
    /** 获取文档版本。 */
    public Long getRevision() { return revision; }
    /** 写入文档版本。 */
    public void setRevision(Long value) { revision = value; }
    /** 获取保存时间。 */
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    /** 写入保存时间。 */
    public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }
    /** 获取快照 JSON，不用于日志。 */
    public String getDocumentJson() { return documentJson; }
    /** 写入快照 JSON。 */
    public void setDocumentJson(String value) { documentJson = value; }
}
