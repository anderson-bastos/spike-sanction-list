package com.spike.ofac.adapter.`in`.web

import com.spike.ofac.application.port.`in`.Page

import io.gatling.javaapi.core.CoreDsl.constantUsersPerSec
import io.gatling.javaapi.core.CoreDsl.details
import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.rampUsersPerSec
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.OpenInjectionStep
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import java.time.Duration

/**
 * Task 22.2 — Gatling load/latency test for the Query API endpoints.
 *
 * ## What this measures (and why)
 * A **NON-FUNCTIONAL performance guard** over the read side of the pipeline — the
 * two Query API endpoints served entirely from the `CURRENT` version pointer
 * (`design.md` "Performance testing" → "HTTP load/latency (Gatling)"):
 *
 *  - `GET /api/{sourceList}/records` — the paginated **list** endpoint
 *    (Req 16.1): configurable default page size 50, bounded max 1000, with
 *    `total`/`offset`/`limit` metadata.
 *  - `GET /api/{sourceList}/records/search?q=...` — the **name-search** endpoint
 *    (Req 16.3): a case-insensitive **contains** match over each record's primary
 *    name **and** its aliases. This is the path that leans on the trigram/GIN
 *    indexes (task 13.1); it is the more index-sensitive of the two and the main
 *    reason this guard exists.
 *
 * ## Latency target — framed against the atomic-activation SLA (Req 9.5)
 * Atomic activation guarantees a new `CURRENT` is resolvable within **5 s**
 * (Req 9.5). A read served through that same `CURRENT` pointer must stay well
 * inside that budget under load, so the assertions below hold the p99 of every
 * request under a fraction of it (see [P99_BUDGET_MS]) and require a fully
 * successful run. These are a regression tripwire, not a functional requirement:
 * correctness of the endpoints is covered by the Query API integration + property
 * tests (tasks 17.x), never here.
 *
 * ## Why it lives in its own source set / task
 * A load run is slow and needs a **running server**, unlike the pure-logic
 * suites. The `io.gatling.gradle` plugin keeps this simulation in the isolated
 * `gatling` source set and exposes it through the opt-in `gatlingRun` task, which
 * is deliberately NOT wired into `test` / `check` (see `build.gradle.kts`). k6 is
 * noted in the design as an equivalent alternative; Gatling is the primary tool.
 *
 * ## Running it (needs the app up)
 * ```
 * ./gradlew gatlingRun \
 *   -Dgatling.baseUrl=http://localhost:8080 \
 *   -Dgatling.sourceList=SDN \
 *   -Dgatling.searchTerm=IVAN
 * ```
 * All knobs read from `gatling.*` system properties with safe local defaults, so
 * the simulation compiles and the task registers with zero setup; only an actual
 * run needs the server.
 */
class QueryApiLoadSimulation : Simulation() {

    private companion object {
        /** Local default target; override with `-Dgatling.baseUrl=...`. */
        const val DEFAULT_BASE_URL = "http://localhost:8080"

        /**
         * Which `SourceList` to address in the `/api/{sourceList}/...` path. The
         * controller binds this segment to the `SourceList` enum (`SDN`,
         * `CONSOLIDATED`); override with `-Dgatling.sourceList=...`.
         */
        const val DEFAULT_SOURCE_LIST = "SDN"

        /**
         * The name-search term. A short, common substring keeps the *contains*
         * match meaningful (it should hit the primary-name + alias trigram path)
         * without depending on any single fixture record; override with
         * `-Dgatling.searchTerm=...`.
         */
        const val DEFAULT_SEARCH_TERM = "IVAN"

        /** Page size used by the list + search requests (Req 16.1 default). */
        const val PAGE_LIMIT = 50

        /**
         * p99 latency budget per request, in ms. Deliberately a small fraction of
         * the 5 s atomic-activation SLA (Req 9.5): a `CURRENT` read under load must
         * sit far inside the window in which a fresh `CURRENT` becomes resolvable.
         * Override with `-Dgatling.p99BudgetMs=...`.
         */
        const val P99_BUDGET_MS = 500

        /** Steady-state arrival rate (users/sec) once ramped; `-Dgatling.rate=...`. */
        const val DEFAULT_RATE = 20.0

        /** Ramp + steady-state durations (seconds); `-Dgatling.rampSeconds` / `-Dgatling.holdSeconds`. */
        const val DEFAULT_RAMP_SECONDS = 10L
        const val DEFAULT_HOLD_SECONDS = 30L

        fun prop(key: String, default: String): String =
            System.getProperty(key)?.takeIf { it.isNotBlank() } ?: default

        fun longProp(key: String, default: Long): Long =
            System.getProperty(key)?.toLongOrNull() ?: default

        fun doubleProp(key: String, default: Double): Double =
            System.getProperty(key)?.toDoubleOrNull() ?: default

        fun intProp(key: String, default: Int): Int =
            System.getProperty(key)?.toIntOrNull() ?: default
    }

    private val baseUrl = prop("gatling.baseUrl", DEFAULT_BASE_URL)
    private val sourceList = prop("gatling.sourceList", DEFAULT_SOURCE_LIST)
    private val searchTerm = prop("gatling.searchTerm", DEFAULT_SEARCH_TERM)
    private val rate = doubleProp("gatling.rate", DEFAULT_RATE)
    private val rampSeconds = longProp("gatling.rampSeconds", DEFAULT_RAMP_SECONDS)
    private val holdSeconds = longProp("gatling.holdSeconds", DEFAULT_HOLD_SECONDS)
    private val p99BudgetMs = intProp("gatling.p99BudgetMs", P99_BUDGET_MS)

    private val httpProtocol = http
        .baseUrl(baseUrl)
        .acceptHeader("application/json")
        .userAgentHeader("gatling-query-api-load/1.0")

    // --- Scenario 1: paginated list over CURRENT (Req 16.1, 16.2) ---
    private val listScenario = scenario("Query API — list (CURRENT)")
        .exec(
            http("list")
                .get("/api/$sourceList/records")
                .queryParam("offset", "0")
                .queryParam("limit", PAGE_LIMIT.toString())
                .check(status().shouldBe(200)),
        )

    // --- Scenario 2: name-search contains match over primary name + aliases,
    //     exercising the trigram/GIN indexes (Req 16.3, task 13.1) ---
    private val searchScenario = scenario("Query API — name search (contains)")
        .exec(
            http("search")
                .get("/api/$sourceList/records/search")
                .queryParam("q", searchTerm)
                .queryParam("offset", "0")
                .queryParam("limit", PAGE_LIMIT.toString())
                .check(status().shouldBe(200)),
        )

    private fun injection(): OpenInjectionStep =
        rampUsersPerSec(1.0).to(rate).during(Duration.ofSeconds(rampSeconds))

    init {
        setUp(
            listScenario.injectOpen(
                injection(),
                constantUsersPerSec(rate).during(Duration.ofSeconds(holdSeconds)),
            ),
            searchScenario.injectOpen(
                injection(),
                constantUsersPerSec(rate).during(Duration.ofSeconds(holdSeconds)),
            ),
        )
            .protocols(httpProtocol)
            .assertions(
                // No failed requests: a load run that errors out is not a valid
                // latency measurement and would mask a regression.
                global().failedRequests().count().shouldBe(0L),
                // Every request's p99 stays a fraction of the 5 s atomic-activation
                // SLA (Req 9.5) — the read-side latency tripwire.
                global().responseTime().percentile(99.0).lt(p99BudgetMs),
                // Per-endpoint guard so the index-sensitive name-search path is
                // held to the same bar as the plain list (Req 16.3 vs 16.1).
                details("search").responseTime().percentile(99.0).lt(p99BudgetMs),
                details("list").responseTime().percentile(99.0).lt(p99BudgetMs),
            )
    }
}
