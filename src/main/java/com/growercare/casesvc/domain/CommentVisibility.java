package com.growercare.casesvc.domain;

import com.growercare.security.UserType;
import java.util.EnumSet;
import java.util.Set;

/** Comment audiences (doc 05 §3, SPEC-CASE-03 R51/R56). */
public enum CommentVisibility {
    /** Grower-visible conversation. */
    PUBLIC("comment:post-public"),
    /** Staff-only notes. */
    INTERNAL("comment:post-internal"),
    /** Between staff and the channel partner. */
    PARTNER("comment:post-partner");

    private final String postPermission;

    CommentVisibility(String postPermission) {
        this.postPermission = postPermission;
    }

    public String postPermission() {
        return postPermission;
    }

    public static Set<CommentVisibility> readableBy(UserType type) {
        return switch (type) {
            case GROWER -> EnumSet.of(PUBLIC);
            case PARTNER -> EnumSet.of(PUBLIC, PARTNER);
            case STAFF -> EnumSet.allOf(CommentVisibility.class);
        };
    }
}
