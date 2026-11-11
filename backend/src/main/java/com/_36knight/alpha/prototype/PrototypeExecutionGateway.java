package com._36knight.alpha.prototype;

/** P4 执行适配器边界；受信执行器连接模型与 Worker，测试必须显式标记合成来源。@author Andersen */
public interface PrototypeExecutionGateway {
    /** 只检查是否能够接收任务，不能触发模型、文件写入或数据库副作用。 */
    boolean available();
    /** 提交事务完成后派发身份；适配器通过 PrototypeService 领取执行令牌。 */
    void dispatch(String runId);
}
