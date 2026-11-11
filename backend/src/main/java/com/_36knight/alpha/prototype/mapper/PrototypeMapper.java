package com._36knight.alpha.prototype.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com._36knight.alpha.prototype.persistence.PrototypeEntity;

/** 文档内原型聚合；只保存当前指针及并发版本，不复制父对象生命周期。 Mapper 仅负责行访问。@author Andersen */
public interface PrototypeMapper extends BaseMapper<PrototypeEntity> {
}
