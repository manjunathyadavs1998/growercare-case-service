package com.growercare.casesvc.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Raise a case (SPEC-CASE-02 R20–R27). Country, territory and BU are deliberately absent: they are derived from
 * the grower and product on the server (decision D3).
 */
public record NewCaseCommand(
        long growerId,
        String caseType,
        String title,
        String description,
        Long farmId,
        Long productId,
        String batchNo,
        String buCode,
        String priority,
        UUID idempotencyKey) {

    public NewCaseCommand {
        Objects.requireNonNull(caseType, "caseType");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        priority = priority == null ? "NORMAL" : priority;
    }

    public NewCaseCommand withTitle(String newTitle) {
        return new NewCaseCommand(growerId, caseType, newTitle, description, farmId, productId, batchNo, buCode, priority, idempotencyKey);
    }

    public NewCaseCommand withIdempotencyKey(UUID key) {
        return new NewCaseCommand(growerId, caseType, title, description, farmId, productId, batchNo, buCode, priority, key);
    }
}
