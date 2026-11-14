package com.sevak.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

/** Bad requests get the right status code, and errors never expose internal details. */
class ApiErrorsTest extends IntegrationTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/services?page=0",
                "/api/v1/services?limit=0",
                "/api/v1/services?limit=101",
                "/api/v1/providers/top?limit=0",
                "/api/v1/providers/top?limit=51",
                "/api/v1/providers?day=Funday",
                "/api/v1/providers/abc"
            })
    void invalidInputReturns400(String url) throws Exception {
        mvc.perform(get(url))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:sevak:error:validation-error"));
    }

    @Test
    void validationErrorNamesTheParameterNotTheJavaMethod() throws Exception {
        mvc.perform(get("/api/v1/services?page=0"))
                .andExpect(jsonPath("$.detail").value("page: must be greater than or equal to 1"));
    }

    @Test
    void anOverlongSearchIsRejected() throws Exception {
        mvc.perform(get("/api/v1/providers").param("service", "x".repeat(101))).andExpect(status().isBadRequest());
    }

    @Test
    void aHugePageNumberIsSafe() throws Exception {
        mvc.perform(get("/api/v1/services").param("page", String.valueOf(Integer.MAX_VALUE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/does-not-exist", "/api/v1/providers/9999", "/api/v1/me/bookings/9999"})
    void missingThingsReturn404(String url) throws Exception {
        mvc.perform(get(url).header(AUTH, customer(1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:sevak:error:not-found"));
    }

    @Test
    void providerAndAdminInputIsValidated() throws Exception {
        mvc.perform(get("/api/v1/provider/jobs?status=nonsense").header(AUTH, provider(1)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/provider/earnings?from=2026-05-01&to=2026-04-01")
                        .header(AUTH, provider(1)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/provider/earnings?from=yesterday").header(AUTH, provider(1)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/bookings?beforeId=5").header(AUTH, admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wrongMethodReturns405() throws Exception {
        mvc.perform(post("/api/v1/services").header(AUTH, admin()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.type").value("urn:sevak:error:method-not-allowed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Monday", "monday", "MONDAY", " Monday "})
    void dayIsAcceptedInAnyLetterCase(String day) throws Exception {
        mvc.perform(get("/api/v1/providers").param("day", day))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(8));
    }

    @Test
    void unexpectedDatabaseErrorReturnsGeneric500() throws Exception {
        // The revenue chart reads this stored summary; renaming it breaks the query. Rolled back after the test.
        jdbc.execute("ALTER MATERIALIZED VIEW mv_category_revenue RENAME TO mv_category_revenue_renamed");

        mvc.perform(get("/api/v1/admin/revenue").header(AUTH, admin()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Something went wrong"))
                .andExpect(jsonPath("$.type").value("urn:sevak:error:internal"));
    }

    @Test
    void cancelNeedsAReason() throws Exception {
        mvc.perform(post("/api/v1/bookings/6/cancel")
                        .header(AUTH, customer(5))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("reason: must not be blank"));
    }

    @Test
    void cancelWithBrokenJsonReturns400() throws Exception {
        mvc.perform(post("/api/v1/bookings/6/cancel")
                        .header(AUTH, customer(5))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest());
    }
}
