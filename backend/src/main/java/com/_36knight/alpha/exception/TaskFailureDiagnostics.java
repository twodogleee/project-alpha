package com._36knight.alpha.exception;

import com._36knight.alpha.model.ModelInvocationException;
import com._36knight.alpha.model.ModelConfigurationException;
import com._36knight.alpha.project.ProjectException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 后台任务和接口共用安全诊断，记录代码位置而非可能含正文/凭证的异常消息。@author Andersen
 */
public final class TaskFailureDiagnostics {
    /**
     * 独立日志类别，便于按任务编号检索。
     */
    private static final Logger LOG = LoggerFactory.getLogger(TaskFailureDiagnostics.class);

    /**
     * 纯工具类，不创建业务实例。
     */
    private TaskFailureDiagnostics() {
    }

    /**
     * 关联持久化任务和业务对象，不接收请求正文、平台地址或凭证。
     *
     * @param kind       CHAT、DRAFT、PRD 或接口诊断来源
     * @param runId      持久化任务编号；同步接口可空
     * @param projectId  会话所属项目；尚未读取或同步接口可空
     * @param sessionId  会话任务归属；PRD 或同步接口可空
     * @param versionId  PRD 或关联会话的需求版本；未读取时可空
     * @param baselineId PRD 固定评审基线；其他来源可空
     */
    public record Context(String kind, String runId, String projectId, String sessionId, String versionId,
                          String baselineId) {
    }

    /**
     * 关键链路开始，不记录输入内容或客户端配置。
     */
    public static void started(Context context) {
        LOG.info("任务开始：{}", context);
    }

    /**
     * 仅在持久化成功终态后记录完成，不将迟到结果报告为成功。
     */
    public static void succeeded(Context context, long startedAt) {
        LOG.info("任务成功：{}, elapsedMs={}", context, Math.max(0, (System.nanoTime() - startedAt) / 1_000_000));
    }

    /**
     * 只记录可公开原因和有界代码位置，返回带诊断编号的安全页面说明。
     */
    public static String report(Context context, long startedAt, RuntimeException exception) {
        ModelInvocationException model = null;
        StringBuilder locations = new StringBuilder();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = exception; cause != null && seen.size() < 8 && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof ModelInvocationException value) model = value;
            locations.append(cause.getClass().getSimpleName()).append(':');
            var frames = cause.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 6); i++) locations.append(" at ").append(frames[i]);
            locations.append("; ");
        }
        String category = model != null ? model.category()
            : exception instanceof ModelConfigurationException ? "MODEL_CONFIGURATION"
            : exception instanceof ProjectException ? "BUSINESS_VALIDATION"
            : exception instanceof org.springframework.dao.DataAccessException ? "STORAGE" : "UNKNOWN";
        String reason = model != null ? model.getMessage() : switch (category) {
            case "MODEL_CONFIGURATION" -> "模型配置不可用，请检查启用模型和密钥配置";
            case "BUSINESS_VALIDATION" -> "生成结果未通过业务校验，请检查结构、章节和需求覆盖";
            case "STORAGE" -> "任务存储失败，请检查数据库状态";
            default -> "任务执行异常，请按诊断编号查看日志中的异常类型和代码位置";
        };
        String diagnosticId = UUID.randomUUID().toString();
        LOG.warn("任务失败：diagnosticId={}, kind={}, runId={}, projectId={}, sessionId={}, versionId={}, baselineId={}, elapsedMs={}, category={}, upstreamStatus={}, exceptionType={}, upstreamCode={}, reason={}, locations={}",
            diagnosticId, context.kind(), context.runId(), context.projectId(), context.sessionId(), context.versionId(), context.baselineId(),
            Math.max(0, (System.nanoTime() - startedAt) / 1_000_000), category, model == null ? null : model.upstreamStatus(),
            model == null ? exception.getClass().getSimpleName() : model.exceptionType(), model == null ? "" : model.upstreamCode(), reason, locations);
        return reason + "（诊断编号：" + diagnosticId + "）";
    }
}
