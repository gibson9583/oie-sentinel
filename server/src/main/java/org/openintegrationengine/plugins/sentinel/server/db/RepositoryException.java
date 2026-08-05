/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

/**
 * Wraps a persistence-layer failure raised by any of the {@code *Repository}
 * classes in this package.
 *
 * <p>Unchecked so callers can propagate freely without polluting their throws
 * clauses. Each repository call site logs its own context message at the time
 * of failure, so the wrapped {@code cause} usually carries enough information
 * on its own; the optional message constructor is available for higher-level
 * callers that want to add their own context.</p>
 */
class RepositoryException extends RuntimeException {

    /**
     * Wraps the underlying cause with no additional message.
     *
     * @param cause the underlying exception that triggered this failure (typically a
     *              JDBC or MyBatis exception); may be {@code null}
     */
    RepositoryException(Throwable cause) {
        super(cause);
    }

    /**
     * Wraps the underlying cause with an explanatory message.
     *
     * @param message a description of what the repository was trying to do
     * @param cause   the underlying exception that triggered this failure; may be {@code null}
     */
    RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
