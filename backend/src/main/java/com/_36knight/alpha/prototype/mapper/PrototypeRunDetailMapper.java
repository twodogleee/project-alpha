package com._36knight.alpha.prototype.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com._36knight.alpha.prototype.persistence.PrototypeRunDetailEntity;

/** 原型任务扩展与执行租约；workspace_runs 负责通用状态和消息关联。 Mapper 仅负责行访问。@author Andersen */
public interface PrototypeRunDetailMapper extends BaseMapper<PrototypeRunDetailEntity> {
}
