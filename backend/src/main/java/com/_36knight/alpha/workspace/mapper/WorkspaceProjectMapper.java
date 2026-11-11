package com._36knight.alpha.workspace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com._36knight.alpha.workspace.persistence.WorkspaceProjectEntity;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

/** WorkspaceProject 数据访问，业务校验位于应用服务。@author Andersen */
public interface WorkspaceProjectMapper extends BaseMapper<WorkspaceProjectEntity> {
    /** 锁定项目根，序列化同项目关联、归档和会话写入；模型调用不持有该锁。 */
    @Select("SELECT * FROM workspace_projects WHERE id = #{id} FOR UPDATE")
    WorkspaceProjectEntity lock(@Param("id") String id);
}
