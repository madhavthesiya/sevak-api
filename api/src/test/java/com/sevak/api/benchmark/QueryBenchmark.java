package com.sevak.api.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.sevak.api.admin.AdminRepository;
import com.sevak.api.customer.CustomerRepository;
import com.sevak.api.meta.QueryCatalog;
import com.sevak.api.provider.ProviderRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads about a million bookings and measures the app's real queries. Not part of the normal build,
 * because the load alone takes about ten minutes. Run it with:
 *
 * <pre>./mvnw test -Dtest=QueryBenchmark -Dsurefire.failIfNoSpecifiedTests=false</pre>
 *
 * <p>Method: every query runs once to warm the cache, then three times under EXPLAIN (ANALYZE, BUFFERS),
 * and the median execution time is reported. A "before" variant changes the schema inside a transaction
 * that is rolled back, so each comparison runs against the same data. Where Phase 6 rewrote a query, the
 * old version (see {@link OldQueries}) must return exactly the same rows, or the run fails.
 *
 * <p>While iterating, add {@code -Dbenchmark.reuse=true} with TESTCONTAINERS_REUSE_ENABLE=true to keep the
 * loaded database between runs. Published numbers come from a fresh container.
 */
@SpringBootTest(
        properties = {
            // Some "before" queries are slow on purpose; the app's own limit is 5 seconds.
            "spring.jdbc.template.query-timeout=10m",
            "sevak.views.refresh-interval=PT24H",
            "sevak.bookings.expiry-interval=PT24H"
        })
class QueryBenchmark {

    private static final Logger log = LoggerFactory.getLogger(QueryBenchmark.class);
    private static final int RUNS = 3;
    private static final Path DOCS = Path.of("../docs/benchmarks");
    private static final Path TIMINGS = Path.of("src/main/resources/benchmarks/query-timings.json");
    private static final Pattern EXECUTION = Pattern.compile("Execution Time: ([0-9.]+) ms");
    private static final Pattern PLANNING = Pattern.compile("Planning Time: ([0-9.]+) ms");

    private static final String START_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_bookings_start_id ON bookings (scheduled_start DESC, booking_id DESC)";
    private static final String NO_START_INDEX = "DROP INDEX IF EXISTS idx_bookings_start_id";

    @ServiceConnection
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:16-alpine")
            .withCommand("postgres", "-c", "shared_buffers=256MB", "-c", "max_wal_size=4GB")
            .withSharedMemorySize(1024L * 1024 * 1024)
            .withReuse(Boolean.getBoolean("benchmark.reuse"));

    static {
        DB.start();
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private QueryCatalog catalog;

    @Autowired
    private JsonMapper json;

    record Variant(String label, List<String> setup, String sql, Map<String, Object> params) {}

    record Experiment(String id, String title, List<Variant> variants) {}

    record Measured(String label, double medianMs, double planningMs, List<Double> runsMs, String plan) {}

    /** Typical rows to query: a median customer and provider, and the customer with the most bookings. */
    record Params(
            int customer, int heavyCustomer, int provider, int booking, int service, Map<String, Object> cursor) {}

    @Test
    void run() throws Exception {
        Double loadSeconds = null;
        if (jdbc.sql("SELECT COUNT(*) FROM bookings").query(Long.class).single() < 100_000) {
            loadSeconds = load(Double.parseDouble(System.getProperty("benchmark.scale", "1.0")));
        }
        verifyIntegrity();
        // The load leaves about a gigabyte of dirty pages. Write them now, so a background checkpoint
        // does not compete with the measurements.
        jdbc.sql("CHECKPOINT").update();

        Params params = chooseParams();
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("environment", environment(loadSeconds));
        results.put("params", params);
        results.put("sameResults", proveRewritesReturnTheSameRows(params));

        List<Map<String, Object>> experimentResults = new ArrayList<>();
        Files.createDirectories(DOCS.resolve("plans"));
        for (Experiment experiment : experiments(params)) {
            List<Measured> measured = new ArrayList<>();
            for (int i = 0; i < experiment.variants().size(); i++) {
                Variant variant = experiment.variants().get(i);
                Measured m = measure(variant);
                Path plan = DOCS.resolve("plans").resolve(experiment.id() + "-" + (char) ('a' + i) + ".txt");
                Files.writeString(plan, variant.label() + "\n\n" + m.plan(), StandardCharsets.UTF_8);
                measured.add(new Measured(m.label(), m.medianMs(), m.planningMs(), m.runsMs(), plan.toString()));
                log.info("{} | {} | {} ms", experiment.id(), variant.label(), m.medianMs());
            }
            experimentResults.add(Map.of("id", experiment.id(), "title", experiment.title(), "variants", measured));
        }
        results.put("experiments", experimentResults);
        results.put("revenueTotals", revenueTotals());
        results.put("refreshMs", refreshTimes());
        results.put("indexSizes", indexSizes());

        Map<String, Double> timings = catalogTimings(params);
        results.put("catalogTimings", timings);

        Files.writeString(
                DOCS.resolve("results.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(results),
                StandardCharsets.UTF_8);
        Map<String, Object> forApp = new LinkedHashMap<>();
        forApp.put("measuredOn", results.get("environment"));
        forApp.put("timings", timings);
        Files.createDirectories(TIMINGS.getParent());
        Files.writeString(TIMINGS, json.writerWithDefaultPrettyPrinter().writeValueAsString(forApp));
    }

    // ── Loading and checking the data ───────────────────────

    private double load(double scale) throws Exception {
        String script = Files.readString(Path.of("sql/benchmarks/generate_large_dataset.sql"))
                .replace("1.0::numeric AS scale", scale + "::numeric AS scale");
        List<String> statements = Arrays.stream(script.split(";\\s*(\\r?\\n|$)"))
                .map(s -> s.lines().filter(l -> !l.strip().startsWith("--")).reduce("", (a, b) -> a + "\n" + b))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
        long started = System.nanoTime();
        // One session throughout: the script's temporary tables live in it.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            for (String sql : statements) {
                long t = System.nanoTime();
                statement.execute(sql);
                double seconds = (System.nanoTime() - t) / 1e9;
                if (seconds > 2) {
                    log.info(
                            "{} s  {}",
                            String.format("%.1f", seconds),
                            sql.lines().findFirst().orElse(""));
                }
            }
        }
        return (System.nanoTime() - started) / 1e9;
    }

    /** The project's own integrity checks: each must return no rows on the generated data too. */
    private void verifyIntegrity() throws Exception {
        String script = Files.readString(Path.of("sql/checks/data_integrity.sql"));
        for (String part : script.split(";")) {
            String sql = part.lines()
                    .filter(l -> !l.strip().startsWith("--"))
                    .reduce("", (a, b) -> a + "\n" + b)
                    .strip();
            if (sql.isEmpty() || sql.toUpperCase().startsWith("SET ")) {
                continue;
            }
            assertThat(jdbc.sql(sql).query().listOfRows()).as(sql).isEmpty();
        }
    }

    private Params chooseParams() {
        int customer = jdbc.sql("""
                        WITH counts AS (SELECT customer_id, COUNT(*) AS n FROM bookings GROUP BY customer_id),
                        median AS (SELECT percentile_disc(0.5) WITHIN GROUP (ORDER BY n) AS m FROM counts)
                        SELECT customer_id FROM counts, median WHERE n = m ORDER BY customer_id LIMIT 1
                        """).query(Integer.class).single();
        int heavyCustomer = jdbc.sql("""
                        SELECT customer_id FROM bookings GROUP BY customer_id ORDER BY COUNT(*) DESC, customer_id
                        LIMIT 1
                        """).query(Integer.class).single();
        int provider = jdbc.sql("""
                        WITH counts AS (
                            SELECT provider_id, COUNT(*) AS n FROM bookings WHERE status = 'pending'
                            GROUP BY provider_id),
                        median AS (SELECT percentile_disc(0.5) WITHIN GROUP (ORDER BY n) AS m FROM counts)
                        SELECT provider_id FROM counts, median WHERE n = m ORDER BY provider_id LIMIT 1
                        """).query(Integer.class).single();
        int booking = jdbc.sql("""
                        SELECT booking_id FROM bookings WHERE customer_id = :c AND status = 'completed'
                        ORDER BY scheduled_start DESC LIMIT 1
                        """).param("c", customer).query(Integer.class).single();
        int service = jdbc.sql("SELECT MIN(service_id) FROM provider_services WHERE provider_id = :p")
                .param("p", provider)
                .query(Integer.class)
                .single();
        Map<String, Object> row = jdbc.sql("""
                        SELECT scheduled_start, booking_id FROM bookings
                        ORDER BY scheduled_start DESC, booking_id DESC OFFSET 99979 LIMIT 1
                        """).query().singleRow();
        return new Params(
                customer,
                heavyCustomer,
                provider,
                booking,
                service,
                params("before", row.get("scheduled_start"), "beforeId", row.get("booking_id")));
    }

    /**
     * A faster query is no use if it answers differently. Each rewrite is compared row for row with the
     * version it replaced; search is compared as a set, because it now ranks by the stored, rounded score.
     */
    private Map<String, Object> proveRewritesReturnTheSameRows(Params p) {
        Map<String, Object> checks = new LinkedHashMap<>();
        assertThat(rows(OldQueries.REVENUE_SUBTOTAL_REJECTED, params()))
                .as("the rejected revenue rewrite")
                .isEqualTo(rows(AdminRepository.REVENUE_SQL, params()));
        assertThat(rows(AdminRepository.CATEGORY_REVENUE_SQL, params()))
                .as("stored revenue matches the live calculation")
                .isEqualTo(rows(AdminRepository.REVENUE_SQL, params()));
        checks.put("revenue", "identical rows, and the stored copy matches");
        // The new view adds weighted_score; compare the columns both versions have.
        String sameColumns = "SELECT rank, provider_id, email, city_name, avg_rating, review_count, weighted_rating,"
                + " jobs_completed, total_revenue, provider_name FROM (%s) x";
        assertThat(rows(sameColumns.formatted(AdminRepository.LEADERBOARD_LIVE_SQL), params()))
                .as("leaderboard view rewrite")
                .isEqualTo(rows(sameColumns.formatted(OldQueries.LEADERBOARD_LIVE), params()));
        checks.put("leaderboard", "identical rows");
        assertThat(rows(ProviderRepository.TOP_SQL, params("limit", 50)))
                .as("top providers from the stored leaderboard")
                .isEqualTo(rows(OldQueries.TOP, params("limit", 50)));
        checks.put("topProviders", "identical first 50 rows");

        Map<String, Object> all = params("service", null, "city", null, "day", null, "limit", 100_000, "offset", 0);
        List<Map<String, Object>> oldSearch = rows(OldQueries.SEARCH, all);
        List<Map<String, Object>> newSearch = rows(ProviderRepository.SEARCH_SQL, all);
        assertThat(newSearch)
                .as("search returns the same offerings in the same order")
                .isEqualTo(oldSearch);
        checks.put("search", "identical " + oldSearch.size() + " offerings in identical order");
        return checks;
    }

    private List<Map<String, Object>> rows(String sql, Map<String, Object> params) {
        return jdbc.sql(sql).params(params).query().listOfRows();
    }

    // ── The experiments ─────────────────────────────────────

    private List<Experiment> experiments(Params p) {
        Map<String, Object> search =
                params("service", "%clean%", "city", "Mumbai", "day", null, "limit", 10, "offset", 0);
        Map<String, Object> myBookings = params("id", p.customer(), "limit", 20, "offset", 0);
        Map<String, Object> heavyBookings = params("id", p.heavyCustomer(), "limit", 20, "offset", 0);
        Map<String, Object> jobs = params("id", p.provider(), "status", "pending", "limit", 10, "offset", 0);
        Map<String, Object> slots = params(
                "provider", p.provider(), "from", LocalDate.now(ZoneId.of("Asia/Kolkata")), "days", 14, "minutes", 60);
        String jobsSql = ProviderRepository.JOBS_SQL.formatted("ASC");
        String page = AdminRepository.BOOKINGS_SELECT + "ORDER BY b.scheduled_start DESC, b.booking_id DESC ";
        String keyset = AdminRepository.BOOKINGS_SELECT + """
                WHERE (b.scheduled_start, b.booking_id) < (:before, :beforeId)
                ORDER BY b.scheduled_start DESC, b.booking_id DESC
                LIMIT 20
                """;
        String singleCustomerIndex = "CREATE INDEX idx_bookings_customer ON bookings (customer_id)";

        return List.of(
                new Experiment(
                        "01-search",
                        "Provider search, ILIKE '%clean%' in Mumbai",
                        List.of(
                                new Variant(
                                        "Old query, without the trigram index",
                                        List.of("DROP INDEX idx_services_name_trgm"),
                                        OldQueries.SEARCH,
                                        search),
                                new Variant("Old query, with the trigram index", List.of(), OldQueries.SEARCH, search),
                                new Variant(
                                        "Ranked by the stored leaderboard score",
                                        List.of(),
                                        ProviderRepository.SEARCH_SQL,
                                        search))),
                new Experiment(
                        "02-my-bookings",
                        "Customer's My Bookings, first page",
                        List.of(
                                new Variant(
                                        "Median customer, no index on customer_id",
                                        List.of("DROP INDEX idx_bookings_customer_start"),
                                        CustomerRepository.BOOKINGS_SQL,
                                        myBookings),
                                new Variant(
                                        "Median customer, single-column (customer_id), as in V1",
                                        List.of("DROP INDEX idx_bookings_customer_start", singleCustomerIndex),
                                        CustomerRepository.BOOKINGS_SQL,
                                        myBookings),
                                new Variant(
                                        "Median customer, composite (customer_id, scheduled_start DESC), as in V11",
                                        List.of(),
                                        CustomerRepository.BOOKINGS_SQL,
                                        myBookings),
                                new Variant(
                                        "Busiest customer, single-column",
                                        List.of("DROP INDEX idx_bookings_customer_start", singleCustomerIndex),
                                        CustomerRepository.BOOKINGS_SQL,
                                        heavyBookings),
                                new Variant(
                                        "Busiest customer, composite",
                                        List.of(),
                                        CustomerRepository.BOOKINGS_SQL,
                                        heavyBookings))),
                new Experiment(
                        "03-pending-jobs",
                        "Provider's pending jobs",
                        List.of(
                                new Variant(
                                        "Neither provider index",
                                        List.of(
                                                "DROP INDEX idx_bookings_pending",
                                                "DROP INDEX idx_bookings_provider_status"),
                                        jobsSql,
                                        jobs),
                                new Variant(
                                        "Composite (provider_id, status, scheduled_start) only",
                                        List.of("DROP INDEX idx_bookings_pending"),
                                        jobsSql,
                                        jobs),
                                new Variant(
                                        "Partial index WHERE status = 'pending' only",
                                        List.of("DROP INDEX idx_bookings_provider_status"),
                                        jobsSql,
                                        jobs),
                                new Variant("Both, as shipped", List.of(), jobsSql, jobs))),
                new Experiment(
                        "04-leaderboard",
                        "Provider leaderboard",
                        List.of(
                                new Variant(
                                        "Old live view, COUNT(DISTINCT)",
                                        List.of(),
                                        OldQueries.LEADERBOARD_LIVE,
                                        params()),
                                new Variant(
                                        "New live view, jobs counted first",
                                        List.of(),
                                        AdminRepository.LEADERBOARD_LIVE_SQL,
                                        params()),
                                new Variant(
                                        "Materialized view",
                                        List.of(),
                                        AdminRepository.LEADERBOARD_FAST_SQL,
                                        params()))),
                new Experiment(
                        "05-pagination",
                        "Admin bookings list, 20 rows a page",
                        List.of(
                                new Variant(
                                        "OFFSET, page 1, no index",
                                        List.of(NO_START_INDEX),
                                        page + "LIMIT 20",
                                        params()),
                                new Variant(
                                        "OFFSET, page 5,000, no index",
                                        List.of(NO_START_INDEX),
                                        page + "LIMIT 20 OFFSET 99980",
                                        params()),
                                new Variant(
                                        "Keyset, page 5,000, no index", List.of(NO_START_INDEX), keyset, p.cursor()),
                                new Variant(
                                        "OFFSET, page 1, with index",
                                        List.of(START_INDEX),
                                        page + "LIMIT 20",
                                        params()),
                                new Variant(
                                        "OFFSET, page 5,000, with index",
                                        List.of(START_INDEX),
                                        page + "LIMIT 20 OFFSET 99980",
                                        params()),
                                new Variant(
                                        "Keyset, page 5,000, with index", List.of(START_INDEX), keyset, p.cursor()))),
                new Experiment(
                        "06-revenue",
                        "Revenue by category",
                        List.of(
                                new Variant(
                                        "Before task 0.4 (counts payments twice)",
                                        List.of(),
                                        OldQueries.REVENUE_FAN_OUT,
                                        params()),
                                new Variant(
                                        "Task 0.4: CTE with a window function (correct)",
                                        List.of(),
                                        OldQueries.REVENUE_WINDOW,
                                        params()),
                                new Variant(
                                        "Phase 6: divide by the trigger-guaranteed subtotal (correct)",
                                        List.of(),
                                        OldQueries.REVENUE_SUBTOTAL_REJECTED,
                                        params()),
                                new Variant(
                                        "Phase 6: read the stored summary",
                                        List.of(),
                                        AdminRepository.CATEGORY_REVENUE_SQL,
                                        params()))),
                new Experiment(
                        "07-free-slots",
                        "Free time slots, which use the double-booking overlap test",
                        List.of(
                                new Variant(
                                        "Without the exclusion constraint's GiST index",
                                        List.of("ALTER TABLE bookings DROP CONSTRAINT ex_provider_no_double_booking"),
                                        ProviderRepository.SLOTS_SQL,
                                        slots),
                                new Variant("With it", List.of(), ProviderRepository.SLOTS_SQL, slots))),
                new Experiment(
                        "08-home-top",
                        "Home page \"highest rated\", 4 providers",
                        List.of(
                                new Variant("Old: computed live", List.of(), OldQueries.TOP, params("limit", 4)),
                                new Variant(
                                        "New: read from the stored leaderboard",
                                        List.of(),
                                        ProviderRepository.TOP_SQL,
                                        params("limit", 4)))));
    }

    private Measured measure(Variant variant) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        return template.execute(status -> {
            status.setRollbackOnly(); // a "before" schema change never outlives its measurement
            variant.setup().forEach(ddl -> jdbc.sql(ddl).update());
            explain(variant.sql(), variant.params()); // warm-up
            List<List<String>> plans = new ArrayList<>();
            for (int i = 0; i < RUNS; i++) {
                plans.add(explain(variant.sql(), variant.params()));
            }
            plans.sort((a, b) -> Double.compare(time(a, EXECUTION), time(b, EXECUTION)));
            List<String> median = plans.get(RUNS / 2);
            return new Measured(
                    variant.label(),
                    time(median, EXECUTION),
                    time(median, PLANNING),
                    plans.stream().map(plan -> time(plan, EXECUTION)).sorted().toList(),
                    String.join("\n", median));
        });
    }

    private List<String> explain(String sql, Map<String, Object> params) {
        return jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + sql)
                .params(params)
                .query(String.class)
                .list();
    }

    private static double time(List<String> plan, Pattern pattern) {
        for (String line : plan) {
            Matcher m = pattern.matcher(line);
            if (m.find()) {
                return Double.parseDouble(m.group(1));
            }
        }
        throw new IllegalStateException("No timing in plan");
    }

    /** Every read query in the drawer's catalogue, with typical values instead of the demo ones. */
    private Map<String, Double> catalogTimings(Params p) {
        Map<String, Map<String, Object>> overrides = new HashMap<>();
        overrides.put(
                "providers.search",
                params("service", "%clean%", "city", "Mumbai", "day", null, "limit", 10, "offset", 0));
        overrides.put("providers.profile", params("id", p.provider()));
        overrides.put("providers.reviews", params("id", p.provider(), "limit", 10, "offset", 0));
        overrides.put(
                "providers.slots",
                params(
                        "provider",
                        p.provider(),
                        "from",
                        LocalDate.now(ZoneId.of("Asia/Kolkata")),
                        "days",
                        14,
                        "minutes",
                        60));
        overrides.put("bookings.price", params("provider", p.provider(), "service", p.service(), "variant", null));
        overrides.put("me.bookings", params("id", p.customer(), "limit", 20, "offset", 0));
        overrides.put("me.booking", params("booking", p.booking(), "customer", p.customer()));
        overrides.put("me.addresses", params("id", p.customer()));
        overrides.put("provider.jobs", params("id", p.provider(), "status", "pending", "limit", 10, "offset", 0));
        overrides.put("provider.earnings", params("id", p.provider(), "from", null, "to", null));
        overrides.put("provider.earningsMonthly", params("id", p.provider()));
        overrides.put("provider.services", params("id", p.provider()));

        Map<String, Double> timings = new LinkedHashMap<>();
        for (String key : catalog.keys()) {
            QueryCatalog.Entry entry = catalog.find(key).orElseThrow();
            if (entry.explainParams() == null) {
                continue;
            }
            Map<String, Object> values = overrides.getOrDefault(key, entry.explainParams());
            Measured m = measure(new Variant(key, List.of(), entry.sql(), values));
            timings.put(key, m.medianMs());
            log.info("catalogue | {} | {} ms", key, m.medianMs());
        }
        return timings;
    }

    // ── Context for the report ──────────────────────────────

    private Map<String, Object> environment(Double loadSeconds) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("postgres", jdbc.sql("SELECT version()").query(String.class).single());
        for (String setting : List.of(
                "shared_buffers",
                "work_mem",
                "effective_cache_size",
                "max_parallel_workers_per_gather",
                "random_page_cost")) {
            env.put(setting, jdbc.sql("SHOW " + setting).query(String.class).single());
        }
        var docker = DB.getDockerClient().infoCmd().exec();
        env.put("dockerCpus", docker.getNCPU());
        env.put("dockerMemoryGb", Math.round(docker.getMemTotal() / 1e8) / 10.0);
        env.put("os", System.getProperty("os.name"));
        env.put("loadSeconds", loadSeconds == null ? "reused an already loaded database" : Math.round(loadSeconds));
        env.put(
                "databaseSize",
                jdbc.sql("SELECT pg_size_pretty(pg_database_size(current_database()))")
                        .query(String.class)
                        .single());
        Map<String, Long> rows = new LinkedHashMap<>();
        for (String table : List.of(
                "users",
                "customers",
                "service_providers",
                "locations",
                "customer_addresses",
                "bookings",
                "booking_items",
                "booking_status_log",
                "payments",
                "provider_reviews",
                "complaints")) {
            rows.put(
                    table,
                    jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single());
        }
        env.put("rows", rows);
        return env;
    }

    /** The point of the fix in task 0.4, measured: how much the old query over-counted. */
    private Map<String, Object> revenueTotals() {
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("actualSuccessfulPayments", sum("SELECT SUM(amount) FROM payments WHERE status = 'success'"));
        totals.put("beforeTask04", sum("SELECT SUM(total_revenue) FROM (" + OldQueries.REVENUE_FAN_OUT + ") x"));
        totals.put("now", sum("SELECT SUM(total_revenue) FROM (" + AdminRepository.REVENUE_SQL + ") x"));
        return totals;
    }

    private BigDecimal sum(String sql) {
        return jdbc.sql(sql).query(BigDecimal.class).single();
    }

    /** What freshness costs: refreshing the stored copy, timed outside any transaction. */
    private Map<String, List<Double>> refreshTimes() {
        Map<String, List<Double>> all = new LinkedHashMap<>();
        for (String view : List.of("mv_provider_leaderboard", "mv_city_revenue_summary", "mv_category_revenue")) {
            List<Double> times = new ArrayList<>();
            for (int i = 0; i < RUNS; i++) {
                long t = System.nanoTime();
                jdbc.sql("REFRESH MATERIALIZED VIEW CONCURRENTLY " + view).update();
                times.add(Math.round((System.nanoTime() - t) / 1e5) / 10.0);
            }
            all.put(view, times.stream().sorted().toList());
        }
        return all;
    }

    private List<Map<String, Object>> indexSizes() {
        return jdbc.sql("""
                        SELECT indexrelname AS index, pg_size_pretty(pg_relation_size(indexrelid)) AS size
                        FROM pg_stat_user_indexes
                        WHERE relname = 'bookings'
                        ORDER BY pg_relation_size(indexrelid) DESC
                        """).query().listOfRows();
    }

    private static Map<String, Object> params(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
