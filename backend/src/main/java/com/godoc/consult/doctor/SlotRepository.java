package com.godoc.consult.doctor;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlotRepository extends JpaRepository<Slot, UUID> {
}
