package com._36knight.alpha.prototype.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/** 不可变 PRD 输入快照；完整保存当次生成来源，读取时不追随当前正文。 @author Andersen */
@TableName("prototype_sources")
public class PrototypeSourceEntity {
    /** 来源快照身份。 */
    @TableId(type = IdType.INPUT)
    public String id;
    /** 所属原型。 */
    public String prototypeId;
    /** 所属 PRD 文档，与原型归属一致。 */
    public String versionId;
    /** DRAFT 草稿或 BASELINE 定稿。 */
    public String sourceKind;
    /** 冻结时的 PRD 正文修订。 */
    public Long prdRevision;
    /** 冻结时的产物聚合版本。 */
    public Long lifecycleRevision;
    /** 冻结时的业务文档版本。 */
    public Long versionRevision;
    /** 定稿来源引用；草稿来源为空。 */
    public String prdBaselineId;
    /** 完整结构化 PRD，含绑定的写作模板引用。 */
    public String prdJson;
    /** 实际采用的背景、模板定义和有界说明，不含凭证。 */
    public String contextJson;
    /** 有序生成范围及稳定模块、页面身份。 */
    public String scopeJson;
    /** 规范化版本和全局、模块、页面依赖摘要。 */
    public String fingerprintsJson;
    /** 规范化完整输入的 SHA-256，不包含抓取时间。 */
    public String contentSha256;
    /** 输入冻结时间。 */
    public OffsetDateTime createdAt;
}
