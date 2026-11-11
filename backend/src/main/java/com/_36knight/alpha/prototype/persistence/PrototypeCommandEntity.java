package com._36knight.alpha.prototype.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/** 应用及恢复的不可变幂等结果；与聚合指针变更同事务提交。 @author Andersen */
@TableName("prototype_commands")
public class PrototypeCommandEntity {
    /** 控制命令身份。 */
    @TableId(type = IdType.INPUT)
    public String id;
    /** 所属原型。 */
    public String prototypeId;
    /** APPLY 应用候选或 RESTORE 恢复历史。 */
    public String kind;
    /** 同原型、同命令种类的幂等键。 */
    public String requestId;
    /** 目标、预期版本等完整原始命令摘要。 */
    public String commandHash;
    /** 同原型被应用或恢复的目标修订。 */
    public String targetRevisionId;
    /** 应用指向原候选；恢复指向新追加修订。 */
    public String resultRevisionId;
    /** 供重复请求返回的最小结果快照。 */
    public String resultJson;
    /** 控制命令成功提交时间。 */
    public OffsetDateTime createdAt;
}
