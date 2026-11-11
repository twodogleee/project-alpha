package com._36knight.alpha.prototype.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/** 不可变可运行原型修订；只有通过平台检查的完整产物进入本表。 @author Andersen */
@TableName("prototype_revisions")
public class PrototypeRevisionEntity {
    /** 可运行修订身份。 */
    @TableId(type = IdType.INPUT)
    public String id;
    /** 所属原型。 */
    public String prototypeId;
    /** 同一原型内递增的不可变修订序号。 */
    public Long revisionNumber;
    /** 生成或编辑的同原型基准，首次生成为空。 */
    public String parentRevisionId;
    /** 人工恢复的历史来源；普通生成为空。 */
    public String restoredFromRevisionId;
    /** 不可变 PRD 来源快照。 */
    public String sourceId;
    /** 产生本结果的唯一任务；人工恢复为空。 */
    public String sourceRunId;
    /** 构建使用的固定模板身份。 */
    public String templateId;
    /** 构建使用的模板版本。 */
    public Long templateRevision;
    /** 已验证的固定工具链身份。 */
    public String toolchainId;
    /** 页面、文件、关联、覆盖和模拟行为清单。 */
    public String manifestJson;
    /** 平台实际执行的检查结果及未验证部分。 */
    public String validationJson;
    /** 不可变对象键、摘要及字节数，不含宿主绝对路径。 */
    public String artifactJson;
    /** 实际来源模式，模板联调不得标记为模型生成。 */
    public String mode;
    /** 公开模型标识或明确的本地模板标识。 */
    public String model;
    /** 有界变更说明，恢复需明确记录恢复动作。 */
    public String changeSummary;
    /** 验证通过并封存的时间。 */
    public OffsetDateTime createdAt;
}
