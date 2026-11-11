package com._36knight.alpha.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 沿用 Zero 的 Mapper 与 PostgreSQL 分页技术；不宣称已接入数据权限。
 *
 * @author Andersen
 */
@Configuration
@MapperScan({"com._36knight.alpha.project.mapper", "com._36knight.alpha.model.mapper", "com._36knight.alpha.workspace.mapper", "com._36knight.alpha.template.mapper", "com._36knight.alpha.prototype.mapper"})
public class AlphaMybatisPlusConfiguration {
    /**
     * 创建 PostgreSQL 分页拦截器，业务冲突仍由 revision 条件更新负责。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        var interceptor = new MybatisPlusInterceptor();
        var pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
