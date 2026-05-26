package com.office.ai.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.*;
import java.time.LocalDateTime;

/**
 * 盘外数据库 TIMESTAMPTZ 类型处理器
 *
 * 功能说明：
 * 1. 将数据库的 TIMESTAMPTZ 类型映射为 Java 的 LocalDateTime
 * 2. TIMESTAMPTZ 存储时会转换为 UTC，读取时根据 session 时区自动转换为本地时间
 * 3. LocalDateTime 不包含时区信息，正好对应这种"自动时区转换"的行为
 *
 * 使用场景：
 * - 盘外数据库的 TIMESTAMPTZ 字段
 * - 需要自动处理时区转换的场景
 *
 * 配置示例：
 * application.yml:
 *   spring:
 *     datasource:
 *       url: jdbc:panweidb://host:port/database?serverTimezone=Asia/Shanghai
 */
@MappedTypes(LocalDateTime.class)
@MappedJdbcTypes(JdbcType.TIMESTAMP_WITH_TIMEZONE)
public class PanweiTimestamptzHandler extends BaseTypeHandler<LocalDateTime> {

    /**
     * 设置参数：将 LocalDateTime 转换为数据库的 TIMESTAMP
     * 
     * @param ps PreparedStatement 对象
     * @param i 参数位置
     * @param parameter LocalDateTime 参数值
     * @param jdbcType JDBC 类型
     * @throws SQLException SQL 异常
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDateTime parameter, JdbcType jdbcType) throws SQLException {
        // 将 LocalDateTime 转换为 Timestamp
        // JDBC 驱动会自动处理时区转换（根据 serverTimezone 配置）
        ps.setObject(i, Timestamp.valueOf(parameter), Types.TIMESTAMP_WITH_TIMEZONE);
    }

    /**
     * 获取结果：从数据库的 TIMESTAMPTZ 转换为 LocalDateTime
     * 
     * @param rs ResultSet 对象
     * @param columnName 列名
     * @return LocalDateTime 对象，如果为 null 则返回 null
     * @throws SQLException SQL 异常
     */
    @Override
    public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        Object value = rs.getObject(columnName);
        if (value == null) {
            return null;
        }
        // 尝试从 OffsetDateTime 转换（timestamptz 可能被映射为 OffsetDateTime）
        if (value instanceof java.time.OffsetDateTime) {
            return ((java.time.OffsetDateTime) value).toLocalDateTime();
        }
        // 尝试从 Timestamp 转换
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime();
        }
        // 尝试从 LocalDateTime 转换（驱动可能已经转换）
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        // 其他情况尝试转换为 String 再解析
        if (value instanceof String) {
            return LocalDateTime.parse((String) value);
        }
        throw new SQLException("Unsupported type for TIMESTAMPTZ: " + value.getClass().getName());
    }

    /**
     * 获取结果：从数据库的 TIMESTAMPTZ 转换为 LocalDateTime（按列索引）
     * 
     * @param rs ResultSet 对象
     * @param columnIndex 列索引
     * @return LocalDateTime 对象，如果为 null 则返回 null
     * @throws SQLException SQL 异常
     */
    @Override
    public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        Object value = rs.getObject(columnIndex);
        if (value == null) {
            return null;
        }
        // 尝试从 OffsetDateTime 转换（timestamptz 可能被映射为 OffsetDateTime）
        if (value instanceof java.time.OffsetDateTime) {
            return ((java.time.OffsetDateTime) value).toLocalDateTime();
        }
        // 尝试从 Timestamp 转换
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime();
        }
        // 尝试从 LocalDateTime 转换（驱动可能已经转换）
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        // 其他情况尝试转换为 String 再解析
        if (value instanceof String) {
            return LocalDateTime.parse((String) value);
        }
        throw new SQLException("Unsupported type for TIMESTAMPTZ: " + value.getClass().getName());
    }

    /**
     * 获取结果：从存储过程的 TIMESTAMPTZ 转换为 LocalDateTime
     * 
     * @param cs CallableStatement 对象
     * @param columnIndex 列索引
     * @return LocalDateTime 对象，如果为 null 则返回 null
     * @throws SQLException SQL 异常
     */
    @Override
    public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        Object value = cs.getObject(columnIndex);
        if (value == null) {
            return null;
        }
        // 尝试从 OffsetDateTime 转换（timestamptz 可能被映射为 OffsetDateTime）
        if (value instanceof java.time.OffsetDateTime) {
            return ((java.time.OffsetDateTime) value).toLocalDateTime();
        }
        // 尝试从 Timestamp 转换
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime();
        }
        // 尝试从 LocalDateTime 转换（驱动可能已经转换）
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        // 其他情况尝试转换为 String 再解析
        if (value instanceof String) {
            return LocalDateTime.parse((String) value);
        }
        throw new SQLException("Unsupported type for TIMESTAMPTZ: " + value.getClass().getName());
    }
}