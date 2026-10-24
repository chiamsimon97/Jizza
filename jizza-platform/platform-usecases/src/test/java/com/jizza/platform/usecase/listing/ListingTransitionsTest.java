package com.jizza.platform.usecase.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.enums.ListingAction;
import com.jizza.engine.api.enums.ListingStatus;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.workspace.PublicListingStore.Profile;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every allowed and refused listing status change, with and without an approved snapshot. */
class ListingTransitionsTest {

  private static final ListingStatus REFUSED = null;

  /** Expected outcome of each action from each status, for an organization never approved. */
  private static Map<ListingAction, ListingStatus[]> neverListed() {
    // Columns: NONE, PENDING, APPROVED, REJECTED, SUSPENDED
    Map<ListingAction, ListingStatus[]> m = new EnumMap<>(ListingAction.class);
    m.put(ListingAction.SUBMITTED, row(ListingStatus.PENDING, REFUSED, ListingStatus.PENDING,
        ListingStatus.PENDING, REFUSED));
    m.put(ListingAction.WITHDRAWN, row(REFUSED, ListingStatus.NONE, REFUSED, REFUSED, REFUSED));
    m.put(ListingAction.UNLISTED, row(REFUSED, REFUSED, REFUSED, REFUSED, REFUSED));
    m.put(ListingAction.APPROVED, row(REFUSED, ListingStatus.APPROVED, REFUSED, REFUSED, REFUSED));
    m.put(ListingAction.REJECTED, row(REFUSED, ListingStatus.REJECTED, REFUSED, REFUSED, REFUSED));
    m.put(ListingAction.SUSPENDED, row(REFUSED, REFUSED, REFUSED, REFUSED, REFUSED));
    m.put(ListingAction.REINSTATED, row(REFUSED, REFUSED, REFUSED, REFUSED, ListingStatus.NONE));
    return m;
  }

  /** The same, for an organization that has an approved snapshot others can see. */
  private static Map<ListingAction, ListingStatus[]> listed() {
    Map<ListingAction, ListingStatus[]> m = new EnumMap<>(ListingAction.class);
    m.put(ListingAction.SUBMITTED, row(ListingStatus.PENDING, REFUSED, ListingStatus.PENDING,
        ListingStatus.PENDING, REFUSED));
    m.put(ListingAction.WITHDRAWN, row(REFUSED, ListingStatus.APPROVED, REFUSED, REFUSED, REFUSED));
    m.put(ListingAction.UNLISTED, row(REFUSED, ListingStatus.NONE, ListingStatus.NONE, REFUSED,
        REFUSED));
    m.put(ListingAction.APPROVED, row(REFUSED, ListingStatus.APPROVED, REFUSED, REFUSED, REFUSED));
    m.put(ListingAction.REJECTED, row(REFUSED, ListingStatus.APPROVED, REFUSED, REFUSED, REFUSED));
    m.put(ListingAction.SUSPENDED, row(REFUSED, ListingStatus.SUSPENDED, ListingStatus.SUSPENDED,
        REFUSED, REFUSED));
    m.put(ListingAction.REINSTATED, row(REFUSED, REFUSED, REFUSED, REFUSED,
        ListingStatus.APPROVED));
    return m;
  }

  private static ListingStatus[] row(ListingStatus... outcomes) {
    return outcomes;
  }

  @Test
  void everyTransitionWithoutAnApprovedSnapshot() {
    check(false, neverListed());
  }

  @Test
  void everyTransitionForAListedOrganization() {
    check(true, listed());
  }

  private static void check(boolean listed, Map<ListingAction, ListingStatus[]> table) {
    assertThat(table).hasSize(ListingAction.values().length);
    for (var entry : table.entrySet()) {
      ListingStatus[] expected = entry.getValue();
      for (ListingStatus from : ListingStatus.values()) {
        ListingStatus want = expected[from.ordinal()];
        String label = entry.getKey() + " from " + from + (listed ? " (listed)" : "");
        if (want == REFUSED) {
          assertThatThrownBy(() -> ListingTransitions.next(from, listed, entry.getKey()))
              .as(label).isInstanceOf(JizzaException.class)
              .satisfies(e -> assertThat(((JizzaException) e).status().value()).isEqualTo(409));
        } else {
          assertThat(ListingTransitions.next(from, listed, entry.getKey())).as(label)
              .isEqualTo(want);
        }
      }
    }
  }

  @Test
  void onlyAnApprovedSnapshotThatIsApprovedOrInReviewIsVisible() {
    for (ListingStatus s : ListingStatus.values()) {
      assertThat(ListingTransitions.visible(s, false)).isFalse();
      assertThat(ListingTransitions.visible(s, true))
          .isEqualTo(s == ListingStatus.APPROVED || s == ListingStatus.PENDING);
    }
  }

  @Test
  void theProfileIsTrimmedAndChecked() {
    Profile p = PublicListingUseCase.clean(new Profile("  Acme  ", null, "Technology",
        " https://acme.example/logo.png ", ""));
    assertThat(p).isEqualTo(new Profile("Acme", "", "technology",
        "https://acme.example/logo.png", null));

    assertThatThrownBy(() -> PublicListingUseCase.clean(new Profile("A", "", "other", null, null)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> PublicListingUseCase.clean(
        new Profile("Acme", "x".repeat(501), "other", null, null)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> PublicListingUseCase.clean(
        new Profile("Acme", "", "casino", null, null)))
        .isInstanceOf(ValidationException.class);
    for (String bad : new String[] {"javascript:alert(1)", "http://acme.example",
        "https://user:pw@acme.example", "not a link"}) {
      assertThatThrownBy(() -> PublicListingUseCase.clean(
          new Profile("Acme", "", "other", null, bad)))
          .as(bad).isInstanceOf(ValidationException.class);
    }
  }
}
