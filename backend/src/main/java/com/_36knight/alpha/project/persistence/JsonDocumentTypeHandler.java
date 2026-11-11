package com._36knight.alpha.project.persistence;

import java.sql.*;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * 将已序列化业务快照绑定为 PostgreSQL JSONB；参数绑定不拼接正文、不输出日志。
 * 测试库使用 TEXT 列，验证同一 Mapper 与事务边界。
 * @author Andersen
 */
public class JsonDocumentTypeHandler extends BaseTypeHandler<String> {
    /** 以 OTHER 绑定 JSON，让 PostgreSQL 驱动按目标 JSONB 类型接收。 */
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, String value, JdbcType jdbcType) throws SQLException {
        statement.setObject(index, value, Types.OTHER);
    }
    /** 按列名读取 JSON 字符串，由应用 ObjectMapper 解析业务对象。 */
    @Override
    public String getNullableResult(ResultSet rows, String column) throws SQLException { return rows.getString(column); }
    /** 按列序号读取快照。 */
    @Override
    public String getNullableResult(ResultSet rows, int index) throws SQLException { return rows.getString(index); }
    /** 支持 MyBatis 可调用语句的标准读取契约。 */
    @Override
    public String getNullableResult(CallableStatement statement, int index) throws SQLException { return statement.getString(index); }
}
