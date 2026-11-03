/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2025 Sergei Semenkov
// All Rights Reserved.
*/

package mondrian.metrics;

import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;

public class MdxMetrics {

    // Every counter here counts DAX queries too: a DAX query is compiled into the same Query and
    // executed by the same code. The language label tells them apart.

    public static final Counter mdxRequests = Counter.build()
            .name("mdx_requests_total")
            .help("Total MDX requests per catalog, cube, user and language (MDX or DAX)")
            .labelNames("catalog", "cube", "user", "language")
            .register();

    public static final Counter mdxCompleted = Counter.build()
            .name("mdx_completed_total")
            .help("Total completed MDX queries per catalog, cube, user and language (MDX or DAX)")
            .labelNames("catalog", "cube", "user", "language")
            .register();

    public static final Counter mdxExecutionTimeSum = Counter.build()
            .name("mdx_execution_time_seconds_total")
            .help("Total sum of MDX query execution time in seconds per catalog, cube, user and language (MDX or DAX)")
            .labelNames("catalog", "cube", "user", "language")
            .register();

    public static final Counter resultCellsCount = Counter.build()
            .name("mdx_result_cells_count_total")
            .help("Total sum of cells in query result per catalog, cube, user and language (MDX or DAX)")
            .labelNames("catalog", "cube", "user", "language")
            .register();

    // Every query that ended in an error, a timeout, a cancel or a result limit. mdx_completed_total
    // counts these too (it counts every query that finished, however it finished), so
    // completed - failed is the number that returned a result.
    public static final Counter mdxFailed = Counter.build()
            .name("mdx_failed_total")
            .help("Total MDX queries that failed per catalog, cube, user and reason "
                + "(error, timeout_or_cancel, limit) and language (MDX or DAX)")
            .labelNames("catalog", "cube", "user", "reason", "language")
            .register();

    // Per catalog and language only, not per user and cube: a histogram is a dozen series per
    // label set, and the per-user picture is already in the counters above.
    public static final Histogram mdxDuration = Histogram.build()
            .name("mdx_query_duration_seconds")
            .help("MDX query execution time in seconds per catalog and language (MDX or DAX)")
            .labelNames("catalog", "language")
            .buckets(0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120, 300)
            .register();
}