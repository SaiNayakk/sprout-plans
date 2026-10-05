package app.sprout.plans.domain;

import org.springframework.http.HttpStatus;

/** The stable error codes of the plans contract. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Invalid request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Sign in first"),
    NO_ACCOUNT(HttpStatus.NOT_FOUND, "No Sprout account"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "No such plan"),
    UNKNOWN_INSTRUMENT(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown instrument"),
    TOO_MANY_PLANS(HttpStatus.UNPROCESSABLE_ENTITY, "Too many plans"),
    PLAN_STATE(HttpStatus.CONFLICT, "Not in that state"),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
