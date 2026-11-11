package com._36knight.alpha.prototype;

/** 原型工作流的模型边界；生产适配与显式合成测试使用不同来源标识。@author Andersen */
public interface PrototypeModelProvider {
    /** 只读判断当前配置能否创建真实模型会话，不发起模型调用。 */
    boolean available();

    /** 固定当次模型及凭证；调用方必须关闭会话以取消仍在执行的请求。 */
    Session snapshot();

    /** 单个任务复用的不可变客户端，源码和模型文本不会进入诊断日志。 */
    interface Session extends AutoCloseable {
        /** 生产会话固定为 live；合成测试必须显式返回 synthetic。 */
        String mode();

        /** 领取任务时记录的实际模型身份，不包含平台凭证或接入地址。 */
        String model();

        /** 有界调用返回原始文本，JSON 结构与源码内容由工作流继续验证。 */
        String generate(String system, String user);

        /** 终止尚未完成的调用；关闭后不允许继续生成。 */
        @Override
        void close();
    }
}
