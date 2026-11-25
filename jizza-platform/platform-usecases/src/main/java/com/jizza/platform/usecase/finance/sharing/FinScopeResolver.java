package com.jizza.platform.usecase.finance.sharing;

import com.jizza.engine.api.error.NotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Whose finance rows a caller may reach. Every finance read and write asks this first.
 *
 * <p>Phase 0: only the caller's own rows. Phase 5 extends it with what Family members shared, row
 * by row; until then another owner's data reads as missing, exactly like a row that does not exist.
 */
@Service
public class FinScopeResolver {

  /** The caller and the owner of the rows being reached; equal until sharing exists. */
  public record FinScope(UUID callerUserId, UUID ownerUserId, boolean canEdit) {
    public boolean own() { return callerUserId.equals(ownerUserId); }
  }

  /** The caller's own data. */
  public FinScope own(UUID callerUserId) {
    if (callerUserId == null) throw new NotFoundException("Finance data", "of nobody");
    return new FinScope(callerUserId, callerUserId, true);
  }

  /** Another owner's data, for reading; refused (as not found) unless it is the caller's own. */
  public FinScope forOwner(UUID callerUserId, UUID ownerUserId) {
    if (callerUserId == null || !callerUserId.equals(ownerUserId)) {
      throw new NotFoundException("Finance data", ownerUserId);
    }
    return own(callerUserId);
  }
}
