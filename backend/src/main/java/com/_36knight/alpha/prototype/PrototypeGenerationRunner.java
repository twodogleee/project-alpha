package com._36knight.alpha.prototype;

import com._36knight.alpha.project.ProjectException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** P4 单槽异步执行器：固定模型与来源、两轮内修复、真实 Worker 验证后调用 P3 事务。@author Andersen */
@Component
public class PrototypeGenerationRunner implements PrototypeExecutionGateway {
    /** 只记录任务身份、固定分类与异常类型，不记录源码、请求、凭证或异常正文。 */
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PrototypeGenerationRunner.class);
    /** 懒取应用服务，避免提交服务与执行适配器形成构造循环。 */
    private final ObjectProvider<PrototypeService> services;
    /** 模型快照只在执行线程持有，绝不进入 Graph 状态或数据库。 */
    private final PrototypeModelProvider models;
    /** 无状态 Graph 组织计划与受限代码生成。 */
    private final PrototypeWorkflow workflow;
    /** 宿主编译 Worker 桥接。 */
    private final PrototypeBuildService builds;
    /** 基准源码读取与完成后的独立文件复核。 */
    private final PrototypeArtifactStore artifacts;
    /** 平台开关不由命令参数控制。 */
    private final PrototypeGenerationProperties settings;
    /** 业务清单由平台组装，不接受模型自报来源身份。 */
    private final ObjectMapper json;
    /** 单实例只接收一个执行，拒绝排队耗尽 P3 排队租约。 */
    private final AtomicBoolean busy = new AtomicBoolean();
    /** 无请求线程占用的后台执行槽。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("prototype-generation").factory());
    /** 独立心跳不被模型或构建调用阻塞。 */
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("prototype-heartbeat").factory());

    /** 将长任务能力与业务事务分开装配。 */
    public PrototypeGenerationRunner(ObjectProvider<PrototypeService> services, PrototypeModelProvider models,
            PrototypeWorkflow workflow, PrototypeBuildService builds, PrototypeArtifactStore artifacts,
            PrototypeGenerationProperties settings, ObjectMapper json) {
        this.services = services; this.models = models; this.workflow = workflow; this.builds = builds;
        this.artifacts = artifacts; this.settings = settings; this.json = json;
    }

    /** 只读当前配置及探测缓存；提交事务不调用模型、不启动外部程序。 */
    @Override public boolean available() {
        return settings.isEnabled() && !busy.get() && models.available() && builds.available();
    }

    /** 有真实模型配置才探测工具链，联调模式和普通单元测试不会启动原型浏览器。 */
    @Scheduled(fixedDelay = 30000, initialDelay = 1000)
    public void refreshCapability() {
        if (settings.isEnabled() && !busy.get() && models.available()) builds.refreshCapability();
    }

    /** 仅在提交事务完成后派发；全局竞争失败由调用方终结已提交的排队任务。 */
    @Override public void dispatch(String runId) {
        if (!settings.isEnabled() || !busy.compareAndSet(false, true)) throw new IllegalStateException("原型执行槽不可用");
        try { executor.execute(() -> execute(runId)); }
        catch (RuntimeException failure) { busy.set(false); throw failure; }
    }

    /** 所有外部调用在事务外；失败只提交安全分类，源代码和诊断不进入任务日志。 */
    private void execute(String runId) {
        PrototypeService service = services.getObject();
        PrototypeContracts.Execution execution = null;
        ScheduledFuture<?> heartbeat = null;
        Progress progress = null;
        try (PrototypeModelProvider.Session model = models.snapshot()) {
            execution = service.claim(runId, model.mode(), model.model());
            if (execution == null) return;
            progress = new Progress(service, execution, Thread.currentThread());
            heartbeat = heartbeats.scheduleAtFixedRate(progress::pulse, 10, 10, TimeUnit.SECONDS);
            List<PrototypeWorkflow.SourceFile> baseFiles = execution.baseObjectKey() == null ? List.of()
                    : artifacts.generatedFiles(execution.baseObjectKey()).entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).map(entry -> new PrototypeWorkflow.SourceFile(entry.getKey(), entry.getValue())).toList();
            var input = new PrototypeWorkflow.Input(execution, execution.operation(), baseFiles,
                    execution.baseManifest(), builds.componentContract());
            PrototypeWorkflow.Output output = null;
            String diagnostic = "";
            int repairs = 0;
            while (true) {
                progress.update("PLANNING", repairs, output == null ? null : output.plan());
                try {
                    output = repairs == 0 ? workflow.execute(input, model, progress::phase)
                            : workflow.repair(input, model, output, diagnostic);
                    progress.update("GENERATING", repairs, output.plan());
                    if (unchanged(input, output)) {
                        service.completeUnchanged(runId, execution.token());
                        return;
                    }
                    progress.update("BUILDING", repairs, output.plan());
                    String objectKey = builds.build(output, model.mode());
                    // 构建接口一次完成隔离编译与浏览器验证，只有返回 PASSED 后才能进入持久化。
                    progress.update("VERIFYING", repairs, output.plan());
                    progress.update("PERSISTING", repairs, output.plan());
                    service.complete(runId, execution.token(), new PrototypeContracts.Completion(objectKey,
                            manifest(execution, output), output.changeSummary()));
                    return;
                } catch (PrototypeGenerationException invalid) {
                    if (repairs >= 2) throw invalid;
                    diagnostic = invalid.diagnostic();
                } catch (PrototypeModelException invalid) {
                    if (!"PROTOTYPE_MODEL_OUTPUT_INVALID".equals(invalid.code()) || repairs >= 2) throw invalid;
                    diagnostic = "模型响应为空或超过限额，请返回完整且满足大小限制的 JSON。";
                } catch (PrototypeBuildException failed) {
                    if (!failed.repairable() || repairs >= 2) throw failed;
                    diagnostic = failed.diagnostic();
                }
                repairs++;
            }
        } catch (RuntimeException failure) {
            // 关闭或租约取消可能令当前线程带中断位；业务终态写入不应被 JDBC 的中断检查跳过。
            boolean interrupted = Thread.interrupted();
            try {
                String code = interrupted ? "PROTOTYPE_INTERRUPTED" : code(failure);
                LOG.warn("原型执行失败：runId={}, code={}, exceptionType={}", runId, code, failure.getClass().getSimpleName());
                if (execution == null) service.rejectQueued(runId, code);
                else service.fail(runId, execution.token(), code);
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        } finally {
            if (heartbeat != null) heartbeat.cancel(false);
            // 必须等待已经开始的旧心跳结束，才能把同一个执行线程交给下一个任务。
            if (progress != null) progress.close();
            busy.set(false);
        }
    }

    /** EDIT 的文件与有效元数据都相同时不造空修订；SYNC 必须记录新的来源快照。 */
    private boolean unchanged(PrototypeWorkflow.Input input, PrototypeWorkflow.Output output) {
        if (!"EDIT".equals(input.operation()) || input.baseManifest() == null) return false;
        Map<String, String> before = new TreeMap<>(), after = new TreeMap<>();
        input.baseFiles().forEach(file -> before.put(file.path(), file.content()));
        output.files().forEach(file -> after.put(file.path(), file.content()));
        return before.equals(after) && input.baseManifest().path("plan").equals(output.plan())
                && input.baseManifest().path("cases").equals(output.cases());
    }

    /** 覆盖代表已有可运行页面；业务需求语义的人工验收仍单独进行，不能由模型自报完成。 */
    private JsonNode manifest(PrototypeContracts.Execution execution, PrototypeWorkflow.Output output) {
        ObjectNode manifest = json.createObjectNode().put("schemaVersion", 1);
        manifest.set("scope", json.valueToTree(execution.source().scope()));
        manifest.set("plan", output.plan()); manifest.set("cases", output.cases());
        var coverage = manifest.putArray("coverage");
        execution.source().scope().pageRefs().forEach(page -> coverage.addObject().put("moduleId", page.moduleId())
                .put("pageId", page.pageId()).put("status", "IMPLEMENTED"));
        manifest.put("notice", "页面已通过平台构建与浏览器检查；业务语义仍需人工核实，模拟操作不写入真实业务数据。");
        return manifest;
    }

    /** 异常正文不能进入数据库或公开任务，只接受平台分类。 */
    private String code(RuntimeException failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
            if (cause instanceof InterruptedException) return "PROTOTYPE_INTERRUPTED";
        }
        if (failure instanceof PrototypeBuildException build) return build.code();
        if (failure instanceof PrototypeModelException model) return model.code();
        if (failure instanceof PrototypeGenerationException generated) return generated.code();
        if (failure instanceof ProjectException project) return project.code();
        return "PROTOTYPE_BUILD_FAILED";
    }

    /** 应用关闭时停止接收，打断模型和 Worker，P3 启动恢复负责处理未能提交的终态。 */
    @PreDestroy public void close() { executor.shutdownNow(); heartbeats.shutdownNow(); }

    /** 同一锁串行推进阶段与周期心跳，避免旧阶段心跳覆盖新阶段。 */
    private static final class Progress {
        /** 仅执行租约业务操作。 */ private final PrototypeService service;
        /** 当前固定执行身份。 */ private final PrototypeContracts.Execution execution;
        /** 失去租约时中断实际执行线程。 */ private final Thread thread;
        /** 当前单调阶段。 */ private String phase = "PREPARING";
        /** 累计修复轮数，不能超过二。 */ private int repairs;
        /** 最近一次通过结构校验的计划。 */ private JsonNode plan;
        /** 结束后不再操作可能已被下一任务复用的线程。 */ private boolean finished;

        /** 绑定本任务身份与拥有者线程。 */
        private Progress(PrototypeService service, PrototypeContracts.Execution execution, Thread thread) {
            this.service = service; this.execution = execution; this.thread = thread;
        }
        /** Graph 只报告既定阶段，不能写业务实体。 */
        private synchronized void phase(String value) { update(value, repairs, plan); }
        /** 原子更新进度并确认仍有租约。 */
        private synchronized void update(String value, int attempts, JsonNode inputPlan) {
            phase = value; repairs = attempts; plan = inputPlan;
            if (finished || Thread.currentThread().isInterrupted() || !service.heartbeat(execution.runId(), execution.token(), phase, repairs, plan))
                throw new PrototypeBuildException("PROTOTYPE_INTERRUPTED", "任务执行租约已失效");
        }
        /** 到期或数据库不可用时终止外部执行，不能继续生成无人持有的任务结果。 */
        private synchronized void pulse() {
            if (finished) return;
            try {
                if (!service.heartbeat(execution.runId(), execution.token(), phase, repairs, plan)) thread.interrupt();
            } catch (RuntimeException failure) { thread.interrupt(); }
        }
        /** 与续租使用同一锁，确保旧回调彻底退出后才释放执行槽。 */
        private synchronized void close() { finished = true; }
    }
}
