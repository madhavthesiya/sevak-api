package com.sevak.api.provider;

import com.sevak.api.provider.dto.AvailabilitySlot;
import com.sevak.api.provider.dto.EarningsByMethod;
import com.sevak.api.provider.dto.FreeSlot;
import com.sevak.api.provider.dto.JobSummary;
import com.sevak.api.provider.dto.MonthlyEarnings;
import com.sevak.api.provider.dto.OfferedService;
import com.sevak.api.provider.dto.OfferedVariant;
import com.sevak.api.provider.dto.OwnAvailabilitySlot;
import com.sevak.api.provider.dto.ProviderBasics;
import com.sevak.api.provider.dto.ProviderOffering;
import com.sevak.api.provider.dto.ProviderReview;
import com.sevak.api.provider.dto.TopProvider;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProviderRepository {

    private static final String DAY_NAME =
            "(ARRAY['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'])[pa.day_of_week]";

    private static final String FROM_OFFERINGS = """
            FROM provider_services ps
            JOIN services s           ON s.service_id = ps.service_id
            JOIN service_providers sp ON sp.provider_id = ps.provider_id
            JOIN users u              ON u.user_id = sp.user_id
            JOIN cities ci            ON ci.city_id = sp.city_id
            WHERE ps.is_active AND sp.is_active AND sp.verification_status = 'verified'
              AND (CAST(:service AS text) IS NULL OR s.service_name ILIKE :service ESCAPE '\\')
              AND (CAST(:city AS text) IS NULL OR LOWER(ci.city_name) = LOWER(:city))
              AND (CAST(:day AS int) IS NULL OR EXISTS (
                    SELECT 1 FROM provider_availability pa
                    WHERE pa.provider_id = sp.provider_id AND pa.day_of_week = CAST(:day AS int)))
            """;

    /**
     * Ranks by the leaderboard's stored score. Working out the platform average live meant reading every
     * review on every search: 115 of 123 ms at 1M bookings. The stored score is refreshed every 15 minutes,
     * and ranking by its unrounded form keeps the order exactly as it was.
     */
    public static final String SEARCH_SQL = """
            SELECT sp.provider_id, u.full_name AS provider_name, ci.city_name, s.service_id,
                   s.service_name, s.base_price, ps.price, sp.avg_rating, sp.review_count,
                   sp.experience_years
            """
            + FROM_OFFERINGS.replaceFirst(
                    "WHERE", "LEFT JOIN mv_provider_leaderboard lb ON lb.provider_id = sp.provider_id\nWHERE")
            + """
            ORDER BY lb.weighted_score DESC NULLS LAST, sp.provider_id, s.service_id
            LIMIT :limit OFFSET :offset
            """;

    public static final String SERVICES_SQL = """
            SELECT s.service_id, s.service_name, s.base_price, ps.price
            FROM provider_services ps
            JOIN services s ON s.service_id = ps.service_id
            WHERE ps.provider_id = :id AND ps.is_active
            ORDER BY s.service_name, s.service_id
            """;

    /** Mirrors fn_provider_available and ex_provider_no_double_booking, so a listed slot can be booked. */
    public static final String SLOTS_SQL = """
            WITH days AS (
                SELECT CAST(d AS date) AS day
                FROM generate_series(CAST(:from AS date), CAST(:from AS date) + CAST(:days AS int) - 1,
                                     interval '1 day') d
            ),
            candidates AS (
                SELECT d.day, start_at AT TIME ZONE 'Asia/Kolkata' AS slot_start
                FROM days d
                JOIN provider_availability pa
                  ON pa.provider_id = :provider AND pa.day_of_week = EXTRACT(ISODOW FROM d.day)
                CROSS JOIN LATERAL generate_series(
                    d.day + pa.start_time,
                    d.day + pa.end_time - make_interval(mins => CAST(:minutes AS int)),
                    interval '30 minutes') AS start_at
            )
            SELECT c.day AS date, c.slot_start AS start
            FROM candidates c
            WHERE c.slot_start > now() + interval '2 hours'
              AND NOT EXISTS (
                    SELECT 1 FROM bookings b
                    WHERE b.provider_id = :provider
                      AND b.status IN ('pending', 'confirmed', 'in_progress')
                      AND tstzrange(b.scheduled_start, b.scheduled_end, '[)')
                          && tstzrange(c.slot_start, c.slot_start + make_interval(mins => CAST(:minutes AS int)), '[)'))
            ORDER BY c.slot_start
            """;

    public static final String MONTHLY_EARNINGS_SQL = """
            SELECT to_char(p.paid_at AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM') AS month,
                   COUNT(*) AS total_payments, SUM(p.amount) AS total_earned
            FROM payments p
            JOIN bookings b ON b.booking_id = p.booking_id
            WHERE b.provider_id = :id AND p.status = 'success'
            GROUP BY 1
            ORDER BY 1
            """;

    public static final String INSERT_AVAILABILITY_SQL = """
            INSERT INTO provider_availability (provider_id, day_of_week, start_time, end_time)
            VALUES (:provider, :day, :start, :end)
            RETURNING slot_id, day_of_week, start_time, end_time
            """;

    /** The home page reads the stored leaderboard; the live view took 2.8 s at 1M bookings. */
    public static final String TOP_SQL = """
                        SELECT lb.provider_id, u.full_name AS provider_name, lb.city_name, lb.avg_rating,
                               lb.review_count, lb.weighted_rating, sp.experience_years
                        FROM mv_provider_leaderboard lb
                        JOIN service_providers sp ON sp.provider_id = lb.provider_id
                        JOIN users u ON u.user_id = sp.user_id
                        WHERE sp.verification_status = 'verified'
                        ORDER BY lb.weighted_rating DESC NULLS LAST, lb.provider_id
                        LIMIT :limit
                        """;

    public static final String BASICS_SQL = """
                        SELECT sp.provider_id, u.full_name AS provider_name, ci.city_name, sp.bio,
                               sp.experience_years, sp.verification_status, sp.avg_rating, sp.review_count,
                               city_avg.avg_city_rating AS city_average_rating,
                               CASE
                                   WHEN sp.avg_rating IS NULL THEN 'No reviews yet'
                                   WHEN sp.avg_rating > city_avg.avg_city_rating THEN 'Above Average'
                                   WHEN sp.avg_rating = city_avg.avg_city_rating THEN 'Average'
                                   ELSE 'Below Average'
                               END AS compared_to_city
                        FROM service_providers sp
                        JOIN users u   ON u.user_id = sp.user_id
                        JOIN cities ci ON ci.city_id = sp.city_id
                        LEFT JOIN (
                            SELECT city_id, ROUND(AVG(avg_rating), 2) AS avg_city_rating
                            FROM service_providers
                            WHERE avg_rating IS NOT NULL
                            GROUP BY city_id
                        ) city_avg ON city_avg.city_id = sp.city_id
                        WHERE sp.provider_id = :id
                        """;

    public static final String REVIEWS_SQL = """
                        SELECT pr.rating, pr.comment, cu_u.full_name AS customer_name, pr.created_at AS reviewed_at
                        FROM provider_reviews pr
                        JOIN bookings b    ON b.booking_id = pr.booking_id
                        JOIN customers cu  ON cu.customer_id = b.customer_id
                        JOIN users cu_u    ON cu_u.user_id = cu.user_id
                        WHERE b.provider_id = :id
                        ORDER BY pr.created_at DESC, pr.review_id DESC
                        LIMIT :limit OFFSET :offset
                        """;

    public static final String JOBS_SQL = """
                        SELECT b.booking_id, cu_u.full_name AS customer_name, b.scheduled_start, b.scheduled_end,
                               b.status, b.total_amount,
                               CASE WHEN b.status IN ('confirmed', 'in_progress', 'completed') THEN loc.street END AS street,
                               ar.area_name, p.status AS payment_status,
                   (SELECT STRING_AGG(s.service_name, ', ' ORDER BY bi.item_no)
                     FROM booking_items bi
                     JOIN services s ON s.service_id = bi.service_id
                     WHERE bi.booking_id = b.booking_id) AS services
                        FROM bookings b
                        JOIN customers cu ON cu.customer_id = b.customer_id
                        JOIN users cu_u   ON cu_u.user_id = cu.user_id
                        JOIN locations loc ON loc.location_id = b.location_id
                        JOIN areas ar     ON ar.area_id = loc.area_id
                        LEFT JOIN LATERAL (
                            SELECT status FROM payments
                            WHERE booking_id = b.booking_id
                            ORDER BY created_at DESC, payment_id DESC
                            LIMIT 1
                        ) p ON TRUE
                        WHERE b.provider_id = :id AND b.status = :status
                        ORDER BY b.scheduled_start %1$s, b.booking_id %1$s
                        LIMIT :limit OFFSET :offset
                        """;

    public static final String EARNINGS_SQL = """
                        SELECT p.payment_method, COUNT(*) AS total_payments, SUM(p.amount) AS total_earned
                        FROM payments p
                        JOIN bookings b ON b.booking_id = p.booking_id
                        WHERE b.provider_id = :id AND p.status = 'success'
                          AND (CAST(:from AS date) IS NULL
                               OR CAST(p.paid_at AT TIME ZONE 'Asia/Kolkata' AS date) >= CAST(:from AS date))
                          AND (CAST(:to AS date) IS NULL
                               OR CAST(p.paid_at AT TIME ZONE 'Asia/Kolkata' AS date) <= CAST(:to AS date))
                        GROUP BY p.payment_method
                        ORDER BY total_earned DESC, p.payment_method
                        """;

    private final JdbcClient jdbc;

    public ProviderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Map<String, Object> offeringFilters(String servicePattern, String city, Integer day) {
        Map<String, Object> params = new HashMap<>();
        params.put("service", servicePattern);
        params.put("city", city);
        params.put("day", day);
        return params;
    }

    public List<ProviderOffering> findOfferings(
            String servicePattern, String city, Integer day, int limit, long offset) {
        Map<String, Object> params = offeringFilters(servicePattern, city, day);
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql(SEARCH_SQL).params(params).query(ProviderOffering.class).list();
    }

    public long countOfferings(String servicePattern, String city, Integer day) {
        return jdbc.sql("SELECT COUNT(*) " + FROM_OFFERINGS)
                .params(offeringFilters(servicePattern, city, day))
                .query(Long.class)
                .single();
    }

    public List<TopProvider> findTop(int limit) {
        return jdbc.sql(TOP_SQL).param("limit", limit).query(TopProvider.class).list();
    }

    public boolean exists(int providerId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM service_providers WHERE provider_id = :id)")
                .param("id", providerId)
                .query(Boolean.class)
                .single();
    }

    /** The derived table compares this provider's rating with their city's average. */
    public Optional<ProviderBasics> findBasics(int providerId) {
        return jdbc.sql(BASICS_SQL)
                .param("id", providerId)
                .query(ProviderBasics.class)
                .optional();
    }

    public List<OfferedService> findServices(int providerId) {
        return jdbc.sql(SERVICES_SQL)
                .param("id", providerId)
                .query(OfferedService.class)
                .list();
    }

    public List<OfferedVariant> findVariants(int providerId) {
        return jdbc.sql("""
                        SELECT sv.service_id, sv.variant_id, sv.variant_name, sv.price, sv.duration
                        FROM service_variants sv
                        JOIN provider_services ps ON ps.service_id = sv.service_id
                        WHERE ps.provider_id = :id AND ps.is_active
                        ORDER BY sv.service_id, sv.price, sv.variant_id
                        """).param("id", providerId).query(OfferedVariant.class).list();
    }

    public List<FreeSlot> findFreeSlots(int providerId, LocalDate from, int days, int minutes) {
        return jdbc.sql(SLOTS_SQL)
                .param("provider", providerId)
                .param("from", from)
                .param("days", days)
                .param("minutes", minutes)
                .query(FreeSlot.class)
                .list();
    }

    public List<OwnAvailabilitySlot> findOwnAvailability(int providerId) {
        return jdbc.sql("""
                        SELECT slot_id, day_of_week, start_time, end_time
                        FROM provider_availability
                        WHERE provider_id = :id
                        ORDER BY day_of_week, start_time
                        """)
                .param("id", providerId)
                .query(OwnAvailabilitySlot.class)
                .list();
    }

    public OwnAvailabilitySlot insertAvailability(int providerId, int day, LocalTime start, LocalTime end) {
        return jdbc.sql(INSERT_AVAILABILITY_SQL)
                .param("provider", providerId)
                .param("day", day)
                .param("start", start)
                .param("end", end)
                .query(OwnAvailabilitySlot.class)
                .single();
    }

    /** Filters on the provider as well, so nobody can remove someone else's hours. */
    public int deleteAvailability(int providerId, long slotId) {
        return jdbc.sql("DELETE FROM provider_availability WHERE slot_id = :slot AND provider_id = :provider")
                .param("slot", slotId)
                .param("provider", providerId)
                .update();
    }

    public boolean serviceExists(int serviceId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM services WHERE service_id = :id)")
                .param("id", serviceId)
                .query(Boolean.class)
                .single();
    }

    public void upsertService(int providerId, int serviceId, java.math.BigDecimal price) {
        jdbc.sql("""
                        INSERT INTO provider_services (provider_id, service_id, price)
                        VALUES (:provider, :service, :price)
                        ON CONFLICT (provider_id, service_id)
                        DO UPDATE SET price = EXCLUDED.price, is_active = TRUE
                        """)
                .param("provider", providerId)
                .param("service", serviceId)
                .param("price", price)
                .update();
    }

    public int deactivateService(int providerId, int serviceId) {
        return jdbc.sql("""
                        UPDATE provider_services SET is_active = FALSE
                        WHERE provider_id = :provider AND service_id = :service AND is_active
                        """)
                .param("provider", providerId)
                .param("service", serviceId)
                .update();
    }

    public List<MonthlyEarnings> findMonthlyEarnings(int providerId) {
        return jdbc.sql(MONTHLY_EARNINGS_SQL)
                .param("id", providerId)
                .query(MonthlyEarnings.class)
                .list();
    }

    public List<AvailabilitySlot> findAvailability(int providerId) {
        return jdbc.sql("SELECT " + DAY_NAME + """
                         AS day_of_week, pa.start_time, pa.end_time
                        FROM provider_availability pa
                        WHERE pa.provider_id = :id
                        ORDER BY pa.day_of_week, pa.start_time
                        """)
                .param("id", providerId)
                .query(AvailabilitySlot.class)
                .list();
    }

    public List<ProviderReview> findReviews(int providerId, int limit, long offset) {
        return jdbc.sql(REVIEWS_SQL)
                .param("id", providerId)
                .param("limit", limit)
                .param("offset", offset)
                .query(ProviderReview.class)
                .list();
    }

    public long countReviews(int providerId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM provider_reviews pr
                        JOIN bookings b ON b.booking_id = pr.booking_id
                        WHERE b.provider_id = :id
                        """).param("id", providerId).query(Long.class).single();
    }

    /** Upcoming work is listed soonest first; finished work newest first. */
    public List<JobSummary> findJobs(int providerId, String status, int limit, long offset) {
        String order = status.equals("completed") || status.equals("cancelled") ? "DESC" : "ASC";
        return jdbc.sql(JOBS_SQL.formatted(order))
                .param("id", providerId)
                .param("status", status)
                .param("limit", limit)
                .param("offset", offset)
                .query(JobSummary.class)
                .list();
    }

    public long countJobs(int providerId, String status) {
        return jdbc.sql("SELECT COUNT(*) FROM bookings WHERE provider_id = :id AND status = :status")
                .param("id", providerId)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    public List<EarningsByMethod> findEarnings(int providerId, LocalDate from, LocalDate to) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", providerId);
        params.put("from", from);
        params.put("to", to);
        return jdbc.sql(EARNINGS_SQL)
                .params(params)
                .query(EarningsByMethod.class)
                .list();
    }
}
