package com._36knight.alpha;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Alpha 产品经理 Agent 的应用入口。
 * @author Andersen
 */
@SpringBootApplication
public class AlphaApplication {
    /** 启动工作台接口与 Agent 工作流。 */
    public static void main(String[] args) {
        SpringApplication.run(AlphaApplication.class, args);
    }
}
