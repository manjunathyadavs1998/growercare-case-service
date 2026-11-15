package com.growercare.casesvc.adapters.out.archive;

import com.growercare.casesvc.application.port.ArchiveCaseQueryPort;
import com.growercare.casesvc.domain.CaseRecord;
import com.growercare.common.error.ArchiveNotEnabledException;
import com.growercare.security.GcPrincipal;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/** Placeholder until the Snowflake adapter (data-lifecycle-service) exists: 501 (SPEC-CASE-02 D4, R33). */
@Component
class ArchiveNotEnabledAdapter implements ArchiveCaseQueryPort {

    @Override
    public List<CaseRecord> list(GcPrincipal principal, Instant createdFrom, int limit, String cursor) {
        throw new ArchiveNotEnabledException();
    }
}
