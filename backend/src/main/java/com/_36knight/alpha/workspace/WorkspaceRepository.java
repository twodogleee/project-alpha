package com._36knight.alpha.workspace;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com._36knight.alpha.workspace.mapper.*;
import com._36knight.alpha.workspace.persistence.*;
import java.util.List;
import org.springframework.stereotype.Repository;

/** MyBatis Plus 仓储，仅负责关系查询、行锁与行写入；不持有模型客户端。@author Andersen */
@Repository
public class WorkspaceRepository {
    /** 项目根映射。 */ private final WorkspaceProjectMapper projects;
    /** 会话映射。 */ private final WorkSessionMapper sessions;
    /** 追加消息映射。 */ private final SessionMessageMapper messages;
    /** 执行任务映射。 */ private final WorkspaceRunMapper runs;
    /** 草稿快照映射。 */ private final SessionDraftRevisionMapper drafts;
    /** 交付版本映射。 */ private final RequirementVersionMapper versions;
    /** 不可变范围修订映射。 */ private final VersionRevisionMapper revisions;
    /** 注入既有 MyBatis Plus Mapper，业务事务由服务组织。 */
    public WorkspaceRepository(WorkspaceProjectMapper p, WorkSessionMapper s, SessionMessageMapper m,
            WorkspaceRunMapper r, SessionDraftRevisionMapper d, RequirementVersionMapper v, VersionRevisionMapper h) {
        projects=p; sessions=s; messages=m; runs=r; drafts=d; versions=v; revisions=h;
    }
    /** 锁项目根，调用方必须处于短业务事务。 */
    public WorkspaceProjectEntity lock(String id) { return projects.lock(id); }
    /** 读取项目。 */ public WorkspaceProjectEntity project(String id) { return projects.selectById(id); }
    /** 读取会话。 */ public WorkSessionEntity session(String id) { return sessions.selectById(id); }
    /** 读取交付版本。 */ public RequirementVersionEntity version(String id) { return versions.selectById(id); }
    /** 读取执行任务。 */ public WorkspaceRunEntity run(String id) { return runs.selectById(id); }
    /** 按幂等键读取原任务。 */
    public WorkspaceRunEntity requestRun(String sessionId,String key) { return runs.selectOne(new QueryWrapper<WorkspaceRunEntity>().eq("session_id",sessionId).eq("request_id",key)); }
    /** 检查同项目命名冲突，不依赖前端唯一校验。 */
    public boolean labelExists(String projectId,String label,String except) {
        return versions.selectCount(new QueryWrapper<RequirementVersionEntity>().eq("project_id",projectId).eq("label",label).ne(except!=null,"id",except))>0;
    }
    /** 查询活动执行计数，归档和提交命令使用同一项目锁。 */
    public boolean running(String projectId,String sessionId) {
        return runs.selectCount(new QueryWrapper<WorkspaceRunEntity>().eq("project_id",projectId).eq(sessionId!=null,"session_id",sessionId).in("status",List.of("QUEUED","RUNNING")))>0;
    }
    /** 查询项目活动版本，不把取消或归档当作完成。 */
    public boolean activeVersions(String projectId) { return versions.selectCount(new QueryWrapper<RequirementVersionEntity>().eq("project_id",projectId).eq("lifecycle","ACTIVE").isNull("deleted_at"))>0; }
    /** 分页读取项目目录，每页最多 50 条。 */
    public Page<WorkspaceProjectEntity> projects(long page,boolean archived) {
        return projects.selectPage(new Page<>(page,50), archive(new QueryWrapper<WorkspaceProjectEntity>(),archived).orderByDesc("updated_at").orderByAsc("id"));
    }
    /** 分页读取会话，projectId 为空时只用于归档目录。 */
    public Page<WorkSessionEntity> sessions(String projectId,String versionId,long page,boolean archived) {
        return sessions.selectPage(new Page<>(page,50), archive(new QueryWrapper<WorkSessionEntity>(),archived).isNull("deleted_at").eq(projectId!=null,"project_id",projectId).eq(versionId!=null,"version_id",versionId).orderByDesc("updated_at").orderByAsc("id"));
    }
    /** 分页读取业务版本，正文长度由写入契约控制。 */
    public Page<RequirementVersionEntity> versions(String projectId,long page,boolean archived) {
        return versions.selectPage(new Page<>(page,50),archive(new QueryWrapper<RequirementVersionEntity>(),archived).isNull("deleted_at").eq(projectId!=null,"project_id",projectId).orderByDesc("updated_at").orderByAsc("id"));
    }
    /** 回收站只按会话分页，正文与消息仍保留原关联。 */
    public Page<WorkSessionEntity> deletedSessions(long page) {
        return sessions.selectPage(new Page<>(page,50),new QueryWrapper<WorkSessionEntity>().isNotNull("deleted_at").orderByDesc("deleted_at").orderByAsc("id"));
    }
    /** 已回收文档独立分页，包含随最后会话共同回收的文档。 */
    public Page<RequirementVersionEntity> deletedVersions(long page) {
        return versions.selectPage(new Page<>(page,50),new QueryWrapper<RequirementVersionEntity>().isNotNull("deleted_at").orderByDesc("deleted_at").orderByAsc("id"));
    }
    /** 未删除的关联会话数量包含归档会话，供文档删除确认使用。 */
    public long linkedSessions(String versionId) {
        return sessions.selectCount(new QueryWrapper<WorkSessionEntity>().eq("version_id",versionId).isNull("deleted_at"));
    }
    /** 未删除的关联会话包含已归档会话，避免误移除仍被保留的共享文档。 */
    public long otherSessions(String versionId,String sessionId) {
        return sessions.selectCount(new QueryWrapper<WorkSessionEntity>().eq("version_id",versionId).ne("id",sessionId).isNull("deleted_at"));
    }
    /** 文档任务可能从其他会话或旧版本入口发起。 */
    public boolean versionRunning(String versionId) {
        return runs.selectCount(new QueryWrapper<WorkspaceRunEntity>().eq("version_id",versionId).in("status",List.of("QUEUED","RUNNING")))>0;
    }
    /** 读取最多 51 条倒序消息，额外一条判断是否还有更早历史。 */
    public List<SessionMessageEntity> messages(String id,Long before) {
        return messages.selectList(new QueryWrapper<SessionMessageEntity>().eq("session_id",id).lt(before!=null,"sequence",before).orderByDesc("sequence").last("LIMIT 51"));
    }
    /** 读取近期执行记录，用于刷新后接管任务状态。 */
    public List<WorkspaceRunEntity> runs(String sessionId) { return runs.selectList(new QueryWrapper<WorkspaceRunEntity>().eq("session_id",sessionId).orderByDesc("created_at").orderByDesc("id").last("LIMIT 20")); }
    /** 当前消息页批量读取附件来源，避免逐条查询；归属限定为同一会话。 */
    public List<WorkspaceRunEntity> messageRuns(String sessionId,List<String> ids) { return ids.isEmpty()?List.of():runs.selectList(new QueryWrapper<WorkspaceRunEntity>().eq("session_id",sessionId).eq("kind","WORKBENCH").in("id",ids)); }
    /** 读取指定不可变草稿，不从未保存页面取值。 */
    public SessionDraftRevisionEntity draft(String sessionId,long revision) { return drafts.selectOne(new QueryWrapper<SessionDraftRevisionEntity>().eq("session_id",sessionId).eq("revision",revision)); }
    /** 分页读取范围修订。 */
    public Page<VersionRevisionEntity> revisions(String id,long page) { return revisions.selectPage(new Page<>(page,50),new QueryWrapper<VersionRevisionEntity>().eq("version_id",id).orderByDesc("revision")); }
    /** 只读历史正文。 */
    public VersionRevisionEntity revision(String id,long revision) { return revisions.selectOne(new QueryWrapper<VersionRevisionEntity>().eq("version_id",id).eq("revision",revision)); }
    /** 插入实体；类型封闭在当前工作空间持久化对象中。 */
    public void insert(Object row) {
        int count=switch(row) {
            case WorkspaceProjectEntity r -> projects.insert(r); case WorkSessionEntity r -> sessions.insert(r);
            case SessionMessageEntity r -> messages.insert(r); case WorkspaceRunEntity r -> runs.insert(r);
            case SessionDraftRevisionEntity r -> drafts.insert(r); case RequirementVersionEntity r -> versions.insert(r);
            case VersionRevisionEntity r -> revisions.insert(r); default -> throw new IllegalArgumentException("未知工作空间行类型");
        };
        if(count!=1) throw new IllegalStateException("工作空间写入未完成");
    }
    /** 更新当前状态；不可变快照及消息没有更新入口。 */
    public void update(Object row) {
        int count=switch(row) {
            case WorkspaceProjectEntity r -> projects.updateById(r); case WorkSessionEntity r -> sessions.updateById(r);
            case WorkspaceRunEntity r -> runs.updateById(r); case RequirementVersionEntity r -> versions.updateById(r);
            default -> throw new IllegalArgumentException("不可更新工作空间快照");
        };
        if(count!=1) throw new IllegalStateException("工作空间更新未完成");
    }
    /** 启动时查询未终结任务；单进程工作空间不宣称跨实例恢复。 */
    public List<WorkspaceRunEntity> unfinished() { return runs.selectList(new QueryWrapper<WorkspaceRunEntity>().in("status",List.of("QUEUED","RUNNING"))); }
    /** 统一归档过滤，恢复通过明确命令完成。 */
    private <T> QueryWrapper<T> archive(QueryWrapper<T> query,boolean archived) { return archived?query.isNotNull("archived_at"):query.isNull("archived_at"); }
}
