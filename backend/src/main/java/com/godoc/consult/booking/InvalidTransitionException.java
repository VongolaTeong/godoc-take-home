package com.godoc.consult.booking;

public class InvalidTransitionException extends RuntimeException {

    public InvalidTransitionException(String action, BookingStatus from) {
        super("Cannot " + action + " a booking in status " + from);
    }
}
