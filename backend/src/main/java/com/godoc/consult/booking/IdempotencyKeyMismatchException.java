package com.godoc.consult.booking;

public class IdempotencyKeyMismatchException extends RuntimeException {

    public IdempotencyKeyMismatchException() {
        super("This Idempotency-Key was already used for a different booking request; "
                + "use a fresh key for a new booking");
    }
}
