package com.jizza.platform.usecase.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.dto.UsageEntryDto;
import com.jizza.engine.api.llm.UsageStore;
import com.jizza.platform.usecase.usage.UsageExplorer.Dimension;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Usage over a range, grouped two levels deep and sliced into a timeline. */
class UsageExploreTest {

  private final UUID userId = UUID.randomUUID();
  private UsageUseCase useCase;

  private static UsageEntryDto entry(String model, String purpose, int in, int out, String cost,
                                     String at) {
    return new UsageEntryDto(UUID.randomUUID(), model, purpose, in, out, new BigDecimal(cost),
        Instant.parse(at));
  }

  @BeforeEach
  void setUp() {
    UsageStore store = mock(UsageStore.class);
    when(store.between(any(), any(), any(), anyInt())).thenReturn(List.of(
        entry("opus", "agent:researcher", 100, 50, "0.30", "2026-10-22T11:00:00Z"),
        entry("haiku", "jizza:routing", 10, 5, "0.01", "2026-10-22T10:00:00Z"),
        entry("opus", "agent:writer", 200, 80, "0.50", "2026-10-21T23:30:00Z")));
    useCase = new UsageUseCase(store);
  }

  private UsageExplorer.Query query(Dimension by, Dimension then, Set<String> areas, ZoneId zone) {
    return new UsageExplorer.Query(Instant.parse("2026-10-21T12:00:00Z"),
        Instant.parse("2026-10-22T12:00:00Z"), by, then, Set.of(), areas, 50, zone);
  }

  @Test
  void groupsByModelThenAreaWithTheMostExpensiveFirst() {
    var page = useCase.explore(userId, query(Dimension.MODEL, Dimension.AREA, Set.of(),
        ZoneOffset.UTC));

    assertThat(page.totals().calls()).isEqualTo(3);
    assertThat(page.totals().costUnits()).isEqualByComparingTo("0.81");
    assertThat(page.groups()).extracting(UsageExplorer.Group::key).containsExactly("opus", "haiku");
    var opus = page.groups().get(0);
    assertThat(opus.totals().inputTokens()).isEqualTo(300);
    assertThat(opus.children()).singleElement().satisfies(g -> {
      assertThat(g.key()).isEqualTo("agent");
      assertThat(g.label()).isEqualTo("Agents");
    });
    assertThat(page.areas()).containsExactly("agent", "jizza");
    assertThat(page.bucketUnit()).isEqualTo("hour");
  }

  @Test
  void areaFilterAndDaysFollowTheViewersZone() {
    var page = useCase.explore(userId, query(Dimension.DAY, null, Set.of("agent"),
        ZoneId.of("Asia/Singapore")));

    assertThat(page.totals().calls()).isEqualTo(2);
    // 23:30 UTC on the 21st is the 22nd in Singapore, so both land on one day.
    assertThat(page.groups()).singleElement().extracting(UsageExplorer.Group::key)
        .isEqualTo("2026-10-22");
  }
}
