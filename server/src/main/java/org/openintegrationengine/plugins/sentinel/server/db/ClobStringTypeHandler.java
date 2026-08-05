/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * Materializes CLOB columns to {@link String} during MyBatis result mapping.
 *
 * <p>The {@code *_json} columns are {@code CLOB} on Derby and Oracle
 * ({@code TEXT}/{@code LONGTEXT}/{@code NVARCHAR(MAX)} elsewhere), and those
 * two drivers return lazy {@link Clob} locators from map-typed results — the
 * repositories' {@code (String)} casts then throw {@code ClassCastException}
 * (seen live on Derby's {@code EmbedClob}). The read must happen here, inside
 * result mapping, because CLOB locators are transaction-scoped: by the time a
 * repository converts the returned map (after {@code SqlSessionManager} has
 * closed the session), the locator is already dead.</p>
 *
 * <p>Referenced by {@code typeHandler=} attributes on the JSON columns in the
 * derby and oracle mappers only. Not MyBatis's built-in
 * {@code ClobTypeHandler} because that one (in the engine-shipped 3.1.1 line)
 * collapses SQL NULL to {@code ""} — nullable columns like
 * {@code details_json} must stay {@code null} so the update statements'
 * {@code <if test="... != null">} guards keep their meaning.</p>
 */
public class ClobStringTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        // Both Derby and Oracle accept setString into a CLOB column (JDBC 4).
        ps.setString(i, parameter);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return materialize(rs.getClob(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return materialize(rs.getClob(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return materialize(cs.getClob(columnIndex));
    }

    /** Reads the whole CLOB eagerly; these payloads are small config/snapshot JSON. */
    private static String materialize(Clob clob) throws SQLException {
        return clob != null ? clob.getSubString(1, (int) clob.length()) : null;
    }
}
