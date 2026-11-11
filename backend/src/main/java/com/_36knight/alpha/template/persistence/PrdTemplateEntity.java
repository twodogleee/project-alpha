package com._36knight.alpha.template.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import java.time.OffsetDateTime;

/** prd_templates 的持久化映射，不直接作为接口响应。@author Andersen */
@TableName("prd_templates")
public class PrdTemplateEntity {
    /** 自定义模板稳定标识。 */
    @TableId(type=IdType.INPUT)
    public String id;
    /** 草稿管理乐观锁，每次变更递增。 */
    public long revision;
    /** 最后已发布版本，零表示尚未发布。 */
    public int latestPublishedRevision;
    /** 软删除只限制新选用与管理写入，草稿、已发布定义和既有 PRD 继续保留。 */
    public boolean deleted;
    /** 当前可编辑草稿，发布后清空。 */
    @TableField(updateStrategy=FieldStrategy.ALWAYS)
    public String draftJson;
    /** 复制动作的唯一幂等键。 */
    public String copyRequestId;
    /** 首次复制参数，用于识别幂等键误用。 */
    public String copyCommandJson;
    /** 创建时间，UTC。 */
    public OffsetDateTime createdAt;
    /** 最近修改时间，UTC。 */
    public OffsetDateTime updatedAt;
}
