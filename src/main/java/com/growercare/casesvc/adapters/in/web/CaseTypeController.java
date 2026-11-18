package com.growercare.casesvc.adapters.in.web;

import com.growercare.casesvc.application.CaseTypeQueries;
import com.growercare.casesvc.application.port.ReferenceData.CaseTypeInfo;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** SPEC-CASE-10 §3: case types and their statuses (R220: cacheable for 5 minutes). */
@RestController
class CaseTypeController {

    private final CaseTypeQueries queries;

    CaseTypeController(CaseTypeQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/case-types")
    ResponseEntity<List<CaseTypeInfo>> caseTypes() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(300)).cachePrivate()).body(queries.caseTypes());
    }
}
