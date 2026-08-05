/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.util.List;

/**
 * A single page of results plus the total row count across every page, as
 * returned by a paginated repository listing (currently just {@code
 * AlertEventRepository.listAlertEvents}, which pairs a {@code LIMIT}/{@code
 * OFFSET} query with a matching {@code COUNT(*)} query so the caller can
 * render page controls without a second round trip of its own).
 *
 * @param <T>      the row type for this page
 * @param items    the rows for this page, in the requested sort order; never
 *                 {@code null}, but may be empty
 * @param total    the total number of rows matching the filter across every
 *                 page, not just this one
 * @param page     the zero-based page number that was requested
 * @param pageSize the page size that was requested
 */
public record PagedResult<T>(List<T> items, long total, int page, int pageSize) {
}
