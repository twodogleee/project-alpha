package com._36knight.alpha.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com._36knight.alpha.project.persistence.ProjectVersionEntity;

/**
 * MyBatis Plus 历史 Mapper，应用层只调用新增与读取，不提供修改历史接口。
 * @author Andersen
 */
public interface ProjectVersionMapper extends BaseMapper<ProjectVersionEntity> { }
