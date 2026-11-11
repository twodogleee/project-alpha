package com._36knight.alpha.prototype;

import java.nio.file.Path;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 原型文件存储的后端配置；路径不来自请求，也不进入公开响应。@author Andersen */
@Component
@ConfigurationProperties(prefix = "alpha.prototype")
public class PrototypeStorageProperties {
    /** P2 封存目录的父路径，默认相对应用工作目录；数据库只保存其内的对象键。 */
    private Path artifactRoot = Path.of("data/prototypes");

    /** Spring 配置绑定保留默认的本机产物目录。 */
    public PrototypeStorageProperties() { }

    /** 返回仅限服务端使用的存储配置。 */
    public Path getArtifactRoot() { return artifactRoot; }

    /** 设置部署或隔离测试的专用根目录，不允许空配置。 */
    public void setArtifactRoot(Path artifactRoot) { this.artifactRoot = Objects.requireNonNull(artifactRoot); }
}
