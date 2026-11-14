package com.sevak.api.meta;

import com.sevak.api.common.NotFoundException;
import com.sevak.api.common.OpenApiConfig;
import com.sevak.api.common.UnprocessableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Powers the "Show the SQL" drawer. */
@RestController
@Tag(name = "6. Under the hood", description = "The SQL behind each screen")
public class MetaController {

    /**
     * @param measuredMs median time from QueryBenchmark on the large dataset; null if not measured
     */
    public record QueryInfo(
            String key,
            String title,
            List<String> techniques,
            String description,
            String sql,
            Double measuredMs,
            boolean explainable) {}

    public record ExplainResult(String key, List<String> plan) {}

    private final QueryCatalog catalog;
    private final QueryTimings timings;
    private final JdbcClient jdbc;

    public MetaController(QueryCatalog catalog, QueryTimings timings, JdbcClient jdbc) {
        this.catalog = catalog;
        this.timings = timings;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/meta/queries/{key}")
    @Operation(summary = "One query's SQL", description = "The key comes from a response's X-Sevak-Query header.")
    public QueryInfo query(@PathVariable String key) {
        QueryCatalog.Entry entry = require(key);
        return new QueryInfo(
                entry.key(),
                entry.title(),
                entry.techniques(),
                entry.description(),
                entry.sql(),
                timings.medianMs(key).orElse(null),
                entry.explainParams() != null);
    }

    /** Read-only transaction and server-side sample values: nothing a user types ever reaches this SQL. */
    @PostMapping("/api/v1/admin/queries/{key}/explain")
    @Transactional(readOnly = true)
    @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
    @Operation(summary = "Run EXPLAIN ANALYZE", description = "Admin only, and only for allow-listed read queries.")
    public ExplainResult explain(@PathVariable String key) {
        QueryCatalog.Entry entry = require(key);
        if (entry.explainParams() == null) {
            throw new UnprocessableException("This statement changes data, so it can't be run with EXPLAIN");
        }
        List<String> plan = jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + entry.sql())
                .params(entry.explainParams())
                .query(String.class)
                .list();
        return new ExplainResult(key, plan);
    }

    private QueryCatalog.Entry require(String key) {
        return catalog.find(key).orElseThrow(() -> new NotFoundException("No query named " + key));
    }
}
