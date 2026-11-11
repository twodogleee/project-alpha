package com._36knight.alpha.project.persistence;

import com.baomidou.mybatisplus.annotation.*;
import java.time.OffsetDateTime;
import org.apache.ibatis.type.JdbcType;

/**
 * 不可变历史数据库映射；存储主键与项目内版本唯一约束分别管理。
 * @author Andersen
 */
@TableName(value = "project_versions", autoResultMap = true)
public class ProjectVersionEntity {
    /** 历史记录存储 ID，不影响业务文档内的稳定 ID。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    /** 历史所属项目。 */
    private String projectId;
    /** 项目内保存版本。 */
    private Long revision;
    /** 版本保存时间。 */
    private OffsetDateTime updatedAt;
    /** 历史 JSON 与导出正文不随当前文档变化。 */
    @TableField(value = "document_json", typeHandler = JsonDocumentTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String documentJson;

    /** MyBatis 反射构造入口。 */
    public ProjectVersionEntity() { }
    /** 获取历史存储 ID。 */
    public String getId() { return id; }
    /** 写入历史存储 ID。 */
    public void setId(String value) { id = value; }
    /** 获取项目 ID。 */
    public String getProjectId() { return projectId; }
    /** 写入项目 ID。 */
    public void setProjectId(String value) { projectId = value; }
    /** 获取保存版本。 */
    public Long getRevision() { return revision; }
    /** 写入保存版本。 */
    public void setRevision(Long value) { revision = value; }
    /** 获取版本时间。 */
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    /** 写入版本时间。 */
    public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }
    /** 获取历史快照，不用于日志。 */
    public String getDocumentJson() { return documentJson; }
    /** 写入历史快照。 */
    public void setDocumentJson(String value) { documentJson = value; }
}
