package com._36knight.alpha.workspace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com._36knight.alpha.workspace.persistence.PrdRunEntity;

/** 生命周期数据映射，业务约束由服务维护。@author Andersen */
public interface PrdRunMapper extends BaseMapper<PrdRunEntity> {
    /** 项目根锁内统一检查旧 PRD 任务，防止与原型/工作台任务跨表并行。 */
    @org.apache.ibatis.annotations.Select("SELECT count(*) FROM version_prd_runs r JOIN requirement_versions v ON v.id=r.version_id WHERE v.project_id=#{projectId} AND r.status IN ('QUEUED','RUNNING')")
    long activeInProject(@org.apache.ibatis.annotations.Param("projectId") String projectId);
}
