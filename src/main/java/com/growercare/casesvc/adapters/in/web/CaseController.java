package com.growercare.casesvc.adapters.in.web;

import static com.growercare.casesvc.adapters.in.web.CaseDtos.caseIdOrNotFound;
import static com.growercare.casesvc.adapters.in.web.CaseDtos.etag;
import static com.growercare.casesvc.adapters.in.web.CaseDtos.optionalId;
import static com.growercare.casesvc.adapters.in.web.CaseDtos.version;

import com.growercare.casesvc.adapters.in.web.CaseDtos.ActivityView;
import com.growercare.casesvc.adapters.in.web.CaseDtos.AssignRequest;
import com.growercare.casesvc.adapters.in.web.CaseDtos.CasePage;
import com.growercare.casesvc.adapters.in.web.CaseDtos.CaseView;
import com.growercare.casesvc.adapters.in.web.CaseDtos.CreateCaseRequest;
import com.growercare.casesvc.adapters.in.web.CaseDtos.TransitionRequest;
import com.growercare.casesvc.application.CaseCommands;
import com.growercare.casesvc.application.CaseQueries;
import com.growercare.casesvc.domain.CaseRecord;
import com.growercare.casesvc.domain.NewCaseCommand;
import com.growercare.security.web.CurrentPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SPEC-CASE-02 §3. Translation only: HTTP ⇄ commands/queries; rules and authorization live in the application layer. */
@RestController
@Validated
class CaseController {

    private final CaseCommands commands;
    private final CaseQueries queries;
    private final Clock clock;

    CaseController(CaseCommands commands, CaseQueries queries, Clock clock) {
        this.commands = commands;
        this.queries = queries;
        this.clock = clock;
    }

    @PostMapping("/cases")
    ResponseEntity<CaseView> create(@RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody CreateCaseRequest body) {
        var command = new NewCaseCommand(
                Long.parseLong(body.growerId()), body.caseType(), body.title(), body.description(),
                optionalId(body.farmId()), optionalId(body.productId()), body.batchNo(), body.buCode(), body.priority(),
                CaseDtos.uuid(idempotencyKey, "Idempotency-Key"));
        var result = commands.create(command);
        var view = view(result.record());
        if (result.replayed()) {
            return ResponseEntity.ok().eTag(etag(view.version())).header("Idempotent-Replayed", "true").body(view);
        }
        return ResponseEntity.created(URI.create("/cases/" + view.caseId())).eTag(etag(view.version())).body(view);
    }

    @GetMapping("/cases/{caseId}")
    ResponseEntity<CaseView> get(@PathVariable String caseId) {
        return withEtag(queries.get(caseIdOrNotFound(caseId)));
    }

    @GetMapping("/cases/by-no/{caseNo}")
    ResponseEntity<CaseView> getByNumber(@PathVariable String caseNo) {
        return withEtag(queries.getByNumber(caseNo));
    }

    @GetMapping("/cases")
    CasePage list(@RequestParam(required = false) @Pattern(regexp = CaseDtos.CODE) String status,
                  @RequestParam(required = false) @Pattern(regexp = CaseDtos.CODE) String caseType,
                  @RequestParam(required = false) @Pattern(regexp = CaseDtos.ID) String territoryId,
                  @RequestParam(defaultValue = "false") boolean assignedToMe,
                  @RequestParam(required = false) Instant createdFrom,
                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
                  @RequestParam(required = false) String cursor) {
        var page = queries.list(new CaseQueries.ListQuery(status, caseType, optionalId(territoryId), assignedToMe, createdFrom, limit, cursor));
        return new CasePage(page.items().stream().map(this::view).toList(), page.nextCursor());
    }

    @PostMapping("/cases/{caseId}/transitions")
    ResponseEntity<CaseView> transition(@PathVariable String caseId, @RequestHeader("If-Match") String ifMatch,
                                        @Valid @RequestBody TransitionRequest body) {
        return withEtag(commands.transition(caseIdOrNotFound(caseId), version(ifMatch), body.toStatus(), body.note()));
    }

    @PutMapping("/cases/{caseId}/assignee")
    ResponseEntity<CaseView> assign(@PathVariable String caseId, @RequestHeader("If-Match") String ifMatch,
                                    @Valid @RequestBody AssignRequest body) {
        return withEtag(commands.assign(caseIdOrNotFound(caseId), version(ifMatch), Long.parseLong(body.assigneeUserId())));
    }

    @GetMapping("/cases/{caseId}/activity")
    List<ActivityView> activity(@PathVariable String caseId) {
        return queries.activity(caseIdOrNotFound(caseId)).stream().map(ActivityView::of).toList();
    }

    private ResponseEntity<CaseView> withEtag(CaseRecord record) {
        var view = view(record);
        return ResponseEntity.status(HttpStatus.OK).eTag(etag(view.version())).body(view);
    }

    private CaseView view(CaseRecord record) {
        return CaseView.of(record, CurrentPrincipal.require().piiClearanceFor("case:read", clock.instant()));
    }
}
