package com._36knight.alpha.prototype;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 单实例后台租约回收；只使过期任务终结，不恢复 Graph、不重放生成命令。@author Andersen */
@Configuration
@EnableScheduling
public class PrototypeLeaseRecovery {
    /** 回收仍经过应用服务和项目根锁。 */ private final PrototypeService prototypes;
    /** 注入事务服务。 */ public PrototypeLeaseRecovery(PrototypeService prototypes){this.prototypes=prototypes;}
    /** 最多三十秒发现失联；真实 Worker 的进程清理由 P4 执行适配器负责。 */
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void recover(){prototypes.recoverExpiredRuns();}
}
