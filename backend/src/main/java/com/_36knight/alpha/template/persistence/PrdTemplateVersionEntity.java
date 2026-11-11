package com._36knight.alpha.template.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/** prd_template_versions 的持久化映射，不直接作为接口响应。@author Andersen */
@TableName("prd_template_versions")
public class PrdTemplateVersionEntity {
    /** 发布记录稳定标识。 */
    @TableId(type=IdType.INPUT)
    public String id;
    /** 所属自定义模板。 */
    public String templateId;
    /** 不可变发布版本号。 */
    public int revision;
    /** 当时完整定义，后续版本禁止改写。 */
    public String definitionJson;
    /** 同一模板内的发布幂等键。 */
    public String publishRequestId;
    /** 发布时草稿管理修订号，用于拒绝幂等键误用。 */
    public long sourceRevision;
    /** 发布时间，UTC。 */
    public OffsetDateTime createdAt;
}
