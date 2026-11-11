package com._36knight.alpha.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validator;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com._36knight.alpha.workspace.PageRuleContracts.Query;
import com._36knight.alpha.workspace.PageRuleContracts.Result;
import com._36knight.alpha.workspace.PageRuleContracts.Source;
import com._36knight.alpha.workspace.PageRuleContracts.Status;

/**
 * 从不可变 PRD 保存历史读取一个页面章节，不初始化文档、不写任务或变更修订。
 * @author Andersen
 */
@Service
public class PageRuleQueryService {
    /** 验证宿主绑定的需求版本仍属于当前项目且未被回收。 */
    private final WorkspaceRepository workspace;
    /** 精确读取保存修订，不以当前正文或最新版本替代缺失记录。 */
    private final LifecycleRepository lifecycle;
    /** 读取服务保存的历史 JSON。 */
    private final ObjectMapper json;
    /** ToolCallback 不等于 HTTP 参数校验，业务入口显式执行约束。 */
    private final Validator validator;

    /** 复用现有仓储与序列化能力，保持工具适配层不直接操作数据库。 */
    public PageRuleQueryService(WorkspaceRepository workspace, LifecycleRepository lifecycle,
            ObjectMapper json, Validator validator) {
        this.workspace = workspace;
        this.lifecycle = lifecycle;
        this.json = json;
        this.validator = validator;
    }

    /**
     * projectId/versionId 由宿主选择；当前单用户项目仅校验归属，不提供用户认证。
     * 以一致的只读快照检查归属和历史；归档允许读取，回收文档拒绝读取。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result query(String projectId, String versionId, Query query) {
        if (query == null || !validator.validate(query).isEmpty()
                || !PrdDocuments.PAGE_TITLES.containsKey(query.section())) {
            return Result.empty(Status.INVALID_ARGUMENT, "请提供正整数 PRD 修订、模块、页面及有效页面章节");
        }
        if (projectId == null || projectId.isBlank() || versionId == null || versionId.isBlank()) {
            return Result.empty(Status.ACCESS_DENIED, "当前绑定范围不可读取");
        }
        try {
            var version = workspace.version(versionId);
            if (version == null || !projectId.equals(version.projectId) || version.deletedAt != null) {
                return Result.empty(Status.ACCESS_DENIED, "当前绑定范围不可读取");
            }
            var history = lifecycle.prdRevision(versionId, query.prdRevision());
            if (history == null) {
                return Result.empty(Status.REVISION_NOT_FOUND, "指定 PRD 保存修订不存在，不回退到最新正文");
            }
            // 会话保存历史在 PRD 顶层附有 suggestions；建议不属于正式正文。
            var tree = json.readTree(history.contentJson);
            if (!(tree instanceof ObjectNode document)) {
                return Result.empty(Status.UNAVAILABLE, "保存记录暂不可读取");
            }
            document.remove("suggestions");
            var prd = json.treeToValue(document, LifecycleContracts.Prd.class);
            if (!validator.validate(prd).isEmpty()) {
                return Result.empty(Status.UNAVAILABLE, "保存记录暂不可读取");
            }
            var module = prd.modules().stream().filter(item -> item.id().equals(query.moduleId()))
                    .findFirst().orElse(null);
            var page = module == null ? null : module.pages().stream()
                    .filter(item -> item.id().equals(query.pageId())).findFirst().orElse(null);
            if (page == null) {
                return Result.empty(Status.PAGE_NOT_FOUND, "指定修订中不存在该模块与页面组合");
            }
            var text = page.sections().getOrDefault(query.section(), "");
            var issues = relevantIssues(prd, query);
            var source = new Source(versionId, history.revision, module.id(), page.id(),
                    query.section(), history.createdAt);
            boolean undefined = text.isBlank() || !issues.isEmpty();
            return new Result(undefined ? Status.RULE_UNDEFINED : Status.FOUND,
                    undefined ? "正文为空或仍有相关待确认事项，请核对原文与问题"
                            : "已读取保存正文；这不表示规则完整或已经人工定稿",
                    source, text, issues);
        } catch (DataAccessException | JsonProcessingException exception) {
            return Result.empty(Status.UNAVAILABLE, "规则查询暂不可用，请稍后重试");
        }
    }

    /** 未定位的问题保守保留；已定位到其他页面或章节的问题不混入本次结果。 */
    private List<String> relevantIssues(LifecycleContracts.Prd prd, Query query) {
        String module = "module:" + query.moduleId();
        String page = "page:" + query.moduleId() + ":" + query.pageId();
        String section = PrdDocuments.pageKey(query.moduleId(), query.pageId(), query.section());
        return prd.unresolvedIssues().stream().filter(issue -> {
            var paths = prd.issueLinks().getOrDefault(issue, List.of());
            return paths.isEmpty() || paths.stream().anyMatch(path -> path.equals("DOCUMENT")
                    || path.equals(module) || path.equals(page) || path.equals(section)
                    || path.startsWith(module + ":") || PrdDocuments.TITLES.containsKey(path));
        }).toList();
    }
}
