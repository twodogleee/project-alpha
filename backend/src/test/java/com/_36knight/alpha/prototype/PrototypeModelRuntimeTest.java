package com._36knight.alpha.prototype;

import com._36knight.alpha.model.ModelCatalogService;
import com._36knight.alpha.model.ModelChatClientFactory;
import com._36knight.alpha.model.ModelConfigurationSettings;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 使用明确合成 ChatModel 验证客户端边界，不调用真实平台或伪造生成验收。@author Andersen */
class PrototypeModelRuntimeTest {
    /** 合成凭证只用于检查错误隔离，不连接任何网络。 */
    private static final String SECRET = "synthetic-private-key-never-print";

    /** 联调配置不可生成原型，也不创建真实模型客户端。 */
    @Test
    void rejectsDemoWithoutCreatingClient() {
        ModelCatalogService catalog = mock(ModelCatalogService.class);
        ModelChatClientFactory factory = mock(ModelChatClientFactory.class);
        when(catalog.activeSettings()).thenReturn(new ModelConfigurationSettings("demo", "custom", "local-template", "", ""));
        PrototypeModelRuntime runtime = new PrototypeModelRuntime(catalog, factory);
        assertThat(runtime.available()).isFalse();
        assertThatThrownBy(runtime::snapshot).isInstanceOf(PrototypeModelException.class)
            .extracting(error -> ((PrototypeModelException) error).code()).isEqualTo("PROTOTYPE_MODEL_UNAVAILABLE");
        verifyNoInteractions(factory);
    }

    /** 会话只读取一次配置，后续切换模型不会改变正在生成任务的身份。 */
    @Test
    void pinsModelAndClientForWholeSession() {
        ModelCatalogService catalog = mock(ModelCatalogService.class);
        ModelChatClientFactory factory = mock(ModelChatClientFactory.class);
        ChatModel model = mock(ChatModel.class);
        when(catalog.activeSettings()).thenReturn(settings("model-one"), settings("model-two"));
        ChatClient client = ChatClient.create(model);
        when(factory.create(any())).thenReturn(client);
        when(model.call(any(Prompt.class))).thenReturn(response("{\"plan\":true}"));
        try (PrototypeModelProvider.Session session = new PrototypeModelRuntime(catalog, factory).snapshot()) {
            assertThat(session.mode()).isEqualTo("live");
            assertThat(session.model()).isEqualTo("model-one");
            assertThat(session.generate("system constraint", "first input")).isEqualTo("{\"plan\":true}");
            assertThat(session.generate("same constraint", "repair input")).isEqualTo("{\"plan\":true}");
            verify(catalog, times(1)).activeSettings();
            verify(factory, times(1)).create(settings("model-one"));
        }
    }

    /** 原始上游异常可能回显密钥和提示词，公开异常完全丢弃消息和原因链。 */
    @Test
    void neverExposesUpstreamFailureOrCredentials() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("Authorization: Bearer " + SECRET));
        try (PrototypeModelProvider.Session session = runtime(model, Duration.ofSeconds(1)).snapshot()) {
            assertThatThrownBy(() -> session.generate("system", "user private text"))
                .isInstanceOf(PrototypeModelException.class).hasNoCause()
                .hasMessageNotContaining(SECRET).hasMessageNotContaining("Authorization")
                .extracting(error -> ((PrototypeModelException) error).code()).isEqualTo("PROTOTYPE_MODEL_FAILED");
        }
    }

    /** 输出超限直接拒绝，不能把截断 JSON 继续交给源码解析器。 */
    @Test
    void rejectsOversizedOrEmptyOutput() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response("x".repeat(8 * 1024 * 1024 + 1)), response("  "));
        try (PrototypeModelProvider.Session session = runtime(model, Duration.ofSeconds(1)).snapshot()) {
            assertThatThrownBy(() -> session.generate("system", "user"))
                .isInstanceOf(PrototypeModelException.class)
                .extracting(error -> ((PrototypeModelException) error).code()).isEqualTo("PROTOTYPE_MODEL_OUTPUT_INVALID");
            assertThatThrownBy(() -> session.generate("system", "user"))
                .isInstanceOf(PrototypeModelException.class);
        }
    }

    /** 输入以 UTF-8 字节计数，超量时不发出模型调用。 */
    @Test
    void rejectsOversizedInputBeforeCallingModel() {
        ChatModel model = mock(ChatModel.class);
        try (PrototypeModelProvider.Session session = runtime(model, Duration.ofSeconds(1)).snapshot()) {
            assertThatThrownBy(() -> session.generate("system", "中".repeat(5 * 1024 * 1024)))
                .isInstanceOf(PrototypeModelException.class);
            verify(model, never()).call(any(Prompt.class));
        }
    }

    /** 三分钟硬时限用短预算验证，超时取消底层工作并封闭本次会话。 */
    @Test
    void timeoutCancelsRequestAndClosesSession() throws Exception {
        ChatModel model = mock(ChatModel.class);
        CountDownLatch cancelled = new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            try { Thread.sleep(30_000); }
            catch (InterruptedException failure) { cancelled.countDown(); throw new IllegalStateException("cancelled"); }
            return response("late");
        });
        try (PrototypeModelProvider.Session session = runtime(model, Duration.ofMillis(80)).snapshot()) {
            assertThatThrownBy(() -> session.generate("system", "user"))
                .isInstanceOf(PrototypeModelException.class)
                .extracting(error -> ((PrototypeModelException) error).code()).isEqualTo("PROTOTYPE_MODEL_TIMEOUT");
            assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> session.generate("system", "later"))
                .extracting(error -> ((PrototypeModelException) error).code()).isEqualTo("PROTOTYPE_INTERRUPTED");
        }
    }

    /** 租约丢失时允许另一线程关闭会话，使等待中的模型调用及时退出。 */
    @Test
    void closeCancelsActiveRequestAndRejectsConcurrentCall() throws Exception {
        ChatModel model = mock(ChatModel.class);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            started.countDown();
            try { Thread.sleep(30_000); }
            catch (InterruptedException failure) { cancelled.set(true); throw new IllegalStateException("cancelled"); }
            return response("late");
        });
        PrototypeModelProvider.Session session = runtime(model, Duration.ofSeconds(2)).snapshot();
        CompletableFuture<String> invocation = CompletableFuture.supplyAsync(() -> session.generate("system", "user"));
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> session.generate("system", "parallel"))
                .extracting(error -> ((PrototypeModelException) error).code()).isEqualTo("PROTOTYPE_MODEL_FAILED");
            session.close();
            assertThatThrownBy(() -> invocation.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(PrototypeModelException.class);
        } finally {
            session.close();
        }
    }

    /** 创建真实 runtime 配合合成模型，只替换平台调用边界。 */
    private PrototypeModelRuntime runtime(ChatModel model, Duration timeout) {
        ModelCatalogService catalog = mock(ModelCatalogService.class);
        when(catalog.activeSettings()).thenReturn(settings("test-model"));
        return new PrototypeModelRuntime(catalog, ignored -> ChatClient.create(model), timeout);
    }

    /** 合成配置使用不可解析的示例地址，测试客户端不读取该地址。 */
    private ModelConfigurationSettings settings(String model) {
        return new ModelConfigurationSettings("ai", "custom", model, "https://example.invalid", SECRET);
    }

    /** 使用 Spring AI 实际响应结构验证 ChatClient 的文本转换。 */
    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
