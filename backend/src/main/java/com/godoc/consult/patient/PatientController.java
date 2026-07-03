package com.godoc.consult.patient;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo-only endpoint: exposes the seeded patients so the UI can "act as" one of them.
 * A real system would never list patients like this — the current patient would come
 * from authentication. Kept so the auth stub (X-Patient-Id header) is usable end to end.
 */
@RestController
@RequestMapping("/api/v1/patients")
public class PatientController {

    private final PatientRepository patients;

    public PatientController(PatientRepository patients) {
        this.patients = patients;
    }

    public record PatientDto(UUID id, String name) {
    }

    @GetMapping
    public List<PatientDto> list() {
        return patients.findAll(Sort.by("name")).stream()
                .map(p -> new PatientDto(p.getId(), p.getName()))
                .toList();
    }
}
