package com._36knight.alpha.template.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com._36knight.alpha.template.persistence.PrdTemplateEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 自定义模板持久化入口，事务由业务服务维护。@author Andersen */
public interface PrdTemplateMapper extends BaseMapper<PrdTemplateEntity> {
    /** 锁住单个自定义模板，所有草稿及发布变更必须持有此锁。 */
    @Select("SELECT * FROM prd_templates WHERE id=#{id} FOR UPDATE")
    PrdTemplateEntity lock(@Param("id") String id);
}
