package com._36knight.alpha.prototype;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com._36knight.alpha.prototype.mapper.PrototypeCommandMapper;
import com._36knight.alpha.prototype.mapper.PrototypeConfirmationMapper;
import com._36knight.alpha.prototype.mapper.PrototypeMapper;
import com._36knight.alpha.prototype.mapper.PrototypeRevisionMapper;
import com._36knight.alpha.prototype.mapper.PrototypeRunDetailMapper;
import com._36knight.alpha.prototype.mapper.PrototypeSourceMapper;
import com._36knight.alpha.prototype.persistence.PrototypeCommandEntity;
import com._36knight.alpha.prototype.persistence.PrototypeConfirmationEntity;
import com._36knight.alpha.prototype.persistence.PrototypeEntity;
import com._36knight.alpha.prototype.persistence.PrototypeRevisionEntity;
import com._36knight.alpha.prototype.persistence.PrototypeRunDetailEntity;
import com._36knight.alpha.prototype.persistence.PrototypeSourceEntity;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 原型关系访问；项目根锁及跨表事务由应用服务组织，快照和历史只允许追加。@author Andersen */
@Repository
public class PrototypeRepository {
    /** 当前原型聚合映射。 */ private final PrototypeMapper prototypes;
    /** 不可变输入映射。 */ private final PrototypeSourceMapper sources;
    /** 不可变可运行修订映射。 */ private final PrototypeRevisionMapper revisions;
    /** 通用任务的一对一扩展映射。 */ private final PrototypeRunDetailMapper details;
    /** 不可变人工确认映射。 */ private final PrototypeConfirmationMapper confirmations;
    /** 应用、恢复幂等结果映射。 */ private final PrototypeCommandMapper commands;

    /** 注入六类 Mapper；本仓储不访问模型、Worker 或宿主文件。 */
    public PrototypeRepository(PrototypeMapper prototypes, PrototypeSourceMapper sources,
            PrototypeRevisionMapper revisions, PrototypeRunDetailMapper details,
            PrototypeConfirmationMapper confirmations, PrototypeCommandMapper commands) {
        this.prototypes=prototypes; this.sources=sources; this.revisions=revisions;
        this.details=details; this.confirmations=confirmations; this.commands=commands;
    }

    /** 按稳定身份读取聚合；访问资格由调用方从父文档检查。 */
    public PrototypeEntity prototype(String id) { return prototypes.selectById(id); }
    /** 只读文档原型，不隐式创建空聚合。 */
    public PrototypeEntity forVersion(String versionId) {
        return prototypes.selectOne(new QueryWrapper<PrototypeEntity>().eq("version_id",versionId));
    }
    /** 读取确切不可变输入，调用方必须检查同原型归属。 */
    public PrototypeSourceEntity source(String id) { return sources.selectById(id); }
    /** 读取确切不可变修订，调用方必须检查同原型归属。 */
    public PrototypeRevisionEntity revision(String id) { return revisions.selectById(id); }
    /** 每个非空生成任务最多封存一个结果；用于完成回调去重。 */
    public PrototypeRevisionEntity revisionForRun(String runId) {
        return revisions.selectOne(new QueryWrapper<PrototypeRevisionEntity>().eq("source_run_id",runId));
    }
    /** 按通用任务身份读取其原型扩展。 */
    public PrototypeRunDetailEntity runDetail(String runId) { return details.selectById(runId); }
    /** 读取人工确认本身；历史引用不依赖当前 PRD 指针。 */
    public PrototypeConfirmationEntity confirmation(String id) { return confirmations.selectById(id); }
    /** 在检查可变版本前查找人工确认幂等结果。 */
    public PrototypeConfirmationEntity confirmation(String prototypeId,String requestId) {
        return confirmations.selectOne(new QueryWrapper<PrototypeConfirmationEntity>()
                .eq("prototype_id",prototypeId).eq("request_id",requestId));
    }
    /** 查询该精确修订的最近历史确认，不借用其他修订的聚合当前指针。 */
    public PrototypeConfirmationEntity latestConfirmationForRevision(String prototypeId,String revisionId) {
        return confirmations.selectOne(new QueryWrapper<PrototypeConfirmationEntity>()
                .eq("prototype_id",prototypeId).eq("revision_id",revisionId)
                .orderByDesc("created_at").orderByDesc("id").last("LIMIT 1"));
    }
    /** 在检查可变版本前查找控制命令幂等结果。 */
    public PrototypeCommandEntity command(String prototypeId,String kind,String requestId) {
        return commands.selectOne(new QueryWrapper<PrototypeCommandEntity>()
                .eq("prototype_id",prototypeId).eq("kind",kind).eq("request_id",requestId));
    }
    /** 不可变修订按序号倒序分页，每页最多 50 条。 */
    public Page<PrototypeRevisionEntity> revisions(String prototypeId,long page) {
        return revisions.selectPage(new Page<>(page,50),new QueryWrapper<PrototypeRevisionEntity>()
                .eq("prototype_id",prototypeId).orderByDesc("revision_number"));
    }
    /** 人工确认按时间及稳定身份倒序分页，每页最多 50 条。 */
    public Page<PrototypeConfirmationEntity> confirmations(String prototypeId,long page) {
        return confirmations.selectPage(new Page<>(page,50),new QueryWrapper<PrototypeConfirmationEntity>()
                .eq("prototype_id",prototypeId).orderByDesc("created_at").orderByDesc("id"));
    }
    /** 查询最近 20 个原型任务扩展，按提交后单调递增的原型版本排序。 */
    public List<PrototypeRunDetailEntity> runDetails(String prototypeId) {
        return details.selectList(new QueryWrapper<PrototypeRunDetailEntity>()
                .eq("prototype_id",prototypeId)
                .orderByDesc("expected_prototype_revision").orderByDesc("run_id").last("LIMIT 20"));
    }
    /** 批量读取任务扩展，避免会话消息及通用任务目录逐条查询。 */
    public List<PrototypeRunDetailEntity> runDetails(List<String> runIds) {
        return runIds.isEmpty()?List.of():details.selectList(new QueryWrapper<PrototypeRunDetailEntity>().in("run_id",runIds));
    }
    /** 追加聚合、不可变来源/历史或任务扩展；类型只限本领域持久化行。 */
    public void insert(Object row) {
        int count=switch(row) {
            case PrototypeEntity r -> prototypes.insert(r);
            case PrototypeSourceEntity r -> sources.insert(r);
            case PrototypeRevisionEntity r -> revisions.insert(r);
            case PrototypeRunDetailEntity r -> details.insert(r);
            case PrototypeConfirmationEntity r -> confirmations.insert(r);
            case PrototypeCommandEntity r -> commands.insert(r);
            default -> throw new IllegalArgumentException("未知原型持久化行类型");
        };
        requireWritten(count);
    }
    /** 仅更新可变聚合；调用方必须持有项目根锁及同一事务。 */
    public void update(PrototypeEntity row) { requireWritten(prototypes.updateById(row)); }
    /** 仅更新任务执行状态和应用结果；不可变来源、修订、确认没有更新入口。 */
    public void update(PrototypeRunDetailEntity row) { requireWritten(details.updateById(row)); }
    /** 所有写入要求恰好影响一行，失败不能继续推进当前指针。 */
    private void requireWritten(int count) {
        if(count!=1) throw new IllegalStateException("原型持久化写入未完成");
    }
}
