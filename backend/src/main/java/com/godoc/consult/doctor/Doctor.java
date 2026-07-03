package com.godoc.consult.doctor;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "doctor")
public class Doctor {

    @Id
    private UUID id;

    private String name;

    private String specialty;

    protected Doctor() {
    }

    public Doctor(UUID id, String name, String specialty) {
        this.id = id;
        this.name = name;
        this.specialty = specialty;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSpecialty() {
        return specialty;
    }
}
