package com._36knight.alpha.prototype.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com._36knight.alpha.prototype.persistence.PrototypeRevisionEntity;

/** 不可变可运行原型修订；只有通过平台检查的完整产物进入本表。 Mapper 仅负责行访问。@author Andersen */
public interface PrototypeRevisionMapper extends BaseMapper<PrototypeRevisionEntity> {
}
