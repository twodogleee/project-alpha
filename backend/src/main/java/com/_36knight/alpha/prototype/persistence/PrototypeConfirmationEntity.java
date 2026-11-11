package com._36knight.alpha.prototype.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/** 人工确认的不可变记录；仅确认当前精确修订及当前有效 PRD 定稿。 @author Andersen */
@TableName("prototype_confirmations")
public class PrototypeConfirmationEntity {
    /** 人工确认身份。 */
    @TableId(type = IdType.INPUT)
    public String id;
    /** 所属原型。 */
    public String prototypeId;
    /** 用于校验确认基线与原型属于同一文档。 */
    public String versionId;
    /** 同原型确切可运行修订。 */
    public String revisionId;
    /** 确认时有效的同文档 PRD 定稿。 */
    public String prdBaselineId;
    /** 与该修订完整范围一致的确认快照。 */
    public String scopeJson;
    /** 同原型内人工确认幂等键。 */
    public String requestId;
    /** 原始确认命令摘要。 */
    public String commandHash;
    /** 人工确认时间。 */
    public OffsetDateTime createdAt;
}
