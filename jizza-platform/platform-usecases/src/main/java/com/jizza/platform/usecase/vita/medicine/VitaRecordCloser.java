package com.jizza.platform.usecase.vita.medicine;

import com.jizza.platform.usecase.finance.profile.PersonStatusListener;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * When a person is archived or marked deceased (spec 5.13, Fixture J), their future un-acted dose
 * rows are removed and their waiting reminders cancelled; the jobs skip a closed record from then
 * on, so nothing is sent again. Reopening makes rows again at the next job run.
 */
@Component
public class VitaRecordCloser implements PersonStatusListener {

  private final VitaMedicineUseCase medicines;

  public VitaRecordCloser(VitaMedicineUseCase medicines) {
    this.medicines = medicines;
  }

  @Override
  public void statusChanged(UUID owner, UUID personId, String status) {
    if (!"active".equals(status)) medicines.closeRecord(owner, personId, Instant.now());
  }
}
