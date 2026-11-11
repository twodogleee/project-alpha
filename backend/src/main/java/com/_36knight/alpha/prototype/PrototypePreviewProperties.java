package com._36knight.alpha.prototype;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 本机受控预览的来源、时效与资源限制；监听地址固定 loopback。@author Andersen */
@Component
@ConfigurationProperties(prefix = "alpha.prototype.preview")
public class PrototypePreviewProperties {
    /** Alpha 页面来源白名单，不从请求 Host、Origin 或转发头反射。 */
    private List<String> allowedOrigins = List.of("http://127.0.0.1:18080", "http://127.0.0.1:5178");
    /** 默认随机端口，避免与业务服务共享 origin。 */
    private int port;
    /** 会话有效期，默认十五分钟，上限一小时。 */
    private Duration ttl = Duration.ofMinutes(15);
    /** 本进程同时保留的会话上限。 */
    private int maxSessions = 8;
    /** 全部会话静态快照总字节上限，默认 100 MiB。 */
    private long maxBytes = 100L * 1024 * 1024;

    /** 返回允许嵌入预览的 Alpha origins。 */
    public List<String> getAllowedOrigins() { return allowedOrigins; }
    /** 环境显式配置来源，启动校验拒绝含路径或凭证的地址。 */
    public void setAllowedOrigins(List<String> value) { allowedOrigins = value; }
    /** 返回独立服务端口，零表示由操作系统分配。 */
    public int getPort() { return port; }
    /** 只允许绑定本机，端口在服务初始化时检查。 */
    public void setPort(int value) { port = value; }
    /** 返回请求会话时固定的有效期。 */
    public Duration getTtl() { return ttl; }
    /** 配置时效不会延长已创建会话。 */
    public void setTtl(Duration value) { ttl = value; }
    /** 返回进程级会话数量上限。 */
    public int getMaxSessions() { return maxSessions; }
    /** 数量限额在创建快照前检查。 */
    public void setMaxSessions(int value) { maxSessions = value; }
    /** 返回进程级字节限额。 */
    public long getMaxBytes() { return maxBytes; }
    /** 内存配额在快照读取前后都检查。 */
    public void setMaxBytes(long value) { maxBytes = value; }
}
