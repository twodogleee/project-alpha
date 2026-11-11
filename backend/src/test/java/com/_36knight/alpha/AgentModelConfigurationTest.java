package com._36knight.alpha;

import com._36knight.alpha.config.AgentModelConfiguration;
import com._36knight.alpha.model.ModelConfigurationSettings;
import com._36knight.alpha.model.ModelConfigurationException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证配置错误不会静默切换到本地模板模式。 */
class AgentModelConfigurationTest {
    /** 云模式缺少密钥时给出配置错误，不创建模拟模型。 */
    @Test
    void rejectsCloudModeWithoutKey() {
        assertThatThrownBy(() -> new AgentModelConfiguration().modelAnalyzerFactory().create(
                new ModelConfigurationSettings("ai", "dashscope", "qwen-plus", "https://dashscope.aliyuncs.com", "")))
                .isInstanceOf(ModelConfigurationException.class).hasMessageContaining("API Key");
    }

    /** 拼错模式名称时拒绝创建客户端。 */
    @Test
    void rejectsUnknownMode() {
        assertThatThrownBy(() -> new AgentModelConfiguration().modelAnalyzerFactory().create(
                new ModelConfigurationSettings("unknown", "dashscope", "qwen-plus", "https://dashscope.aliyuncs.com", "")))
                .isInstanceOf(ModelConfigurationException.class).hasMessageContaining("模式");
    }
}
