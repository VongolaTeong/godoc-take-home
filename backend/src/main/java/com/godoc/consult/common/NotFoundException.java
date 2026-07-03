package com.godoc.consult.common;

import java.util.UUID;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String what, UUID id) {
        super(what + " " + id + " not found");
    }
}
