package com._36knight.alpha;

import com._36knight.alpha.workspace.LifecycleRepository;
import com._36knight.alpha.workspace.PageRuleContracts.Query;
import com._36knight.alpha.workspace.PageRuleContracts.Status;
import com._36knight.alpha.workspace.PageRuleQueryService;
import com._36knight.alpha.workspace.WorkspaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 数据库不可用的边界测试；错误不能被解释为“未定义规则”。@author Andersen */
class PageRuleQueryServiceTest {
    /** 固定仓储故障，检查结果不包含连接错误或原始异常。 */
    @Test void returnsUnavailableWithoutLeakingDatabaseFailure() {
        var workspace = mock(WorkspaceRepository.class);
        var lifecycle = mock(LifecycleRepository.class);
        when(workspace.version("version")).thenThrow(new DataAccessResourceFailureException("private connection detail"));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var service = new PageRuleQueryService(workspace, lifecycle, new ObjectMapper(), factory.getValidator());
            var result = service.query("project", "version", new Query(1L, "personnel", "list", "search"));
            assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
            assertThat(result.message()).doesNotContain("private");
            assertThat(result.source()).isNull();
            assertThat(result.text()).isEmpty();
            verifyNoInteractions(lifecycle);
        }
    }
}
