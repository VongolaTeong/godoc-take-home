package com.godoc.consult.common;

import java.util.UUID;

/**
 * The X-Patient-Id header did not resolve to a known patient. This is the auth stand-in
 * for the take-home: in a real system the current patient would come from authentication,
 * and this would be an ordinary 401.
 */
public class UnknownPatientException extends RuntimeException {

    public UnknownPatientException(UUID patientId) {
        super("Unknown patient " + patientId);
    }
}
