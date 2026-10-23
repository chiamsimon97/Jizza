package com.jizza.platform.usecase.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.dto.NotificationDto;
import com.jizza.engine.api.enums.EscalationState;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.NotificationPriority;
import com.jizza.engine.api.enums.NotificationStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.notification.Answer;
import com.jizza.engine.api.notification.NotificationBus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Ownership: a notification belonging to somebody else is reported missing, not forbidden. */
class NotificationUseCaseTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID notificationId = UUID.randomUUID();
  private NotificationBus bus;
  private NotificationUseCase useCase;

  private NotificationDto dto() {
    return new NotificationDto(notificationId, userId, null, "Jizza", NotificationKind.INFO,
        "hello", null, null, null, null, NotificationStatus.UNREAD, null, null,
        EscalationState.NONE, Instant.now(), null);
  }

  @BeforeEach
  void setUp() {
    bus = mock(NotificationBus.class);
    when(bus.get(notificationId)).thenReturn(dto());
    useCase = new NotificationUseCase(bus);
  }

  @Test
  void ownerCanReadMarkReadAndAnswer() {
    assertThat(useCase.get(userId, notificationId)).isNotNull();

    useCase.markRead(userId, notificationId);
    verify(bus).markRead(notificationId);

    var answer = Answer.approve();
    useCase.answer(userId, notificationId, answer);
    verify(bus).answer(notificationId, answer);
  }

  @Test
  void anotherUsersNotificationIsReportedMissingAndNothingRuns() {
    UUID somebodyElse = UUID.randomUUID();

    assertThatThrownBy(() -> useCase.get(somebodyElse, notificationId))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> useCase.markRead(somebodyElse, notificationId))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> useCase.answer(somebodyElse, notificationId, Answer.approve()))
        .isInstanceOf(NotFoundException.class);

    verify(bus, never()).markRead(notificationId);
    verify(bus, never()).answer(notificationId, Answer.approve());
  }

  @Test
  void listDelegatesStraightThrough() {
    useCase.list(userId, true);
    verify(bus).list(userId, true);
  }

  @Test
  void anotherUsersItemCannotBeFiledOrSnoozed() {
    UUID somebodyElse = UUID.randomUUID();

    assertThatThrownBy(() -> useCase.archive(somebodyElse, notificationId, true))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> useCase.snooze(somebodyElse, notificationId, Instant.now()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> useCase.markUnread(somebodyElse, notificationId))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void theSummaryCountsWhatIsWaitingLeavingOutSnoozedAndFiledItems() {
    Instant now = Instant.now();
    var open = item(NotificationKind.PERMISSION_REQUEST, NotificationStatus.UNREAD,
        NotificationPriority.URGENT, null, null);
    var openRead = item(NotificationKind.INSTRUCTION_REQUIRED, NotificationStatus.READ,
        NotificationPriority.NORMAL, null, null);
    var snoozed = item(NotificationKind.PERMISSION_REQUEST, NotificationStatus.UNREAD,
        NotificationPriority.URGENT, null, now.plusSeconds(600));
    var answered = item(NotificationKind.PERMISSION_REQUEST, NotificationStatus.APPROVED,
        NotificationPriority.NORMAL, null, null);
    var update = item(NotificationKind.INFO, NotificationStatus.UNREAD,
        NotificationPriority.NORMAL, null, null);
    var filed = item(NotificationKind.INFO, NotificationStatus.UNREAD,
        NotificationPriority.NORMAL, now, null);
    when(bus.list(userId, false)).thenReturn(
        java.util.List.of(open, openRead, snoozed, answered, update, filed));

    var summary = useCase.summary(userId);

    assertThat(summary.needsYou()).isEqualTo(2);
    assertThat(summary.urgent()).isEqualTo(1);
    assertThat(summary.unread()).isEqualTo(2);
  }

  private NotificationDto item(NotificationKind kind, NotificationStatus status,
                               NotificationPriority priority, Instant archivedAt,
                               Instant snoozedUntil) {
    return new NotificationDto(UUID.randomUUID(), userId, null, "Jizza", kind, "x", null, null,
        null, null, status, null, null, EscalationState.NONE, Instant.now(), null, priority,
        archivedAt, snoozedUntil, null);
  }
}
