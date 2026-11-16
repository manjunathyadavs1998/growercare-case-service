package com.growercare.casesvc.adapters.in.web;

import static com.growercare.casesvc.adapters.in.web.CaseDtos.caseIdOrNotFound;

import com.growercare.casesvc.adapters.in.web.CaseDtos.AttachmentPage;
import com.growercare.casesvc.adapters.in.web.CaseDtos.AttachmentUploadView;
import com.growercare.casesvc.adapters.in.web.CaseDtos.AttachmentView;
import com.growercare.casesvc.adapters.in.web.CaseDtos.DownloadView;
import com.growercare.casesvc.adapters.in.web.CaseDtos.PresignedView;
import com.growercare.casesvc.adapters.in.web.CaseDtos.RequestAttachmentRequest;
import com.growercare.casesvc.application.Attachments;
import com.growercare.common.error.NotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** SPEC-CASE-05 §3: evidence and comment attachments (presigned upload, scan, download). */
@RestController
class AttachmentController {

    private final Attachments service;

    AttachmentController(Attachments service) {
        this.service = service;
    }

    @PostMapping("/cases/{caseId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    AttachmentUploadView request(@PathVariable String caseId, @Valid @RequestBody RequestAttachmentRequest body) {
        var upload = service.request(caseIdOrNotFound(caseId),
                new Attachments.RequestUpload(body.kind(), body.contentType(), body.sizeBytes(), body.sha256()));
        return new AttachmentUploadView(CaseDtos.id(upload.attachment().attachmentId()), upload.attachment().status().name(),
                PresignedView.of(upload.request()));
    }

    @PostMapping("/cases/{caseId}/attachments/{attachmentId}/complete")
    AttachmentView complete(@PathVariable String caseId, @PathVariable String attachmentId) {
        return AttachmentView.of(service.complete(caseIdOrNotFound(caseId), attachmentIdOrNotFound(attachmentId)));
    }

    @GetMapping("/cases/{caseId}/attachments")
    AttachmentPage list(@PathVariable String caseId) {
        return new AttachmentPage(service.list(caseIdOrNotFound(caseId)).stream().map(AttachmentView::of).toList());
    }

    @GetMapping("/cases/{caseId}/attachments/{attachmentId}/download")
    DownloadView download(@PathVariable String caseId, @PathVariable String attachmentId) {
        var presigned = service.download(caseIdOrNotFound(caseId), attachmentIdOrNotFound(attachmentId));
        return new DownloadView(presigned.url().toString(), presigned.expiresAt());
    }

    private static long attachmentIdOrNotFound(String raw) {
        if (raw == null || !raw.matches(CaseDtos.ID)) {
            throw new NotFoundException("Attachment");
        }
        return Long.parseLong(raw);
    }
}
