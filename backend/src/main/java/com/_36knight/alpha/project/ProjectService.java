package com._36knight.alpha.project;

import com._36knight.alpha.agent.RequirementWorkflow;
import com._36knight.alpha.service.PrdRenderer;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import static com._36knight.alpha.project.ProjectDocument.*;

/**
 * 维护人工评审规则、来源与业务版本，Graph 仅用于创建时的分析。
 * @author Andersen
 */
@Service
public class ProjectService {
    /** 项目持久化边界。 */
    private final ProjectRepository repository;
    /** 首次分析能力；评审保存不再调用模型。 */
    private final RequirementWorkflow workflow;
    /** 从保存快照生成一致的 PRD。 */
    private final PrdRenderer renderer;

    /** 装配业务存储、分析和渲染能力。 */
    public ProjectService(ProjectRepository repository, RequirementWorkflow workflow, PrdRenderer renderer) {
        this.repository = repository;
        this.workflow = workflow;
        this.renderer = renderer;
    }

    /** 创建首个草稿；模型失败时不写入项目，生成内容始终待确认。 */
    public ProjectDocument create(CreateProjectRequest request) {
        var response = workflow.execute(request.content().strip());
        var draft = response.draft();
        Instant now = Instant.now();
        var document = withPrd(new ProjectDocument(newId(), request.name().strip(), request.content().strip(),
                response.mode(), response.runId(), 1, now, now, draft.title(), draft.overview(),
                draft.requirements().stream().map(text -> new RequirementItem(newId(), text,
                        RequirementStatus.PENDING, RequirementSource.GENERATED, null)).toList(),
                draft.openQuestions().stream().map(text -> new Clarification(newId(), text, "")).toList(),
                draft.assumptions().stream().map(text -> new DesignSuggestion(newId(), text, SuggestionStatus.PENDING)).toList(), ""));
        repository.insert(document);
        return document;
    }

    /** 读取项目列表摘要。 */
    public List<ProjectSummary> list() { return repository.list(); }
    /** 读取当前已保存版本。 */
    public ProjectDocument get(String id) { return repository.get(id); }
    /** 读取项目版本摘要。 */
    public List<VersionSummary> versions(String id) { return repository.versions(id); }
    /** 读取只读历史，不覆盖当前项目。 */
    public ProjectDocument version(String id, long revision) { return repository.version(id, revision); }

    /** 校验完整编辑、关联与确认边界，再以乐观锁提交新版本。 */
    public ProjectDocument save(String id, SaveProjectRequest request) {
        ProjectDocument old = repository.get(id);
        if (old.revision() != request.expectedRevision()) throw ProjectException.conflict();
        Map<String, RequirementItem> existing = new HashMap<>();
        old.requirements().forEach(item -> existing.put(item.id(), item));
        Set<String> seen = new HashSet<>();
        List<RequirementItem> requirements = new ArrayList<>();
        for (var edit : request.requirements()) {
            String text = edit.text().strip();
            if (edit.id() == null) {
                requirements.add(new RequirementItem(newId(), text, edit.status(), RequirementSource.USER, null));
            } else {
                RequirementItem item = existing.get(edit.id());
                if (item == null || !seen.add(edit.id())) throw invalid("需求项 ID 无效或重复");
                // 防止一次正文改写同时沿用旧确认，必须后续明确确认。
                RequirementStatus status = item.status() == RequirementStatus.CONFIRMED && !item.text().equals(text)
                        ? RequirementStatus.PENDING : edit.status();
                requirements.add(new RequirementItem(item.id(), text, status, item.source(), item.sourceId()));
            }
        }
        for (var item : old.requirements()) {
            if (item.source() == RequirementSource.SUGGESTION && !seen.contains(item.id())) {
                throw invalid("已接受建议的关联需求不能删除，可编辑其正文和确认状态");
            }
        }
        var questions = mergeQuestions(old, request);
        var suggestions = mergeSuggestions(old, request, requirements);
        if (requirements.size() > 100) throw invalid("需求最多 100 项，请减少新增项后再接受建议");
        ProjectDocument next = withPrd(new ProjectDocument(old.id(), request.name().strip(), old.originalContent(),
                old.mode(), old.runId(), old.revision() + 1, old.createdAt(), Instant.now(), request.title().strip(),
                request.overview().strip(), List.copyOf(requirements), questions, suggestions, ""));
        repository.update(next, request.expectedRevision());
        return next;
    }

    /** 问题 ID 和正文保持来源，仅合并人工回答；不自动形成需求。 */
    private List<Clarification> mergeQuestions(ProjectDocument old, SaveProjectRequest request) {
        Map<String, Clarification> existing = new HashMap<>();
        old.questions().forEach(item -> existing.put(item.id(), item));
        Set<String> seen = new HashSet<>();
        List<Clarification> questions = new ArrayList<>();
        for (var edit : request.questions()) {
            var item = existing.get(edit.id());
            if (item == null || !seen.add(edit.id())) throw invalid("澄清问题 ID 无效或重复");
            questions.add(new Clarification(item.id(), item.text(), edit.answer().strip()));
        }
        if (seen.size() != existing.size()) throw invalid("请保留全部澄清问题，可修改或清空回答");
        return List.copyOf(questions);
    }

    /** 接受建议只追加一次待确认需求，保留建议正文与稳定关联。 */
    private List<DesignSuggestion> mergeSuggestions(ProjectDocument old, SaveProjectRequest request,
            List<RequirementItem> requirements) {
        Map<String, DesignSuggestion> existing = new HashMap<>();
        old.suggestions().forEach(item -> existing.put(item.id(), item));
        Set<String> seen = new HashSet<>();
        List<DesignSuggestion> suggestions = new ArrayList<>();
        for (var edit : request.suggestions()) {
            var item = existing.get(edit.id());
            if (item == null || !seen.add(edit.id())) throw invalid("设计建议 ID 无效或重复");
            String text = edit.text().strip();
            if (item.status() == SuggestionStatus.ACCEPTED
                    && (edit.status() != SuggestionStatus.ACCEPTED || !item.text().equals(text))) {
                throw invalid("已接受建议保留原文和处理状态，请编辑其关联需求");
            }
            if (item.status() != SuggestionStatus.ACCEPTED && edit.status() == SuggestionStatus.ACCEPTED) {
                requirements.add(new RequirementItem(newId(), text, RequirementStatus.PENDING,
                        RequirementSource.SUGGESTION, item.id()));
            }
            suggestions.add(new DesignSuggestion(item.id(), text, edit.status()));
        }
        if (seen.size() != existing.size()) throw invalid("请保留全部设计建议，可拒绝未接受建议");
        return List.copyOf(suggestions);
    }

    /** 由同一业务版本渲染 PRD，客户端不能指定文档正文。 */
    private ProjectDocument withPrd(ProjectDocument document) {
        return new ProjectDocument(document.id(), document.name(), document.originalContent(), document.mode(),
                document.runId(), document.revision(), document.createdAt(), document.updatedAt(), document.title(),
                document.overview(), document.requirements(), document.questions(), document.suggestions(), renderer.render(document));
    }

    /** 为项目及各业务项分配不依赖数组位置的 ID。 */
    private String newId() { return UUID.randomUUID().toString(); }
    /** 返回可安全展示的关联校验错误。 */
    private ProjectException invalid(String message) {
        return new ProjectException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }
}
