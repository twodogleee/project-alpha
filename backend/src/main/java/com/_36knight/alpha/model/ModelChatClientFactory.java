package com._36knight.alpha.model;

import org.springframework.ai.chat.client.ChatClient;

/** 需求与原型共用的平台客户端装配，不负责业务提示词或输出解析。@author Andersen */
@FunctionalInterface
public interface ModelChatClientFactory {
    /** 仅创建绑定私有配置的客户端，不发送请求，不允许联调模式伪装真实模型。 */
    ChatClient create(ModelConfigurationSettings settings);
}
