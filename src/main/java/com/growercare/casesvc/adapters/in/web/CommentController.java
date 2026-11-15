package com.growercare.casesvc.adapters.in.web;

import static com.growercare.casesvc.adapters.in.web.CaseDtos.caseIdOrNotFound;
import static com.growercare.casesvc.adapters.in.web.CaseDtos.optionalId;

import com.growercare.casesvc.adapters.in.web.CaseDtos.CommentPage;
import com.growercare.casesvc.adapters.in.web.CaseDtos.CommentView;
import com.growercare.casesvc.adapters.in.web.CaseDtos.PostCommentRequest;
import com.growercare.casesvc.adapters.in.web.CaseDtos.ShareView;
import com.growercare.casesvc.application.CommentsAndSharing;
import com.growercare.casesvc.domain.CommentVisibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SPEC-CASE-03 §3: comments and partner shares. */
@RestController
@Validated
class CommentController {

    private final CommentsAndSharing service;

    CommentController(CommentsAndSharing service) {
        this.service = service;
    }

    @PostMapping("/cases/{caseId}/comments")
    ResponseEntity<CommentView> post(@PathVariable String caseId, @Valid @RequestBody PostCommentRequest body) {
        var result = service.post(caseIdOrNotFound(caseId), new CommentsAndSharing.PostComment(
                CaseDtos.uuid(body.clientMsgId(), "clientMsgId"),
                CommentVisibility.valueOf(body.visibility()),
                body.body(),
                optionalId(body.replyToCommentId())));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(CommentView.of(result.comment()));
    }

    @GetMapping("/cases/{caseId}/comments")
    CommentPage comments(@PathVariable String caseId,
                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
                         @RequestParam(required = false) String cursor) {
        var page = service.comments(caseIdOrNotFound(caseId), limit, cursor);
        return new CommentPage(page.items().stream().map(CommentView::of).toList(), page.nextCursor());
    }

    @PutMapping("/cases/{caseId}/shares/{partnerOrgId}")
    ShareView share(@PathVariable String caseId, @PathVariable @Pattern(regexp = CaseDtos.ID) String partnerOrgId) {
        return ShareView.of(service.share(caseIdOrNotFound(caseId), Long.parseLong(partnerOrgId)));
    }

    @DeleteMapping("/cases/{caseId}/shares/{partnerOrgId}")
    ResponseEntity<Void> unshare(@PathVariable String caseId, @PathVariable @Pattern(regexp = CaseDtos.ID) String partnerOrgId) {
        service.unshare(caseIdOrNotFound(caseId), Long.parseLong(partnerOrgId));
        return ResponseEntity.noContent().build();
    }
}
