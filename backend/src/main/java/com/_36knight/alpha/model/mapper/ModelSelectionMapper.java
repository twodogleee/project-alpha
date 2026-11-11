package com._36knight.alpha.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com._36knight.alpha.model.persistence.ModelSelectionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 目录根版本与运行选择的访问，所有写操作先锁此行。@author Andersen
 */
public interface ModelSelectionMapper extends BaseMapper<ModelSelectionEntity> {
    /**
     * 用数据库行锁串行执行关系校验和修改。
     */
    @Select("SELECT * FROM model_runtime_selection WHERE id=1 FOR UPDATE")
    ModelSelectionEntity lockSelection();

    /**
     * 所有目录变更递增同一版本，选择可显式清空。
     */
    @Update("UPDATE model_runtime_selection SET revision=#{item.revision}, mode=#{item.mode}, active_model_id=#{item.activeModelId,jdbcType=VARCHAR}, updated_at=#{item.updatedAt} WHERE id=1")
    int updateSelection(@Param("item") ModelSelectionEntity item);
}
