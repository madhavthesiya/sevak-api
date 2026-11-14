package com.sevak.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sevak.api.meta.QueryCatalog;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** The endpoints that exist for the web app: slots, quotes, provider settings, admin queues, the SQL drawer. */
class FrontendApiTest extends IntegrationTest {

    @Autowired
    private QueryCatalog catalog;

    /** A Monday at least a week away, so no moved demo booking or the two-hour lead time gets in the way. */
    private static LocalDate quietMonday() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata")).plusWeeks(1).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    }

    private ResultActions postJson(String url, String auth, String body) throws Exception {
        return mvc.perform(post(url)
                .header(AUTH, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // ── Accounts ────────────────────────────────────────────

    @Test
    void theApiDocsDefineTheErrorSchemaTheyPointAt() throws Exception {
        mvc.perform(get("/api-docs"))
                .andExpect(jsonPath("$.components.schemas.Problem").exists());
    }

    @Test
    void everyRoleCanReadItsOwnAccount() throws Exception {
        mvc.perform(get("/api/v1/me").header(AUTH, customer(1))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").header(AUTH, provider(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("provider"));
        mvc.perform(get("/api/v1/me").header(AUTH, admin())).andExpect(status().isOk());
    }

    // ── Search and booking ──────────────────────────────────

    @Test
    void areasArePublicAndCarryTheirCity() throws Exception {
        mvc.perform(get("/api/v1/areas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[?(@.areaName == 'Navrangpura')].cityName", hasItem("Ahmedabad")));
    }

    @Test
    void theProfileListsVariantsWithPriceAndDuration() throws Exception {
        mvc.perform(get("/api/v1/providers/1"))
                .andExpect(jsonPath("$.variants[?(@.variantName == 'Premium')].duration", hasItem(45)));
    }

    @Test
    void slotsFollowWorkingHoursAroundTheLunchBreak() throws Exception {
        // Ravi works 09:00-13:00 and 14:00-18:00 on Mondays: seven one-hour starts in each block.
        mvc.perform(get("/api/v1/providers/1/slots")
                        .param("from", quietMonday().toString())
                        .param("days", "1")
                        .param("minutes", "60"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Sevak-Query", "providers.slots"))
                .andExpect(jsonPath("$", hasSize(14)));
    }

    @Test
    void aBookedTimeDisappearsFromTheSlots() throws Exception {
        LocalDate monday = quietMonday();
        jdbc.update("""
                INSERT INTO bookings (customer_id, provider_id, location_id, scheduled_start, scheduled_end,
                                      status, subtotal, discount, total_amount)
                VALUES (1, 1, 1, CAST(? AS timestamp) AT TIME ZONE 'Asia/Kolkata',
                        CAST(? AS timestamp) AT TIME ZONE 'Asia/Kolkata', 'confirmed', 220, 0, 220)
                """, monday + " 10:00", monday + " 11:00");

        // 09:30, 10:00 and 10:30 would all overlap the 10:00-11:00 job.
        mvc.perform(get("/api/v1/providers/1/slots")
                        .param("from", monday.toString())
                        .param("days", "1")
                        .param("minutes", "60"))
                .andExpect(jsonPath("$", hasSize(11)));
    }

    @Test
    void aSlotLongerThanTheShiftIsNotOffered() throws Exception {
        mvc.perform(get("/api/v1/providers/1/slots")
                        .param("from", quietMonday().toString())
                        .param("days", "1")
                        .param("minutes", "300"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void aQuotePricesTheCouponWithoutUsingItUp() throws Exception {
        Integer before = jdbc.queryForObject("SELECT used_count FROM coupons WHERE code = 'NEWUSER25'", Integer.class);

        postJson(
                        "/api/v1/bookings/quote",
                        customer(1),
                        "{\"providerId\":2,\"couponCode\":\"newuser25\",\"items\":[{\"serviceId\":3,\"quantity\":1}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(1500.00))
                .andExpect(jsonPath("$.discount").value(375.00))
                .andExpect(jsonPath("$.totalAmount").value(1125.00))
                .andExpect(jsonPath("$.minutes").value(60));

        Integer after = jdbc.queryForObject("SELECT used_count FROM coupons WHERE code = 'NEWUSER25'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(before, after);
    }

    @Test
    void aQuoteRefusesACouponThisCustomerAlreadyUsed() throws Exception {
        // Customer 1 used WELCOME10 in the demo data.
        postJson(
                        "/api/v1/bookings/quote",
                        customer(1),
                        "{\"providerId\":2,\"couponCode\":\"WELCOME10\",\"items\":[{\"serviceId\":3,\"quantity\":1}]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:sevak:error:coupon-invalid"));
    }

    @Test
    void bookingDetailSaysWhetherItIsPaidAndReviewed() throws Exception {
        mvc.perform(get("/api/v1/me/bookings/1").header(AUTH, customer(1)))
                .andExpect(jsonPath("$.booking.paymentStatus").value("success"))
                .andExpect(jsonPath("$.booking.reviewed").value(true));
    }

    @Test
    void bookingListsNameTheServices() throws Exception {
        mvc.perform(get("/api/v1/me/bookings").header(AUTH, customer(1)))
                .andExpect(
                        jsonPath("$.data[?(@.bookingId == 1)].services", hasItem(containsString("Fan Installation"))));
    }

    // ── Provider settings ───────────────────────────────────

    @Test
    void overlappingHoursGetAFriendlyConflict() throws Exception {
        postJson(
                        "/api/v1/provider/availability",
                        provider(1),
                        "{\"dayOfWeek\":1,\"startTime\":\"12:00\",\"endTime\":\"15:00\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:sevak:error:slot-overlap"))
                .andExpect(jsonPath("$.detail", containsString("overlap")));
    }

    @Test
    void hoursCanBeAddedAndRemoved() throws Exception {
        postJson(
                        "/api/v1/provider/availability",
                        provider(1),
                        "{\"dayOfWeek\":7,\"startTime\":\"09:00\",\"endTime\":\"12:00\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dayOfWeek").value(7));
        Long slotId = jdbc.queryForObject(
                "SELECT slot_id FROM provider_availability WHERE provider_id = 1 AND day_of_week = 7", Long.class);

        mvc.perform(delete("/api/v1/provider/availability/" + slotId).header(AUTH, provider(1)))
                .andExpect(status().isNoContent());
        Integer left = jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_availability WHERE provider_id = 1 AND day_of_week = 7", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, left);
    }

    @Test
    void aProviderCannotRemoveSomeoneElsesHours() throws Exception {
        Long slotId = jdbc.queryForObject(
                "SELECT slot_id FROM provider_availability WHERE provider_id = 2 LIMIT 1", Long.class);
        mvc.perform(delete("/api/v1/provider/availability/" + slotId).header(AUTH, provider(1)))
                .andExpect(status().isNotFound());
    }

    @Test
    void endBeforeStartIsRefusedWithAReadableMessage() throws Exception {
        postJson(
                        "/api/v1/provider/availability",
                        provider(1),
                        "{\"dayOfWeek\":7,\"startTime\":\"15:00\",\"endTime\":\"09:00\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("after")));
    }

    @Test
    void servicesCanBeAddedPricedAndRemoved() throws Exception {
        postJson("/api/v1/provider/services", provider(1), "{\"serviceId\":5,\"price\":450}")
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/providers/1"))
                .andExpect(jsonPath("$.services[?(@.serviceId == 5)].price", hasItem(450.00)));

        mvc.perform(delete("/api/v1/provider/services/5").header(AUTH, provider(1)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/providers/1"))
                .andExpect(jsonPath("$.services[?(@.serviceId == 5)]").isEmpty());
    }

    @Test
    void addingAnUnknownServiceIsA404() throws Exception {
        postJson("/api/v1/provider/services", provider(1), "{\"serviceId\":9999,\"price\":450}")
                .andExpect(status().isNotFound());
    }

    @Test
    void monthlyEarningsAddUpToTheMethodBreakdown() throws Exception {
        mvc.perform(get("/api/v1/provider/earnings/monthly").header(AUTH, provider(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].month").value("2026-04"))
                .andExpect(jsonPath("$[0].totalEarned").value(4025.00));
    }

    @Test
    void aProviderSeesTheirOwnProfile() throws Exception {
        mvc.perform(get("/api/v1/provider/profile").header(AUTH, provider(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider.providerName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.provider.cityAverageRating").exists());
    }

    @Test
    void jobsListNameTheServices() throws Exception {
        mvc.perform(get("/api/v1/provider/jobs").param("status", "pending").header(AUTH, provider(1)))
                .andExpect(jsonPath("$.data[0].services", containsString("Full House Wiring")));
    }

    // ── Admin queues ────────────────────────────────────────

    @Test
    void theVerificationQueueListsPendingProviders() throws Exception {
        mvc.perform(get("/api/v1/admin/providers").param("status", "pending").header(AUTH, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.providerName == 'Meena Iyer')].verificationStatus", hasItem("pending")))
                .andExpect(content().string(not(containsString("@gmail.com"))));
    }

    @Test
    void theComplaintsBoardShowsNamesNotEmails() throws Exception {
        mvc.perform(get("/api/v1/admin/complaints/board").header(AUTH, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[?(@.complaintId == 1)].raisedByName", hasItem("Sneha Reddy")))
                .andExpect(content().string(not(containsString("@gmail.com"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "true"})
    void theLeaderboardShowsNamesNotEmails(String fast) throws Exception {
        mvc.perform(get("/api/v1/admin/leaderboard").param("fast", fast).header(AUTH, admin()))
                .andExpect(jsonPath("$[0].providerName").exists())
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(content().string(not(containsString("@gmail.com"))));
    }

    @Test
    void theFastLeaderboardNamesItsOwnQuery() throws Exception {
        mvc.perform(get("/api/v1/admin/leaderboard").param("fast", "true").header(AUTH, admin()))
                .andExpect(header().string("X-Sevak-Query", "admin.leaderboard.fast"));
        mvc.perform(get("/api/v1/admin/leaderboard").header(AUTH, admin()))
                .andExpect(header().string("X-Sevak-Query", "admin.leaderboard"));
    }

    // ── The SQL drawer ──────────────────────────────────────

    @Test
    void anEndpointNamesItsQuery() throws Exception {
        mvc.perform(get("/api/v1/providers")).andExpect(header().string("X-Sevak-Query", "providers.search"));
    }

    @Test
    void theHeaderIsExposedToTheBrowser() throws Exception {
        mvc.perform(get("/api/v1/providers").header("Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("X-Sevak-Query")));
    }

    @Test
    void aQueryCanBeReadWithoutLoggingIn() throws Exception {
        mvc.perform(get("/api/v1/meta/queries/providers.profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sql", containsString("avg_city_rating")))
                .andExpect(jsonPath("$.techniques", hasItem("Derived table")))
                .andExpect(jsonPath("$.measuredMs").isNumber())
                .andExpect(jsonPath("$.explainable").value(true));
    }

    @Test
    void aStatementThatWritesHasNoTiming() throws Exception {
        mvc.perform(get("/api/v1/meta/queries/bookings.create"))
                .andExpect(jsonPath("$.measuredMs", nullValue()))
                .andExpect(jsonPath("$.explainable").value(false));
    }

    @Test
    void anUnknownQueryIsA404() throws Exception {
        mvc.perform(get("/api/v1/meta/queries/nope")).andExpect(status().isNotFound());
    }

    @Test
    void explainIsAdminOnly() throws Exception {
        mvc.perform(post("/api/v1/admin/queries/admin.revenue/explain")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/queries/admin.revenue/explain").header(AUTH, customer(1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void explainRefusesAnythingOffTheAllowList() throws Exception {
        mvc.perform(post("/api/v1/admin/queries/DROP TABLE users/explain").header(AUTH, admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void explainNeverRunsAWrite() throws Exception {
        mvc.perform(post("/api/v1/admin/queries/bookings.create/explain").header(AUTH, admin()))
                .andExpect(status().isUnprocessableContent());
    }

    /** Every read query must really run, or the Run EXPLAIN button would fail on that screen. */
    @Test
    void everyExplainableQueryRuns() throws Exception {
        for (String key : catalog.keys()) {
            if (catalog.find(key).orElseThrow().explainParams() != null) {
                mvc.perform(post("/api/v1/admin/queries/" + key + "/explain").header(AUTH, admin()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.plan[0]").exists());
            }
        }
    }
}
