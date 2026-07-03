package com.godoc.consult.booking;

import java.util.UUID;

public class SlotInPastException extends RuntimeException {

    public SlotInPastException(UUID slotId) {
        super("Slot " + slotId + " starts in the past and can no longer be booked");
    }
}
