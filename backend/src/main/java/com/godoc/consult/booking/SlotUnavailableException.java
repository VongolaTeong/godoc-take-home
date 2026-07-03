package com.godoc.consult.booking;

import java.util.UUID;

/** Raised when the partial unique index rejects a second active booking for a slot. */
public class SlotUnavailableException extends RuntimeException {

    public SlotUnavailableException(UUID slotId) {
        super("Slot " + slotId + " already has an active booking");
    }
}
