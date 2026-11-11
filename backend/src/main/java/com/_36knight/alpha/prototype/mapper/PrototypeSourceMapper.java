package com._36knight.alpha.prototype.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com._36knight.alpha.prototype.persistence.PrototypeSourceEntity;

/** 不可变 PRD 输入快照；完整保存当次生成来源，读取时不追随当前正文。 Mapper 仅负责行访问。@author Andersen */
public interface PrototypeSourceMapper extends BaseMapper<PrototypeSourceEntity> {
}
