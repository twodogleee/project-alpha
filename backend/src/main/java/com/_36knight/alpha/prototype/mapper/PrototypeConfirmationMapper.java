package com._36knight.alpha.prototype.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com._36knight.alpha.prototype.persistence.PrototypeConfirmationEntity;

/** 人工确认的不可变记录；仅确认当前精确修订及当前有效 PRD 定稿。 Mapper 仅负责行访问。@author Andersen */
public interface PrototypeConfirmationMapper extends BaseMapper<PrototypeConfirmationEntity> {
}
