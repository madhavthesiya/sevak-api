package com.sevak.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sevak.api.admin.AdminRepository;
import com.sevak.api.admin.dto.CategoryRevenue;
import com.sevak.api.admin.dto.LeaderboardEntry;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The numbers the API reports are correct, and customers only see their own data. */
class DataCorrectnessTest extends IntegrationTest {

    @Autowired
    private AdminRepository admin;

    private BigDecimal totalCategoryRevenue() {
        return admin.computeRevenueByCategory().stream()
                .map(CategoryRevenue::totalRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal totalSuccessfulPayments() {
        return jdbc.queryForObject("SELECT SUM(amount) FROM payments WHERE status = 'success'", BigDecimal.class);
    }

    private BigDecimal revenueOf(String category) {
        return admin.computeRevenueByCategory().stream()
                .filter(row -> category.equals(row.categoryName()))
                .map(CategoryRevenue::totalRevenue)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void everyQueryInTheIntegrityCheckFileReturnsNoRows() throws Exception {
        String script = Files.readAllLines(Path.of("sql/checks/data_integrity.sql")).stream()
                .filter(line -> !line.startsWith("--"))
                .collect(Collectors.joining("\n"));
        List<String> queries = Arrays.stream(script.split(";"))
                .map(String::trim)
                .filter(sql -> sql.toUpperCase().startsWith("SELECT")
                        || sql.toUpperCase().startsWith("WITH"))
                .toList();

        assertThat(queries).hasSize(4);
        for (String sql : queries) {
            assertThat(jdbc.queryForList(sql)).as(sql).isEmpty();
        }
    }

    @Test
    void bookingWithTwoItemsInOneCategoryIsNotCountedTwice() {
        assertThat(revenueOf("Electrical")).isEqualByComparingTo("495.00");
        assertThat(revenueOf("Cleaning")).isEqualByComparingTo("4025.00");
        assertThat(revenueOf("Plumbing")).isEqualByComparingTo("1400.00");
        assertThat(revenueOf("Painting")).isEqualByComparingTo("5000.00");
        assertThat(totalCategoryRevenue()).isEqualByComparingTo(totalSuccessfulPayments());
    }

    @Test
    void paymentForAMultiCategoryBookingIsSplitBetweenCategories() {
        // Booking 9 is one ₹5000 Painting item, paid ₹5000; add a Cleaning item of the same value.
        jdbc.update("INSERT INTO booking_items (booking_id, item_no, service_id, quantity, unit_price)"
                + " VALUES (9, 2, 4, 1, 5000.00)");
        jdbc.update("UPDATE bookings SET subtotal = subtotal + 5000, total_amount = total_amount + 5000"
                + " WHERE booking_id = 9");
        // Run the deferred subtotal check now, to prove this is a state the database would commit.
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");

        assertThat(revenueOf("Painting")).isEqualByComparingTo("2500.00");
        assertThat(revenueOf("Cleaning")).isEqualByComparingTo("6525.00");
        assertThat(totalCategoryRevenue()).isEqualByComparingTo(totalSuccessfulPayments());
    }

    @Test
    void storedCategoryRevenueMatchesTheLiveCalculation() {
        assertThat(admin.findRevenueByCategory()).isNotEmpty().isEqualTo(admin.computeRevenueByCategory());
    }

    @Test
    void liveLeaderboardMatchesTheMaterializedView() {
        List<LeaderboardEntry> live = admin.findLeaderboard();

        assertThat(live).isNotEmpty();
        assertThat(admin.findLeaderboardFromView()).isEqualTo(live);
    }

    @Test
    void providersWithNoReviewsHaveNoRating() {
        assertThat(jdbc.queryForObject(
                        "SELECT avg_rating FROM service_providers WHERE provider_id = 5", BigDecimal.class))
                .isNull();
    }

    @Test
    void spendingSeparatesPaidUpcomingAndRefunded() throws Exception {
        mvc.perform(get("/api/v1/me/spending").header(AUTH, customer(3)))
                .andExpect(jsonPath("$.paid").value(0))
                .andExpect(jsonPath("$.upcoming").value(350.00))
                .andExpect(jsonPath("$.refunded").value(350.00));
    }

    @Test
    void aPrepaidBookingIsPaidButNotUpcoming() throws Exception {
        jdbc.update(
                "INSERT INTO payments (booking_id, payment_method, status, amount) VALUES (4, 'UPI', 'success', 350)");

        mvc.perform(get("/api/v1/me/spending").header(AUTH, customer(3)))
                .andExpect(jsonPath("$.paid").value(350.00))
                .andExpect(jsonPath("$.upcoming").value(0));
    }

    @Test
    void searchHidesProvidersWhoAreNotVerified() throws Exception {
        mvc.perform(get("/api/v1/providers?service=fan"))
                .andExpect(jsonPath("$.total").value(1));

        jdbc.update("UPDATE service_providers SET verification_status = 'pending' WHERE provider_id = 1");

        mvc.perform(get("/api/v1/providers?service=fan"))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void aCustomerCannotReadAnotherCustomersBooking() throws Exception {
        mvc.perform(get("/api/v1/me/bookings/1").header(AUTH, customer(2))).andExpect(status().isNotFound());
    }

    @Test
    void theOwnerSeesTheItemsPriceBreakdownAndTimeline() throws Exception {
        mvc.perform(get("/api/v1/me/bookings/1").header(AUTH, customer(1)))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.timeline.length()").value(4))
                .andExpect(jsonPath("$.booking.subtotal").value(550.00))
                .andExpect(jsonPath("$.booking.couponCode").value("WELCOME10"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // the refresh commits on its own connection
    void refreshViewsSucceedsWithTheApiKey() throws Exception {
        mvc.perform(post("/api/v1/admin/refresh-views").header(AUTH, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshed").value(true));
    }

    @Test
    void everyShowcaseQueryRunsAgainstTheCurrentSchema() throws Exception {
        String script = Files.readAllLines(Path.of("sql/sevak_queries.sql")).stream()
                .filter(line -> !line.startsWith("--"))
                .collect(Collectors.joining("\n"));
        List<String> queries = Arrays.stream(script.split(";"))
                .map(String::trim)
                .filter(sql -> sql.toUpperCase().startsWith("SELECT"))
                .toList();

        assertThat(queries).hasSize(15);
        for (String sql : queries) {
            jdbc.queryForList(sql);
        }
    }

    @Test
    void aFailedPaymentAttemptIsNotCountedAsPaid() throws Exception {
        // Booking 1 has a failed attempt and then a successful one for 495.
        mvc.perform(get("/api/v1/me/spending").header(AUTH, customer(1)))
                .andExpect(jsonPath("$.totalBookings").value(2))
                .andExpect(jsonPath("$.paid").value(2495.00));
    }

    @Test
    void aBookingWithSeveralPaymentAttemptsAppearsOnceInCompletedJobs() throws Exception {
        mvc.perform(get("/api/v1/provider/jobs?status=completed").header(AUTH, provider(1)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].paymentStatus").value("success"));
    }

    @Test
    void searchShowsTheProvidersOwnPrice() throws Exception {
        mvc.perform(get("/api/v1/providers?service=fan"))
                .andExpect(jsonPath("$.data[0].basePrice").value(200.00))
                .andExpect(jsonPath("$.data[0].price").value(220.00));
    }

    @Test
    void addressesIncludeTheFlatNumber() throws Exception {
        mvc.perform(get("/api/v1/me/addresses").header(AUTH, customer(1)))
                .andExpect(jsonPath("$[0].houseNo").exists());
    }

    @Test
    void aSoftDeletedAddressIsHidden() throws Exception {
        jdbc.update("UPDATE customer_addresses SET is_active = FALSE WHERE customer_id = 1 AND location_id = 1");

        mvc.perform(get("/api/v1/me/addresses").header(AUTH, customer(1)))
                .andExpect(jsonPath("$.length()").value(1));
    }
}
