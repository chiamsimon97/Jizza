package com.jizza.platform.usecase.vita.labs;

import com.jizza.engine.api.vita.VitaStore;
import org.springframework.stereotype.Service;

/**
 * The canonical lab tests, read once from the system reference table. The table changes only with a
 * migration, so the normaliser built from it is kept for the life of the server.
 */
@Service
public class VitaLabCatalog {

  private final VitaStore store;
  private volatile LabNormaliser normaliser;

  public VitaLabCatalog(VitaStore store) {
    this.store = store;
  }

  public LabNormaliser normaliser() {
    LabNormaliser n = normaliser;
    if (n == null) {
      n = new LabNormaliser(store.labTests());
      normaliser = n;
    }
    return n;
  }

  public boolean known(String code) {
    return normaliser().byCode(code).isPresent();
  }
}
