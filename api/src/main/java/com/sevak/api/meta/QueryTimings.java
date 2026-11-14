package com.sevak.api.meta;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Measured query times, written by QueryBenchmark to benchmarks/query-timings.json. Nothing here is
 * typed in by hand: if the file is missing, every query simply shows as not measured.
 */
@Component
public class QueryTimings {

    private static final String FILE = "benchmarks/query-timings.json";

    private final Map<String, Double> medianMs = new HashMap<>();

    public QueryTimings(JsonMapper json) {
        ClassPathResource resource = new ClassPathResource(FILE);
        if (!resource.exists()) {
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode timings = json.readTree(in).path("timings");
            timings.properties()
                    .forEach(entry ->
                            medianMs.put(entry.getKey(), entry.getValue().asDouble()));
        } catch (IOException e) {
            throw new IllegalStateException("Can't read " + FILE, e);
        }
    }

    public Optional<Double> medianMs(String key) {
        return Optional.ofNullable(medianMs.get(key));
    }
}
