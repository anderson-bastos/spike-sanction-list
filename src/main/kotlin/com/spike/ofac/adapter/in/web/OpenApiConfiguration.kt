package com.spike.ofac.adapter.`in`.web

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI metadata for the read-only Query API (API-first).
 *
 * springdoc exposes the generated contract at `/v3/api-docs` (JSON) and
 * `/v3/api-docs.yaml` (YAML), plus Swagger UI at `/swagger-ui.html`. The
 * versioned `src/main/resources/openapi.yaml` is the **source of truth**; a
 * contract test (`OpenApiContractTest`, integrationTest source set) asserts the
 * doc springdoc generates from the code still matches that file, failing `check`
 * on any drift — so the code can never silently diverge from the published
 * contract.
 *
 * This bean only supplies the top-level `info` block; the paths/schemas are
 * derived from the annotated [QueryController] and the [com.spike.ofac.application.port.`in`.Page]
 * response type.
 */
@Configuration
class OpenApiConfiguration {

    @Bean
    fun ofacQueryApiOpenApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("OFAC Sanctions Ingestion — Query API")
                .description(
                    "Read-only API over the CURRENT version of each Source_List. " +
                        "Paginated list + case-insensitive contains name search over " +
                        "primary name and aliases. Serves only CURRENT (never " +
                        "PREVIOUS/N_MINUS_2/COLD) and never mutates any version, pointer, or record.",
                )
                .version("v1")
                .license(License().name("Internal").url("https://example.invalid/license")),
        )
}
