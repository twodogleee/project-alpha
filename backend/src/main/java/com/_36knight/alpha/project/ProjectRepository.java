package com._36knight.alpha.project;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com._36knight.alpha.project.mapper.ProjectDocumentMapper;
import com._36knight.alpha.project.mapper.ProjectVersionMapper;
import com._36knight.alpha.project.persistence.ProjectDocumentEntity;
import com._36knight.alpha.project.persistence.ProjectVersionEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通过 MyBatis Plus 保存当前业务文档与不可变快照，事务不覆盖模型调用。
 * @author Andersen
 */
@Repository
public class ProjectRepository {
    /** 当前项目 Mapper，负责基础查询及原子版本校验。 */
    private final ProjectDocumentMapper documents;
    /** 不可变版本 Mapper，只执行新增和查询。 */
    private final ProjectVersionMapper history;
    /** 应用的 JSON 时间与记录类型配置，不写入日志。 */
    private final ObjectMapper mapper;

    /** 注入 Mapper 与快照序列化器，隐藏数据库实现于业务服务。 */
    public ProjectRepository(ProjectDocumentMapper documents, ProjectVersionMapper history, ObjectMapper mapper) {
        this.documents = documents;
        this.history = history;
        this.mapper = mapper;
    }

    /** 使用 PostgreSQL 分页插件查询最多 100 个摘要，不加载 JSON 正文。 */
    public List<ProjectSummary> list() {
        var query = Wrappers.<ProjectDocumentEntity>lambdaQuery()
                .select(ProjectDocumentEntity::getId, ProjectDocumentEntity::getName,
                        ProjectDocumentEntity::getRevision, ProjectDocumentEntity::getUpdatedAt)
                .orderByDesc(ProjectDocumentEntity::getUpdatedAt).orderByAsc(ProjectDocumentEntity::getId);
        return documents.selectPage(new Page<>(1, 100, false), query).getRecords().stream()
                .map(row -> new ProjectSummary(row.getId(), row.getName(), row.getRevision(), row.getUpdatedAt().toInstant())).toList();
    }

    /** 读取当前完整文档，不存在时返回安全的 404。 */
    public ProjectDocument get(String id) {
        var row = documents.selectById(id);
        if (row == null) throw new ProjectException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在");
        return decode(row.getDocumentJson());
    }

    /** 创建项目及首个不可变版本，Mapper 任一步失败均回滚。 */
    @Transactional
    public void insert(ProjectDocument document) {
        String json = encode(document);
        if (documents.insert(currentRow(document, json)) != 1) throw storageError();
        insertVersion(document, json);
    }

    /** 条件更新后追加版本，两条 SQL 在同一事务中提交。 */
    @Transactional
    public void update(ProjectDocument document, long expectedRevision) {
        String json = encode(document);
        if (documents.updateIfRevision(currentRow(document, json), expectedRevision) != 1) throw ProjectException.conflict();
        insertVersion(document, json);
    }

    /** 读取最近 100 个版本摘要，不查询文档正文。 */
    public List<VersionSummary> versions(String id) {
        get(id);
        var query = Wrappers.<ProjectVersionEntity>lambdaQuery()
                .select(ProjectVersionEntity::getRevision, ProjectVersionEntity::getUpdatedAt)
                .eq(ProjectVersionEntity::getProjectId, id).orderByDesc(ProjectVersionEntity::getRevision);
        return history.selectPage(new Page<>(1, 100, false), query).getRecords().stream()
                .map(row -> new VersionSummary(row.getRevision(), row.getUpdatedAt().toInstant())).toList();
    }

    /** 按项目和 revision 读取只读快照，包括列表以外的旧版本。 */
    public ProjectDocument version(String id, long revision) {
        get(id);
        var row = history.selectOne(Wrappers.<ProjectVersionEntity>lambdaQuery()
                .eq(ProjectVersionEntity::getProjectId, id).eq(ProjectVersionEntity::getRevision, revision));
        if (row == null) throw new ProjectException(HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND", "项目版本不存在");
        return decode(row.getDocumentJson());
    }

    /** 映射当前状态，时间以 UTC 写入 timestamptz。 */
    private ProjectDocumentEntity currentRow(ProjectDocument document, String json) {
        var row = new ProjectDocumentEntity();
        row.setId(document.id());
        row.setName(document.name());
        row.setRevision(document.revision());
        row.setUpdatedAt(document.updatedAt().atOffset(ZoneOffset.UTC));
        row.setDocumentJson(json);
        return row;
    }

    /** 在调用方事务内追加历史，由数据库唯一约束保证版本唯一。 */
    private void insertVersion(ProjectDocument document, String json) {
        var row = new ProjectVersionEntity();
        row.setId(UUID.randomUUID().toString());
        row.setProjectId(document.id());
        row.setRevision(document.revision());
        row.setUpdatedAt(document.updatedAt().atOffset(ZoneOffset.UTC));
        row.setDocumentJson(json);
        if (history.insert(row) != 1) throw storageError();
    }

    /** 序列化失败不泄漏文档正文。 */
    private String encode(ProjectDocument document) {
        try { return mapper.writeValueAsString(document); }
        catch (JsonProcessingException exception) { throw storageError(); }
    }

    /** 读取损坏快照时返回明确存储错误，不生成伪成功文档。 */
    private ProjectDocument decode(String json) {
        try { return mapper.readValue(json, ProjectDocument.class); }
        catch (JsonProcessingException exception) { throw storageError(); }
    }

    /** 返回不含 SQL、数据库凭证及正文的存储错误。 */
    private ProjectException storageError() {
        return new ProjectException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "项目存储暂时不可用，请保留编辑内容后重试");
    }
}
