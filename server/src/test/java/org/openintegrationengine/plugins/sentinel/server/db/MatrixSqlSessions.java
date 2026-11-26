/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

import javax.sql.DataSource;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.SqlSessionManager;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;

/**
 * MyBatis sessions over a matrix database with the packaged vendor mapper, so a matrix test can read through the
 * shipped repositories rather than with plain SQL.
 */
final class MatrixSqlSessions {

    private MatrixSqlSessions() {
    }

    static SqlSessionManager open(DatabaseMatrixSupport.MatrixDatabase database) throws Exception {
        Configuration configuration = new Configuration(new Environment("matrix", new JdbcTransactionFactory(),
                new OpenerDataSource(database)));
        configuration.setJdbcTypeForNull(JdbcType.VARCHAR);
        String resource = "/mapper/" + database.vendor() + "-sqlmap.xml";
        try (InputStream in = MatrixSqlSessions.class.getResourceAsStream(resource)) {
            assertNotNull(in, "Packaged mapper is absent from the test classpath: " + resource);
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return SqlSessionManager.newInstance(new SqlSessionFactoryBuilder().build(configuration));
    }

    /** The matrix's own connections, for MyBatis. */
    private record OpenerDataSource(DatabaseMatrixSupport.MatrixDatabase database) implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            try {
                return database.open();
            } catch (SQLException e) {
                throw e;
            } catch (Exception e) {
                throw new SQLException(e);
            }
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            throw new SQLException("Not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> type) {
            return false;
        }
    }
}
