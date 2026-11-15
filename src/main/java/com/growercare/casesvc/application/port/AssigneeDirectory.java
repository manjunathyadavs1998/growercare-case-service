package com.growercare.casesvc.application.port;

import com.growercare.security.GcPrincipal;
import java.util.Optional;

/** Looks up a potential assignee by IAM user id (decision D2: IAM {@code /internal/users/by-id/{id}/principal}). */
@FunctionalInterface
public interface AssigneeDirectory {

    Optional<GcPrincipal> findByUserId(long userId);
}
