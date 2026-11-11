package com._36knight.alpha.prototype.persistence;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/** 文档内原型聚合；只保存当前指针及并发版本，不复制父对象生命周期。 @author Andersen */
@TableName("prototypes")
public class PrototypeEntity {
    /** 原型稳定身份。 */
    @TableId(type = IdType.INPUT)
    public String id;
    /** 所属项目，必须与文档一致。 */
    public String projectId;
    /** 所属 PRD 文档；每份文档最多一个原型。 */
    public String versionId;
    /** 聚合并发版本，从 1 开始。 */
    public Long revision;
    /** 下次分配的不可变修订序号，从 1 开始。 */
    public Long nextRevisionNumber;
    /** 当前可运行修订；首次成功前为空。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String currentRevisionId;
    /** 最近人工确认记录；变更当前稿不抹除历史。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String latestConfirmationId;
    /** 首次选择的原型模板身份。 */
    public String templateId;
    /** 固定模板版本，一期不原位迁移。 */
    public Long templateRevision;
    /** 原型首次创建时间。 */
    public OffsetDateTime createdAt;
    /** 聚合状态最后变更时间。 */
    public OffsetDateTime updatedAt;
}
