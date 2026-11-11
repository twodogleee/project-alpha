package com._36knight.alpha;

import com._36knight.alpha.model.ModelAnalyzerFactory;
import com._36knight.alpha.dto.RequirementDraft;
import com._36knight.alpha.project.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import static com._36knight.alpha.project.ProjectDocument.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 模拟结构化分析结果，验证建议来源、人工确认及关联保护；不代表模型效果。
 * @author Andersen
 */
@SpringBootTest(properties = {"alpha.ai.mode=demo", "alpha.ai.api-key=", "spring.datasource.dynamic.datasource.master.url=jdbc:h2:mem:project-review;DB_CLOSE_DELAY=-1"})
@Import(ProjectReviewServiceTest.AnalysisFixture.class)
class ProjectReviewServiceTest {
    /** 使用真实 Graph 与数据库执行业务评审。 */
    @Autowired private ProjectService service;

    /** 固定分析器仅作为测试数据，明确区别于云模型结果。 */
    @TestConfiguration
    static class AnalysisFixture {
        /** 输出带建议的测试草稿，便于验证 demo 默认不会产生的分支。 */
        @Bean @Primary
        ModelAnalyzerFactory fixtureAnalyzer() {
            return settings -> content -> new RequirementDraft("测试草稿", content, List.of("新增客户", "查询客户"),
                    List.of("谁能分配客户？"), List.of("提供批量导入", "提供客户标签"));
        }
    }

    /** 接受建议创建一次待确认需求，反复保存保留稳定关联。 */
    @Test
    void acceptsSuggestionOnceAndKeepsRequirementPending() {
        var initial = create();
        String suggestionId = initial.suggestions().getFirst().id();
        var edit = payload(initial, initial.requirements(), List.of(
                new SaveProjectRequest.SuggestionEdit(suggestionId, "提供 CSV 批量导入", SuggestionStatus.ACCEPTED),
                suggestionEdit(initial.suggestions().get(1), SuggestionStatus.REJECTED)));
        var saved = service.save(initial.id(), edit);
        var accepted = saved.requirements().getLast();
        assertThat(accepted.source()).isEqualTo(RequirementSource.SUGGESTION);
        assertThat(accepted.sourceId()).isEqualTo(suggestionId);
        assertThat(accepted.status()).isEqualTo(RequirementStatus.PENDING);
        assertThat(accepted.text()).isEqualTo("提供 CSV 批量导入");
        var again = service.save(initial.id(), payload(saved, saved.requirements(), suggestions(saved)));
        assertThat(again.requirements()).hasSize(3);
        assertThat(again.requirements().getLast().id()).isEqualTo(accepted.id());
        assertThat(service.version(initial.id(), 1).suggestions().getFirst().status()).isEqualTo(SuggestionStatus.PENDING);
        assertThat(saved.prdMarkdown()).contains("已接受，见关联需求", "已拒绝");
    }

    /** 已接受建议不可撤销、改写原文或删除关联需求。 */
    @Test
    void protectsAcceptedSuggestionAndItsLinkedRequirement() {
        var initial = create();
        var saved = service.save(initial.id(), payload(initial, initial.requirements(), List.of(
                suggestionEdit(initial.suggestions().getFirst(), SuggestionStatus.ACCEPTED),
                suggestionEdit(initial.suggestions().get(1), SuggestionStatus.PENDING))));
        assertInvalid(() -> service.save(saved.id(), payload(saved, saved.requirements().subList(0, 2), suggestions(saved))));
        assertInvalid(() -> service.save(saved.id(), payload(saved, saved.requirements(), List.of(
                suggestionEdit(saved.suggestions().getFirst(), SuggestionStatus.PENDING),
                suggestionEdit(saved.suggestions().get(1), SuggestionStatus.PENDING)))));
        assertInvalid(() -> service.save(saved.id(), payload(saved, saved.requirements(), List.of(
                new SaveProjectRequest.SuggestionEdit(saved.suggestions().getFirst().id(), "改写接受原文", SuggestionStatus.ACCEPTED),
                suggestionEdit(saved.suggestions().get(1), SuggestionStatus.PENDING)))));
        assertThat(service.get(saved.id())).isEqualTo(saved);
        assertThat(service.versions(saved.id())).hasSize(2);
    }

    /** 建议拒绝可恢复待处理，但不会生成需求。 */
    @Test
    void rejectingAndReopeningSuggestionDoesNotCreateRequirement() {
        var initial = create();
        var rejected = service.save(initial.id(), payload(initial, initial.requirements(), List.of(
                suggestionEdit(initial.suggestions().getFirst(), SuggestionStatus.REJECTED),
                suggestionEdit(initial.suggestions().get(1), SuggestionStatus.PENDING))));
        var reopened = service.save(initial.id(), payload(rejected, rejected.requirements(), List.of(
                suggestionEdit(rejected.suggestions().getFirst(), SuggestionStatus.PENDING),
                suggestionEdit(rejected.suggestions().get(1), SuggestionStatus.PENDING))));
        assertThat(reopened.requirements()).hasSize(2);
        assertThat(reopened.suggestions().getFirst().status()).isEqualTo(SuggestionStatus.PENDING);
    }

    /** 重复或跨项目建议 ID 不得制造需求来源。 */
    @Test
    void rejectsDuplicateOrForeignSuggestionIds() {
        var first = create();
        var second = create();
        var duplicated = suggestionEdit(first.suggestions().getFirst(), SuggestionStatus.ACCEPTED);
        assertInvalid(() -> service.save(first.id(), payload(first, first.requirements(), List.of(duplicated, duplicated))));
        assertInvalid(() -> service.save(first.id(), payload(first, first.requirements(), suggestions(second))));
        assertThat(service.get(first.id()).requirements()).hasSize(2);
        assertThat(service.versions(first.id())).hasSize(1);
    }

    /** 新项目使用隔离的稳定 ID。 */
    private ProjectDocument create() { return service.create(new CreateProjectRequest("测试项目", "测试需求输入")); }
    /** 构造完整编辑请求，不允许改写来源。 */
    private SaveProjectRequest payload(ProjectDocument document, List<RequirementItem> requirements,
            List<SaveProjectRequest.SuggestionEdit> suggestions) {
        return new SaveProjectRequest(document.revision(), document.name(), document.title(), document.overview(),
                requirements.stream().map(item -> new SaveProjectRequest.RequirementEdit(item.id(), item.text(), item.status())).toList(),
                document.questions().stream().map(item -> new SaveProjectRequest.QuestionEdit(item.id(), item.answer())).toList(), suggestions);
    }
    /** 复制当前建议处理状态。 */
    private List<SaveProjectRequest.SuggestionEdit> suggestions(ProjectDocument document) {
        return document.suggestions().stream().map(item -> suggestionEdit(item, item.status())).toList();
    }
    /** 构造指定状态的建议输入。 */
    private SaveProjectRequest.SuggestionEdit suggestionEdit(DesignSuggestion item, SuggestionStatus status) {
        return new SaveProjectRequest.SuggestionEdit(item.id(), item.text(), status);
    }
    /** 关联错误必须返回输入错误，不推进保存版本。 */
    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ProjectException.class,
                exception -> assertThat(exception.code()).isEqualTo("INVALID_INPUT"));
    }
}
