package com.godoc.consult.doctor;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/doctors")
public class DoctorController {

    private final DoctorRepository doctors;

    public DoctorController(DoctorRepository doctors) {
        this.doctors = doctors;
    }

    public record DoctorDto(UUID id, String name, String specialty) {
    }

    @GetMapping
    public List<DoctorDto> list() {
        return doctors.findAll(Sort.by("name")).stream()
                .map(d -> new DoctorDto(d.getId(), d.getName(), d.getSpecialty()))
                .toList();
    }
}
