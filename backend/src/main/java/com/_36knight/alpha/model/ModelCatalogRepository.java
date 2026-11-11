package com._36knight.alpha.model;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com._36knight.alpha.model.mapper.ModelPlatformMapper;
import com._36knight.alpha.model.mapper.ModelProfileMapper;
import com._36knight.alpha.model.mapper.ModelSelectionMapper;
import com._36knight.alpha.model.persistence.ModelPlatformEntity;
import com._36knight.alpha.model.persistence.ModelProfileEntity;
import com._36knight.alpha.model.persistence.ModelSelectionEntity;

import java.util.List;

import org.springframework.stereotype.Repository;

/**
 * MyBatis Plus 目录访问，业务服务统一管理跨表事务。@author Andersen
 */
@Repository
public class ModelCatalogRepository {
    /**
     * 平台存取。
     */
    private final ModelPlatformMapper platforms;
    /**
     * 模型存取。
     */
    private final ModelProfileMapper profiles;
    /**
     * 全局版本及选择存取。
     */
    private final ModelSelectionMapper selections;

    /**
     * 注入目录 Mapper。
     */
    public ModelCatalogRepository(ModelPlatformMapper platforms, ModelProfileMapper profiles, ModelSelectionMapper selections) {
        this.platforms = platforms;
        this.profiles = profiles;
        this.selections = selections;
    }

    /**
     * 按名称与稳定 ID 返回平台。
     */
    public List<ModelPlatformEntity> platforms() {
        return platforms.selectList(new QueryWrapper<ModelPlatformEntity>().orderByAsc("name", "id"));
    }

    /**
     * 按显示名称与 ID 返回模型。
     */
    public List<ModelProfileEntity> profiles() {
        return profiles.selectList(new QueryWrapper<ModelProfileEntity>().orderByAsc("name", "id"));
    }

    /**
     * 查询平台或返回 null。
     */
    public ModelPlatformEntity platform(String id) {
        return platforms.selectById(id);
    }

    /**
     * 查询模型或返回 null。
     */
    public ModelProfileEntity profile(String id) {
        return profiles.selectById(id);
    }

    /**
     * 读取唯一运行选择。
     */
    public ModelSelectionEntity selection() {
        return selections.selectById(1L);
    }

    /**
     * 锁住目录根，必须位于业务事务中。
     */
    public ModelSelectionEntity lockSelection() {
        return selections.lockSelection();
    }

    /**
     * 插入平台，不含凭证。
     */
    public void insertPlatform(ModelPlatformEntity item) {
        platforms.insert(item);
    }

    /**
     * 更新平台元数据。
     */
    public void updatePlatform(ModelPlatformEntity item) {
        platforms.updateById(item);
    }

    /**
     * 删除没有模型引用的平台。
     */
    public void deletePlatform(String id) {
        platforms.deleteById(id);
    }

    /**
     * 插入模型及可选密文。
     */
    public void insertProfile(ModelProfileEntity item) {
        profiles.insert(item);
    }

    /**
     * 更新模型，可显式清空密文。
     */
    public void updateProfile(ModelProfileEntity item) {
        profiles.updateProfile(item);
    }

    /**
     * 删除未启用模型。
     */
    public void deleteProfile(String id) {
        profiles.deleteById(id);
    }

    /**
     * 更新运行选择及根版本。
     */
    public void updateSelection(ModelSelectionEntity item) {
        selections.updateSelection(item);
    }
}
