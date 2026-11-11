package com._36knight.alpha.agent;

/**
 * 获取当前配置的单次运行副本，隔离 Graph 与配置存储。@author Andersen
 */
public interface AnalysisRuntimeProvider {
    /**
     * 每个生成请求调用一次，进行中的上下文不会随保存变化。
     */
    AnalysisRuntime snapshot();
}
