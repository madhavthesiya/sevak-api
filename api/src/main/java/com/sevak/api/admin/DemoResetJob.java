package com.sevak.api.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs only where sevak.demo.reset-cron is set; "-" turns it off. */
@Component
public class DemoResetJob {

    private static final Logger log = LoggerFactory.getLogger(DemoResetJob.class);

    private final DemoResetService service;
    private final AdminService admin;

    public DemoResetJob(DemoResetService service, AdminService admin) {
        this.service = service;
        this.admin = admin;
    }

    @Scheduled(cron = "${sevak.demo.reset-cron}", zone = "Asia/Kolkata")
    public void reset() {
        service.reset();
        // After the reset's transaction has committed, so the stored summaries see the new data.
        admin.refreshViews();
        log.info("Demo data has been reset");
    }
}
