package com._36knight.alpha.prototype;

import java.nio.file.Path;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 模型生成执行环境的后端配置；HTTP 命令不能指定程序、脚本或宿主路径。@author Andersen */
@Component
@ConfigurationProperties(prefix = "alpha.prototype.generation")
public class PrototypeGenerationProperties {
    /** 是否允许真实模型生成；仍须模型及 Worker 能力探测通过。 */
    private boolean enabled = true;
    /** 固定 Node 可执行程序，允许部署配置使用绝对路径。 */
    private String nodeExecutable = "node";
    /** 与当前应用版本一同交付的受信 Worker 目录。 */
    private Path workerDirectory = Path.of("prototype-worker");
    /** 固定 zero-admin@2 组件契约所在目录，不能由模型选择。 */
    private Path templateDirectory = Path.of("prototype-template/zero-admin");

    /** 可选的本机 Chromium 可执行文件；空值交由 Worker 搜索已安装版本。 */
    private String chromiumExecutable = "";

    /** Spring 配置绑定采用仓库根目录启动约定。 */
    public PrototypeGenerationProperties() { }
    /** 查询平台开关。 */
    public boolean isEnabled() { return enabled; }
    /** 仅影响后续任务；运行中输入始终固定。 */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /** 返回平台指定的 Node 程序。 */
    public String getNodeExecutable() { return nodeExecutable; }
    /** 不接受空程序名，也不通过 shell 解释程序名。 */
    public void setNodeExecutable(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Node 程序不能为空");
        nodeExecutable = value;
    }
    /** 返回受信 Worker 目录。 */
    public Path getWorkerDirectory() { return workerDirectory; }
    /** 目录由后端配置绑定。 */
    public void setWorkerDirectory(Path value) { workerDirectory = Objects.requireNonNull(value); }
    /** 返回固定模板目录。 */
    public Path getTemplateDirectory() { return templateDirectory; }
    /** 目录只用于读取受信组件契约。 */
    public void setTemplateDirectory(Path value) { templateDirectory = Objects.requireNonNull(value); }
    /** 返回仅用于本机探测和验证的浏览器路径。 */
    public String getChromiumExecutable() { return chromiumExecutable; }
    /** 显式选择本机浏览器，不经过 shell。 */
    public void setChromiumExecutable(String value) { chromiumExecutable = Objects.requireNonNull(value).strip(); }
}
