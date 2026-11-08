package com.jizza.engine.notifications.bus;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.NotificationEntity;
import org.junit.jupiter.api.Test;

class ApprovalValuesTest {

  private static NotificationEntity asking(String form) {
    NotificationEntity entity = new NotificationEntity();
    entity.setContext(Json.read("{\"node\":\"step\",\"form\":" + form + "}"));
    return entity;
  }

  @Test
  void approvingNeedsEachMissingValueFilledIn() {
    NotificationEntity entity = asking("""
        {"fields":[{"key":"fields.1.value","label":"Phone"}],"missing":["fields.1.value"]}
        """);

    assertThatThrownBy(() -> DefaultNotificationBus.requireFilledIn(entity, null))
        .isInstanceOf(ValidationException.class).hasMessageContaining("Fill in Phone");
    assertThatThrownBy(() -> DefaultNotificationBus.requireFilledIn(entity,
        Json.obj().put("fields.1.value", " ")))
        .isInstanceOf(ValidationException.class);
    assertThatCode(() -> DefaultNotificationBus.requireFilledIn(entity,
        Json.obj().put("fields.1.value", "98450 00000"))).doesNotThrowAnyException();
  }

  @Test
  void aCardWithNothingMissingApprovesAsBefore() {
    assertThatCode(() -> DefaultNotificationBus.requireFilledIn(asking("{\"missing\":[]}"), null))
        .doesNotThrowAnyException();
    assertThatCode(() -> DefaultNotificationBus.requireFilledIn(new NotificationEntity(), null))
        .doesNotThrowAnyException();
  }
}
