package com.sevak.api.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Keeps the materialized views fresh without anyone having to call the refresh endpoint. */
@Configuration
@EnableScheduling
public class ViewRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(ViewRefreshJob.class);

    private final AdminService service;

    public ViewRefreshJob(AdminService service) {
        this.service = service;
    }

    /** Fills the views once the demo data is in, so nothing reads an out-of-date copy after a deploy. */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        try {
            log.info("Startup view refresh: {}", service.refreshViews().message());
        } catch (RuntimeException e) {
            // Stale summaries are better than no app; the scheduled refresh tries again.
            log.warn("Startup view refresh failed; the scheduled refresh will retry", e);
        }
    }

    @Scheduled(
            fixedDelayString = "${sevak.views.refresh-interval}",
            initialDelayString = "${sevak.views.refresh-interval}")
    public void refresh() {
        log.info("Scheduled view refresh: {}", service.refreshViews().message());
    }
}
