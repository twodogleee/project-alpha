package com._36knight.alpha.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com._36knight.alpha.project.persistence.ProjectDocumentEntity;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis Plus 当前文档 Mapper，条件更新保持原有版本冲突契约。
 * @author Andersen
 */
public interface ProjectDocumentMapper extends BaseMapper<ProjectDocumentEntity> {
    /** 仅当数据库版本仍为 expectedRevision 时原子更新当前文档。 */
    int updateIfRevision(@Param("document") ProjectDocumentEntity document, @Param("expectedRevision") long expectedRevision);
}
