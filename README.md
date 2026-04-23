<div align="center">

# ⬡ SEVAK — Service Marketplace API

**A production-grade REST API built on a BCNF-normalized 23-table PostgreSQL schema**

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.4-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Swagger](https://img.shields.io/badge/Swagger-API%20Docs-85EA2D?logo=swagger&logoColor=black)](https://sevak-api.example.com/swagger-ui.html)

[Live Demo](https://sevak-api.example.com/swagger-ui.html) · [API Docs](https://sevak-api.example.com/api-docs) · [Architecture](#architecture)

</div>

---

## 📋 Overview

**SEVAK** is a hyperlocal home-services marketplace (similar to Urban Company) where customers can discover, book, and review service providers for tasks like electrical work, plumbing, cleaning, and painting.

This project focuses on **database engineering excellence** — featuring a rigorously normalized schema, advanced SQL analytics, and a clean REST API layer that exposes the full power of PostgreSQL.

### Highlights

- 🏗️ **23-table BCNF-normalized schema** with mathematical proof of compliance
- 📊 **19 REST endpoints** showcasing advanced SQL (Window Functions, Derived Tables, STRING_AGG)
- ⚡ **Stored Procedures** for transactional operations (booking cancellation/confirmation)
- 🔄 **Database Triggers** for automatic rating recalculation on review insert
- 📖 **Interactive Swagger UI** for live API exploration — no frontend needed

---

## 🏛️ Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     Swagger UI (Browser)                     │
│              Interactive API Documentation                   │
└──────────────────────┬───────────────────────────────────────┘
                       │  HTTP (JSON)
┌──────────────────────▼───────────────────────────────────────┐
│                   Spring Boot 4.0.4                          │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────────┐    │
│  │ Controllers │→ │ Repositories │→ │   JdbcTemplate   │    │
│  │ (REST URLs) │  │  (Raw SQL)   │  │ (Query Executor) │    │
│  └─────────────┘  └──────────────┘  └────────┬─────────┘    │
│  ┌─────────────┐  ┌──────────────┐           │              │
│  │ CORS Config │  │  Error Hndlr │           │              │
│  └─────────────┘  └──────────────┘           │              │
└──────────────────────────────────────────────┼──────────────┘
                                               │  JDBC
┌──────────────────────────────────────────────▼──────────────┐
│                    PostgreSQL 16                             │
│  ┌────────────────────────────────────────────────────┐     │
│  │           sevak schema (23 tables)                 │     │
│  │  ┌───────┐ ┌──────────┐ ┌─────────┐ ┌─────────┐  │     │
│  │  │ users │ │ bookings │ │ reviews │ │locations│  │     │
│  │  └───┬───┘ └────┬─────┘ └────┬────┘ └────┬────┘  │     │
│  │      └──────────┴────────────┴────────────┘       │     │
│  │  Triggers · Stored Procedures · Indexes           │     │
│  └────────────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────────┘
```

---

## 🗄️ Database Design

### Entity-Relationship Summary

| Domain | Tables | Description |
|:-------|:------:|:------------|
| **Auth** | 3 | `users`, `customers`, `admins` — ISA hierarchy with role-based access |
| **Providers** | 4 | `service_providers`, `provider_documents`, `provider_availability`, `provider_services` |
| **Services** | 3 | `categories`, `services`, `service_variants` — hierarchical catalog |
| **Location** | 4 | `cities`, `areas`, `locations`, `customer_addresses` — BCNF-compliant geo data |
| **Bookings** | 4 | `bookings`, `booking_items`, `booking_status_log`, `coupons` |
| **Payments** | 2 | `payments`, `cancellations` — 1:1 with bookings |
| **Reviews** | 3 | `provider_reviews`, `service_reviews`, `complaints` |

### BCNF Compliance

The schema underwent a targeted normalization refactor to resolve a hidden dependency:

```
BEFORE (violated BCNF):
  addresses(address_id, customer_id, area_id, street, landmark, label, latitude, longitude)
  FD: {latitude, longitude} → {area_id, street, landmark}  ← NOT a superkey!

AFTER (BCNF compliant):
  locations(location_id, area_id, street, landmark, latitude, longitude)
    → {latitude, longitude} is now a UNIQUE candidate key ✅
  customer_addresses(customer_id, location_id, label)
    → Pure junction table, all-key ✅
```

📄 Full mathematical proof: [`SEVAK_Normalization_Proof.md`](docs/SEVAK_Normalization_Proof.md)

---

## 🔌 API Endpoints

### 1. General — Browse & Search
| Method | Endpoint | SQL Technique |
|:-------|:---------|:-------------|
| `GET` | `/api/general/services` | JOIN, ORDER BY |
| `GET` | `/api/general/services/search?q={keyword}` | 5-table JOIN, ILIKE |
| `GET` | `/api/general/providers/top` | JOIN, LIMIT, NULLS LAST |
| `GET` | `/api/general/providers/available?day={day}` | Parameterized JOIN |

### 2. Customer — Bookings & Addresses
| Method | Endpoint | SQL Technique |
|:-------|:---------|:-------------|
| `GET` | `/api/customers/{id}/bookings` | 3-table JOIN |
| `GET` | `/api/customers/{id}/bookings/{bid}/items` | LEFT JOIN, computed columns |
| `GET` | `/api/customers/{id}/payments` | JOIN, NULLS LAST |
| `GET` | `/api/customers/{id}/spending` | CASE, SUM, COUNT, GROUP BY |
| `GET` | `/api/customers/{id}/addresses` | **4-table JOIN** (BCNF path) |

### 3. Provider — Jobs & Analytics
| Method | Endpoint | SQL Technique |
|:-------|:---------|:-------------|
| `GET` | `/api/providers/{id}/pending` | 3-table JOIN via locations |
| `GET` | `/api/providers/{id}/completed` | LEFT JOIN |
| `GET` | `/api/providers/{id}/earnings` | SUM, COUNT, GROUP BY |
| `GET` | `/api/providers/{id}/reviews` | 3-table JOIN via bookings |
| `GET` | `/api/providers/{id}/rating` | **Derived Table (Subquery in FROM)** |

### 4. Admin — Analytics Dashboard
| Method | Endpoint | SQL Technique |
|:-------|:---------|:-------------|
| `GET` | `/api/admin/revenue` | 5-table JOIN, SUM, GROUP BY |
| `GET` | `/api/admin/leaderboard` | **RANK() Window Function** |
| `GET` | `/api/admin/complaints` | **STRING_AGG**, 5-table JOIN |

### 5. Booking Actions — Stored Procedures
| Method | Endpoint | SQL Technique |
|:-------|:---------|:-------------|
| `POST` | `/api/bookings/{id}/cancel` | **Stored Procedure** (sp_cancel_booking) |
| `POST` | `/api/bookings/{id}/confirm` | **Stored Procedure** (sp_confirm_booking) |

---

## 🚀 Quick Start

### Prerequisites
- Java 21+
- PostgreSQL 16+
- Maven 3.9+

### Local Development

```bash
# 1. Clone the repo
git clone https://github.com/madhavthesiya/sevak-api.git
cd sevak-api

# 2. Configure environment
cp .env.example .env
# Edit .env and set your PostgreSQL password

# 3. Set up database
psql -U postgres -c "CREATE DATABASE sevak_db"
psql -U postgres -d sevak_db -f sql/sevak_ddl.sql
psql -U postgres -d sevak_db -f sql/sevak_data.sql

# 4. Run the API
export DB_PASSWORD=your_password   # Linux/Mac
$env:DB_PASSWORD="your_password"   # Windows PowerShell
mvn spring-boot:run

# 5. Open Swagger UI
open http://localhost:8080/swagger-ui.html
```

### Docker (One Command)

```bash
docker-compose up --build
# API:     http://localhost:8080/swagger-ui.html
# Health:  http://localhost:8080/health
```

---

## 🛠️ Tech Stack

| Layer | Technology | Purpose |
|:------|:-----------|:--------|
| Runtime | Java 21 (Temurin) | Long-term support JDK |
| Framework | Spring Boot 4.0.4 | REST API + dependency injection |
| Database | PostgreSQL (Supabase) | Managed cloud PostgreSQL |
| DB Access | Spring JDBC (JdbcTemplate) | Raw SQL execution — intentionally chosen over JPA to showcase SQL proficiency |
| API Docs | springdoc-openapi 3.0.2 | Auto-generated Swagger UI |
| Container | Docker + Docker Compose | Reproducible deployments |
| API Hosting | DigitalOcean App Platform | Production API deployment |
| DB Hosting | Supabase | Free-tier managed PostgreSQL |

---

## 📁 Project Structure

```
sevak-api/
├── src/main/java/com/sevak/api/
│   ├── SevakApiApplication.java          # Entry point
│   ├── config/
│   │   ├── OpenApiConfig.java            # Swagger metadata
│   │   ├── CorsConfig.java              # Cross-origin config
│   │   └── GlobalExceptionHandler.java   # JSON error responses
│   ├── controller/
│   │   ├── GeneralController.java        # 4 browse/search endpoints
│   │   ├── CustomerController.java       # 5 customer endpoints
│   │   ├── ProviderController.java       # 5 provider endpoints
│   │   ├── AdminController.java          # 3 analytics endpoints
│   │   ├── BookingActionController.java  # 2 stored procedure endpoints
│   │   └── HealthController.java         # /health check
│   └── repository/
│       ├── GeneralRepository.java        # Browse & search queries
│       ├── CustomerRepository.java       # Customer data queries
│       ├── ProviderRepository.java       # Provider analytics queries
│       ├── AdminRepository.java          # Admin dashboard queries
│       └── BookingActionRepository.java  # Stored procedure calls
├── src/main/resources/
│   ├── application.properties            # Dev config
│   └── application-prod.properties       # Production config
├── sql/
│   ├── sevak_ddl.sql                     # Schema (23 tables)
│   └── sevak_data.sql                    # Seed data
├── Dockerfile                            # Multi-stage build
├── docker-compose.yml                    # Full-stack local setup
└── pom.xml                               # Dependencies
```

---

## 📊 Advanced SQL Showcase

This project intentionally uses **Spring JDBC** instead of JPA/Hibernate to demonstrate advanced SQL capabilities:

<details>
<summary><b>Window Function — Provider Leaderboard</b></summary>

```sql
SELECT
    RANK() OVER (ORDER BY sp.avg_rating DESC NULLS LAST,
                 COUNT(b.booking_id) DESC)   AS rank,
    sp.provider_id, u.email, ci.city_name,
    sp.avg_rating,
    COUNT(b.booking_id)                      AS jobs_completed,
    COALESCE(SUM(b.total_amount), 0)         AS total_revenue
FROM service_providers sp
JOIN users u ON sp.user_id = u.user_id
JOIN cities ci ON sp.city_id = ci.city_id
LEFT JOIN bookings b ON sp.provider_id = b.provider_id
                    AND b.status = 'completed'
WHERE sp.is_active = TRUE
GROUP BY sp.provider_id, u.email, ci.city_name, sp.avg_rating
ORDER BY rank
```
</details>

<details>
<summary><b>Derived Table — Rating vs City Average</b></summary>

```sql
SELECT sp.provider_id, u.email, ci.city_name,
       sp.avg_rating AS my_rating,
       city_avg.avg_city_rating,
       CASE
           WHEN sp.avg_rating > city_avg.avg_city_rating THEN 'Above Average'
           WHEN sp.avg_rating = city_avg.avg_city_rating THEN 'Average'
           ELSE 'Below Average'
       END AS comparison
FROM service_providers sp
JOIN users u ON sp.user_id = u.user_id
JOIN cities ci ON sp.city_id = ci.city_id
JOIN (
    SELECT city_id, ROUND(AVG(avg_rating), 2) AS avg_city_rating
    FROM service_providers WHERE avg_rating IS NOT NULL
    GROUP BY city_id
) city_avg ON sp.city_id = city_avg.city_id
WHERE sp.provider_id = ?
```
</details>

<details>
<summary><b>Stored Procedure — Booking Cancellation</b></summary>

```sql
CALL sp_cancel_booking(booking_id, 'Customer changed mind');
-- Atomically: updates status, creates cancellation record,
-- processes refund if payment exists — all in one transaction
```
</details>

---

## 📄 License

This project is licensed under the MIT License.
