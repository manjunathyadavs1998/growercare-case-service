package com.growercare.casesvc.application;

import com.growercare.casesvc.application.port.ReferenceData;
import com.growercare.security.RequiresPermission;
import java.util.List;
import org.springframework.stereotype.Service;

/** SPEC-CASE-10: the workflow's reference data for the UI (codes only; the UI translates them). */
@Service
public class CaseTypeQueries {

    private final ReferenceData ref;

    public CaseTypeQueries(ReferenceData ref) {
        this.ref = ref;
    }

    @RequiresPermission("case:read")
    public List<ReferenceData.CaseTypeInfo> caseTypes() {
        return ref.caseTypesWithStatuses();
    }
}
