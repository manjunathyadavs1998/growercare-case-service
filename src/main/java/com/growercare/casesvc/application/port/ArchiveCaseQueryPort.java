package com.growercare.casesvc.application.port;

import com.growercare.casesvc.domain.CaseRecord;
import com.growercare.security.GcPrincipal;
import java.time.Instant;
import java.util.List;

/**
 * Cold store (Snowflake, ADR-003): cases older than the hot window. Until the data-lifecycle adapter exists the
 * placeholder throws {@link com.growercare.common.error.ArchiveNotEnabledException} (501, decision D4).
 * A Snowflake adapter implements this port with SQL in {@code sql/snowflake/} — use cases do not change.
 */
public interface ArchiveCaseQueryPort {

    List<CaseRecord> list(GcPrincipal principal, Instant createdFrom, int limit, String cursor);
}
