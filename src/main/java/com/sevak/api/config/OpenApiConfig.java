package com.sevak.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI sevakOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SEVAK — Service Marketplace API")
                        .description("""
                                RESTful API for the SEVAK Service Marketplace Database.
                                Built on a BCNF-normalized 23-table PostgreSQL schema featuring
                                advanced SQL queries (7-table JOINs, Window Functions, Stored Procedures).
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Madhav")
                                .url("https://github.com/madhavthesiya")));
    }
}
