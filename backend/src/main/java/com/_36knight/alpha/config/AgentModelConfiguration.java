package com._36knight.alpha.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com._36knight.alpha.agent.LocalRequirementAnalyzer;
import com._36knight.alpha.agent.SpringAiRequirementAnalyzer;
import com._36knight.alpha.model.ModelAnalyzerFactory;
import com._36knight.alpha.model.ModelChatClientFactory;
import com._36knight.alpha.model.ModelConfigurationException;
import com._36knight.alpha.model.ModelHttpTransport;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

/**
 * 按请求快照创建平台客户端，禁用自动重试，不在启动时固定云模型。
 *
 * @author Andersen
 */
@Configuration
public class AgentModelConfiguration {
    /**
     * 仅创建适配器，保存配置或创建客户端均不触发付费模型调用。
     */
    @Bean
    public ModelAnalyzerFactory modelAnalyzerFactory() {
        return settings -> {
            if ("demo".equals(settings.mode())) return new LocalRequirementAnalyzer();
            return new SpringAiRequirementAnalyzer(modelChatClientFactory().create(settings));
        };
    }

    /** 原型和需求流程共用平台协议、超时与无重试策略，不复制凭证处理逻辑。 */
    @Bean
    public ModelChatClientFactory modelChatClientFactory() {
        return settings -> {
            if (!"ai".equals(settings.mode()) || settings.apiKey() == null || settings.apiKey().isBlank()) {
                throw ModelConfigurationException.invalid("真实模型调用需要有效模式与 API Key");
            }
            // 不使用 SDK 默认的重试日志，防止上游回显正文或凭证进入日志。
            RetryTemplate retry = RetryTemplate.builder().maxAttempts(1).build();
            if ("dashscope".equals(settings.platform())) {
                DashScopeApi api = DashScopeApi.builder().apiKey(settings.apiKey()).baseUrl(settings.baseUrl())
                    .restClientBuilder(ModelHttpTransport.restClient()).build();
                var model = DashScopeChatModel.builder().dashScopeApi(api).retryTemplate(retry)
                    .defaultOptions(DashScopeChatOptions.builder().model(settings.model()).build()).build();
                return ChatClient.create(model);
            }
            if (!java.util.Set.of("openai", "deepseek", "custom").contains(settings.platform())) {
                throw ModelConfigurationException.invalid("接入平台尚未支持");
            }
            OpenAiApi api = OpenAiApi.builder().apiKey(settings.apiKey()).baseUrl(settings.baseUrl())
                .completionsPath("/chat/completions").restClientBuilder(ModelHttpTransport.restClient()).build();
            var model = OpenAiChatModel.builder().openAiApi(api).retryTemplate(retry)
                .defaultOptions(OpenAiChatOptions.builder().model(settings.model()).build()).build();
            return ChatClient.create(model);
        };
    }
}
