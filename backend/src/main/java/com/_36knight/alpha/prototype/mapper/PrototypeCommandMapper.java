package com._36knight.alpha.prototype.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com._36knight.alpha.prototype.persistence.PrototypeCommandEntity;

/** 应用及恢复的不可变幂等结果；与聚合指针变更同事务提交。 Mapper 仅负责行访问。@author Andersen */
public interface PrototypeCommandMapper extends BaseMapper<PrototypeCommandEntity> {
}
