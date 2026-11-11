package com._36knight.alpha;

import com._36knight.alpha.project.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import static org.assertj.core.api.Assertions.*;

/**
 * 文件库重开和真实并发事务，验证原子保存而非只做顺序请求。
 * @author Andersen
 */
class ProjectPersistenceTest {
    /** 测试业务文件隔离于开发 data 目录。 */
    @TempDir Path directory;

    /** 关闭全部连接后重新打开文件库，数据与历史仍存在。 */
    @Test
    void survivesClosingAndReopeningDatabase() {
        String url = "jdbc:h2:file:" + directory.resolve("projects");
        ProjectDocument document = fixture(1, "首版");
        try (var context = database(url)) { context.getBean(ProjectRepository.class).insert(document); }
        try (var context = database(url)) {
            ProjectRepository repository = context.getBean(ProjectRepository.class);
            assertThat(repository.get(document.id())).isEqualTo(document);
            assertThat(repository.version(document.id(), 1)).isEqualTo(document);
            repository.update(fixture(2, "新版"), 1);
        }
        try (var context = database(url)) {
            var repository = context.getBean(ProjectRepository.class);
            assertThat(repository.get(document.id()).name()).isEqualTo("新版");
            assertThat(repository.version(document.id(), 1)).isEqualTo(document);
            assertThat(repository.versions(document.id())).hasSize(2);
        }
    }

    /** 同时提交相同基准版本仅一个成功，另一个冲突且不追加历史。 */
    @Test
    void concurrentWritersCannotOverwriteEachOther() throws Exception {
        try (var context = database("jdbc:h2:file:" + directory.resolve("concurrent"));
                var executor = Executors.newFixedThreadPool(2)) {
            var repository = context.getBean(ProjectRepository.class);
            repository.insert(fixture(1, "原始"));
            var start = new CountDownLatch(1);
            Future<Boolean> first = executor.submit(() -> updateTogether(repository, start, "窗口甲"));
            Future<Boolean> second = executor.submit(() -> updateTogether(repository, start, "窗口乙"));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(repository.get("project-id").name()).isIn("窗口甲", "窗口乙");
            assertThat(repository.versions("project-id")).hasSize(2);
        }
    }

    /** 新版本插入失败时，当前文档更新必须回滚。 */
    @Test
    void versionFailureRollsBackCurrentDocument() {
        try (var context = database("jdbc:h2:file:" + directory.resolve("rollback"))) {
            var repository = context.getBean(ProjectRepository.class);
            ProjectDocument original = fixture(1, "原始");
            repository.insert(original);
            // 刻意重复项目内 revision 唯一键，触发第二条 SQL 失败。
            assertThatThrownBy(() -> repository.update(fixture(1, "不应保存"), 1))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(repository.get("project-id")).isEqualTo(original);
            assertThat(repository.versions("project-id")).hasSize(1);
        }
    }

    /** 等待起跑信号并只将版本冲突转换为失败标识。 */
    private boolean updateTogether(ProjectRepository repository, CountDownLatch start, String name) throws Exception {
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("并发测试等待超时");
        try { repository.update(fixture(2, name), 1); return true; }
        catch (ProjectException exception) {
            assertThat(exception.code()).isEqualTo("VERSION_CONFLICT");
            return false;
        }
    }

    /** 创建真实事务代理和文件数据库；关闭上下文后连接全部释放。 */
    private ConfigurableApplicationContext database(String url) {
        return new SpringApplicationBuilder(AlphaApplication.class).web(WebApplicationType.NONE).run(
                "--spring.datasource.dynamic.datasource.master.url=" + url,
                "--spring.datasource.dynamic.datasource.master.driver-class-name=org.h2.Driver",
                "--spring.datasource.dynamic.datasource.master.username=sa",
                "--spring.datasource.dynamic.datasource.master.password=",
                "--spring.flyway.enabled=false", "--spring.sql.init.mode=always",
                "--spring.sql.init.schema-locations=classpath:db/test-schema.sql",
                "--alpha.ai.mode=demo", "--alpha.ai.api-key=",
                "--logging.level.root=WARN");
    }

    /** 固定快照用于文件重开及原子更新验证。 */
    private ProjectDocument fixture(long revision, String name) {
        Instant time = Instant.parse("2026-10-12T00:00:00Z");
        return new ProjectDocument("project-id", name, "原始需求", "demo", "run-id", revision, time, time,
                "标题", "概述", List.of(), List.of(), List.of(), "PRD " + name);
    }
}
