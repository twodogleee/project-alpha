package com._36knight.alpha.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com._36knight.alpha.model.persistence.ModelProfileEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 模型 SQL 访问，清空凭证必须显式写 NULL。@author Andersen
 */
public interface ModelProfileMapper extends BaseMapper<ModelProfileEntity> {
    /**
     * 更新所有可编辑字段，包括显式清除密钥。
     */
    @Update("UPDATE model_profiles SET platform_id=#{item.platformId}, name=#{item.name}, model=#{item.model}, api_key_ciphertext=#{item.apiKeyCiphertext,jdbcType=VARCHAR}, updated_at=#{item.updatedAt} WHERE id=#{item.id}")
    int updateProfile(@Param("item") ModelProfileEntity item);
}
