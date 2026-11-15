package com.growercare.casesvc.config;

import com.growercare.casesvc.application.port.ReferenceData;
import com.growercare.casesvc.domain.RoutingRules;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** SPEC-CASE-04: routing sanity check at startup (R89) and the scheduling switch for jobs (D7). */
@Configuration(proxyBeanMethods = false)
public class CaseJobsConfig {

    /** R89: refuse to start when an active country has no fallback routing rule (routing could fail at runtime). */
    @Bean
    ApplicationRunner routingFallbackCheck(ReferenceData ref) {
        return args -> RoutingRules.requireFallback(ref.routingRules(), ref.activeCountries());
    }

    /** Off in tests ({@code gc.jobs.enabled=false}), which call the job methods directly. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "gc.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling { }
}
