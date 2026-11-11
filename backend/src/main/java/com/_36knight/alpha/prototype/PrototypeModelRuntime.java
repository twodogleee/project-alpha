package com._36knight.alpha.prototype;

import com._36knight.alpha.model.ModelCatalogService;
import com._36knight.alpha.model.ModelChatClientFactory;
import com._36knight.alpha.model.ModelConfigurationSettings;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 固定当前启用模型执行原型文本调用；密钥仅进入后端客户端内存。@author Andersen */
@Service
public class PrototypeModelRuntime implements PrototypeModelProvider {
    /** 单次请求最长三分钟，修复不额外获得无限调用预算。 */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(180);
    /** 允许完整文件 JSON 的有界输入，超过限制不截断源码。 */
    private static final int INPUT_BYTES = 12 * 1024 * 1024;
    /** 模型响应文本上限，工作流仍执行更严格的文件级限制。 */
    private static final int OUTPUT_BYTES = 8 * 1024 * 1024;
    /** 持久化的当前模型选择，只能从私有配置解密凭证。 */
    private final ModelCatalogService catalog;
    /** 与需求流程共用的固定平台适配。 */
    private final ModelChatClientFactory factory;
    /** 单次调用的硬时限；非公开构造器允许快速验证超时。 */
    private final Duration timeout;

    /** 注入配置与客户端工厂；构造不发起模型请求。 */
    @Autowired
    public PrototypeModelRuntime(ModelCatalogService catalog, ModelChatClientFactory factory) {
        this(catalog, factory, CALL_TIMEOUT);
    }

    /** 测试使用较短时限，生产固定采用三分钟限制。 */
    PrototypeModelRuntime(ModelCatalogService catalog, ModelChatClientFactory factory, Duration timeout) {
        this.catalog = catalog;
        this.factory = factory;
        this.timeout = timeout;
    }

    /** 配置不可用或处于联调模式时拒绝，不尝试模型连接也不回退本地样板。 */
    @Override
    public boolean available() {
        try {
            return live(catalog.activeSettings());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** 当前配置只读取一次，后续配置切换不改变本任务的模型身份。 */
    @Override
    public Session snapshot() {
        try {
            ModelConfigurationSettings settings = catalog.activeSettings();
            if (!live(settings)) throw unavailable();
            return new FixedSession(settings.model(), factory.create(settings), timeout);
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
    }

    /** 创建真实调用需要明确 AI 模式、模型身份和服务端凭证。 */
    private static boolean live(ModelConfigurationSettings settings) {
        return settings != null && "ai".equals(settings.mode()) && settings.model() != null
            && !settings.model().isBlank() && settings.model().length() <= 120
            && settings.apiKey() != null && !settings.apiKey().isBlank();
    }

    /** 配置异常不公开接入地址、密钥或底层配置正文。 */
    private static PrototypeModelException unavailable() {
        return new PrototypeModelException("PROTOTYPE_MODEL_UNAVAILABLE", "原型生成需要启用有效的真实模型配置");
    }

    /** 固定客户端仅支持串行调用；关闭与取消可以跨线程执行。 */
    private static final class FixedSession implements Session {
        /** 当次固定的实际模型名。 */
        private final String model;
        /** 持有后端凭证的不可序列化客户端。 */
        private final ChatClient client;
        /** 调用最长等待时间。 */
        private final Duration timeout;
        /** 虚拟线程运行同步 SDK，关闭不等待不可控的上游响应。 */
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        /** 关闭标记防止结束后继续计费调用。 */
        private final AtomicBoolean closed = new AtomicBoolean();
        /** 串行限制防止修复与前一次模型调用重叠。 */
        private final AtomicBoolean generating = new AtomicBoolean();
        /** 取消句柄不含模型原文或凭证。 */
        private final AtomicReference<Future<String>> current = new AtomicReference<>();

        /** 固定客户端和模型身份，后续不再读取配置表。 */
        private FixedSession(String model, ChatClient client, Duration timeout) {
            this.model = model;
            this.client = client;
            this.timeout = timeout;
        }

        /** 生产适配器从不返回合成或样板来源。 */
        @Override public String mode() { return "live"; }

        /** 模型身份与本客户端使用的配置一致。 */
        @Override public String model() { return model; }

        /** 最长等待与输出上限同时强制，任何失败都不返回截断内容。 */
        @Override
        public String generate(String system, String user) {
            if (closed.get()) throw interrupted();
            if (system == null || system.isBlank() || user == null
                    || (long) system.getBytes(StandardCharsets.UTF_8).length + user.getBytes(StandardCharsets.UTF_8).length > INPUT_BYTES) {
                throw new PrototypeModelException("PROTOTYPE_MODEL_OUTPUT_INVALID", "原型模型输入超过限制或缺少系统约束");
            }
            if (!generating.compareAndSet(false, true)) {
                throw new PrototypeModelException("PROTOTYPE_MODEL_FAILED", "同一原型任务不允许并发调用模型");
            }
            Future<String> call = null;
            try {
                if (closed.get()) throw interrupted();
                call = executor.submit(() -> client.prompt().system(system).user(user).call().content());
                current.set(call);
                if (closed.get()) {
                    call.cancel(true);
                    throw interrupted();
                }
                String text = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (text == null || text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length > OUTPUT_BYTES) {
                    throw new PrototypeModelException("PROTOTYPE_MODEL_OUTPUT_INVALID", "模型返回空内容或超过输出上限");
                }
                return text;
            } catch (TimeoutException timeoutFailure) {
                close();
                throw new PrototypeModelException("PROTOTYPE_MODEL_TIMEOUT", "原型模型调用超过三分钟限制");
            } catch (InterruptedException interruptedFailure) {
                Thread.currentThread().interrupt();
                close();
                throw interrupted();
            } catch (CancellationException cancelled) {
                throw interrupted();
            } catch (ExecutionException failed) {
                // 不传播 SDK 的异常消息、cause 或 suppressed，防止网关回显凭证。
                throw new PrototypeModelException("PROTOTYPE_MODEL_FAILED", "原型模型请求失败，请检查平台连接、模型权限或额度");
            } catch (PrototypeModelException safe) {
                throw safe;
            } catch (RuntimeException failed) {
                throw new PrototypeModelException("PROTOTYPE_MODEL_FAILED", "原型模型客户端无法执行请求");
            } finally {
                if (call != null) current.compareAndSet(call, null);
                generating.set(false);
            }
        }

        /** 取消当次 HTTP 请求，并阻止后续修复继续使用结束的客户端。 */
        @Override
        public void close() {
            closed.set(true);
            Future<String> call = current.get();
            if (call != null) call.cancel(true);
            executor.shutdownNow();
        }

        /** 固定中断诊断不附加线程异常或请求正文。 */
        private static PrototypeModelException interrupted() {
            return new PrototypeModelException("PROTOTYPE_INTERRUPTED", "原型模型调用已中断");
        }
    }
}
