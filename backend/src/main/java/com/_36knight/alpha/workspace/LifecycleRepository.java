package com._36knight.alpha.workspace;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com._36knight.alpha.workspace.mapper.LifecycleStateMapper;
import com._36knight.alpha.workspace.mapper.LifecycleBaselineMapper;
import com._36knight.alpha.workspace.mapper.LifecycleRevisionMapper;
import com._36knight.alpha.workspace.mapper.PrdRunMapper;
import com._36knight.alpha.workspace.persistence.LifecycleStateEntity;
import com._36knight.alpha.workspace.persistence.LifecycleBaselineEntity;
import com._36knight.alpha.workspace.persistence.LifecycleRevisionEntity;
import com._36knight.alpha.workspace.persistence.PrdRunEntity;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 正式产物数据库访问；基线和保存记录只提供插入和读取。@author Andersen */
@Repository
public class LifecycleRepository {
    /** 当前聚合映射。 */ private final LifecycleStateMapper states;
    /** 不可变确认映射。 */ private final LifecycleBaselineMapper baselines;
    /** 不可变保存映射。 */ private final LifecycleRevisionMapper revisions;
    /** PRD 持久化任务映射。 */ private final PrdRunMapper runs;
    /** 注入 MyBatis Plus 数据访问能力。 */
    public LifecycleRepository(LifecycleStateMapper s,LifecycleBaselineMapper b,LifecycleRevisionMapper h,PrdRunMapper r){states=s;baselines=b;revisions=h;runs=r;}
    /** 同项目旧 PRD 任务参与统一的单任务限制，调用方需持有项目根锁。 */
    public boolean runningProject(String projectId){return runs.activeInProject(projectId)>0;}
    /** 读取当前产物。 */ public LifecycleStateEntity state(String id){return states.selectById(id);}
    /** 读取基线。 */ public LifecycleBaselineEntity baseline(String id){return baselines.selectById(id);}
    /** 幂等人工确认查询。 */ public LifecycleBaselineEntity confirmation(String version,String key){return baselines.selectOne(new QueryWrapper<LifecycleBaselineEntity>().eq("version_id",version).eq("request_id",key));}
    /** 读取任务。 */ public PrdRunEntity run(String id){return runs.selectById(id);}
    /** 查询生成幂等命令。 */ public PrdRunEntity requestRun(String version,String key){return runs.selectOne(new QueryWrapper<PrdRunEntity>().eq("version_id",version).eq("request_id",key));}
    /** 近期任务用于刷新接管。 */ public List<PrdRunEntity> runs(String version){return runs.selectList(new QueryWrapper<PrdRunEntity>().eq("version_id",version).orderByDesc("created_at").orderByDesc("id").last("LIMIT 20"));}
    /** 运行期间不能取消、重开或启动同版本第二个任务。 */ public boolean running(String version){return runs.selectCount(new QueryWrapper<PrdRunEntity>().eq("version_id",version).in("status",List.of("QUEUED","RUNNING")))>0;}
    /** 启动查询未终结任务。 */ public List<PrdRunEntity> unfinished(){return runs.selectList(new QueryWrapper<PrdRunEntity>().in("status",List.of("QUEUED","RUNNING")));}
    /** 产物历史有界分页。 */ public Page<LifecycleRevisionEntity> history(String version,String kind,long page){return revisions.selectPage(new Page<>(page,50),new QueryWrapper<LifecycleRevisionEntity>().eq("version_id",version).eq("kind",kind).orderByDesc("revision"));}
    /** 精确读取不可变 PRD 保存记录，缺失时不回退到其他修订。 */
    public LifecycleRevisionEntity prdRevision(String version, long revision) {
        return revisions.selectOne(new QueryWrapper<LifecycleRevisionEntity>()
                .eq("version_id", version).eq("kind", "PRD").eq("revision", revision));
    }
    /** 已确认基线目录有界分页。 */ public Page<LifecycleBaselineEntity> baselines(String version,long page){return baselines.selectPage(new Page<>(page,50),new QueryWrapper<LifecycleBaselineEntity>().eq("version_id",version).orderByDesc("created_at").orderByDesc("id"));}
    /** 插入初始产物。 */ public void insert(LifecycleStateEntity s){states.insert(s);}
    /** 原子业务事务内更新当前产物。 */ public void update(LifecycleStateEntity s){states.updateById(s);}
    /** 插入不可变确认。 */ public void insert(LifecycleBaselineEntity b){baselines.insert(b);}
    /** 插入不可变修订。 */ public void insert(LifecycleRevisionEntity h){revisions.insert(h);}
    /** 保存待执行任务。 */ public void insert(PrdRunEntity r){runs.insert(r);}
    /** 只更新任务状态和结果。 */ public void update(PrdRunEntity r){runs.updateById(r);}
}
