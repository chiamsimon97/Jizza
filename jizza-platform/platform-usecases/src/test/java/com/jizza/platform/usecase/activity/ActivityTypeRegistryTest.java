package com.jizza.platform.usecase.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.audit.catalog.ActivityCategory;
import com.jizza.engine.api.audit.catalog.ActivityCategory.ActivityGroup;
import com.jizza.engine.api.audit.catalog.ActivityLogCatalog;
import com.jizza.engine.api.audit.catalog.ActivityLogType;
import com.jizza.engine.api.audit.catalog.ActivityLogType.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How an action finds its section, sub-group and label, and how a module adds its own. */
class ActivityTypeRegistryTest {

  private final ActivityTypeRegistry core =
      new ActivityTypeRegistry(List.of(new CoreActivityCatalog()));

  @Test
  void anExactTypeWinsOverItsFamily() {
    var c = core.classify("auth.mfa.disabled");

    assertThat(c.category()).isEqualTo("security");
    assertThat(c.group()).isEqualTo("mfa");
    assertThat(c.label()).isEqualTo("Two-step verification turned off");
    assertThat(c.severity()).isEqualTo(Severity.WARNING);
  }

  @Test
  void theLongestFamilyWinsAndAMissingLabelIsDerived() {
    var c = core.classify("agent.run.something_new");

    assertThat(c.group()).isEqualTo("runs");
    assertThat(c.label()).isEqualTo("Agent run something new");
  }

  @Test
  void anUndescribedActionIsFiledByItsCategoryPrefix() {
    var c = core.classify("service.brand_new");

    assertThat(c.category()).isEqualTo("tools");
    assertThat(c.group()).isEqualTo("services");
  }

  @Test
  void anUnknownAreaFallsIntoOther() {
    var c = core.classify("billing.invoice_paid");

    assertThat(c.category()).isEqualTo(ActivityTypeRegistry.OTHER);
    assertThat(c.label()).isEqualTo("Billing invoice paid");
  }

  @Test
  void otherIsAlwaysTheLastCategory() {
    assertThat(core.categories().get(core.categories().size() - 1).key())
        .isEqualTo(ActivityTypeRegistry.OTHER);
  }

  @Test
  void aModuleCanAddACategoryAndExtendAnExistingOneWithoutTouchingTheCore() {
    ActivityLogCatalog billing = new ActivityLogCatalog() {
      @Override
      public List<ActivityCategory> categories() {
        return List.of(
            new ActivityCategory("billing", "Billing", "CreditCard", 15, List.of("billing."),
                List.of(new ActivityGroup("invoices", "Invoices"))),
            new ActivityCategory("security", "ignored", "ignored", 0, List.of("sso."),
                List.of(new ActivityGroup("sso", "Single sign-on"))));
      }

      @Override
      public List<ActivityLogType> types() {
        return List.of(
            ActivityLogType.of("billing.invoice_paid", "billing", "invoices", "Invoice paid"),
            ActivityLogType.of("sso.*", "security", "sso", null, Severity.NOTICE));
      }
    };
    var registry = new ActivityTypeRegistry(List.of(new CoreActivityCatalog(), billing));

    assertThat(registry.classify("billing.invoice_paid").label()).isEqualTo("Invoice paid");
    assertThat(registry.classify("sso.linked").group()).isEqualTo("sso");
    var security = registry.category("security").orElseThrow();
    assertThat(security.label()).isEqualTo("Sign-in & security");
    assertThat(security.groups()).extracting(ActivityGroup::key).contains("sign_in", "sso");
    assertThat(registry.categories()).extracting(ActivityCategory::key)
        .startsWith("security", "billing");
  }

  @Test
  void aTypeThatNamesAMissingGroupLandsInOther() {
    var registry = new ActivityTypeRegistry(List.of(new CoreActivityCatalog(),
        () -> List.of(ActivityLogType.of("run.teleported", "workflows", "nope", "Teleported"))));

    assertThat(registry.classify("run.teleported").group()).isEqualTo(ActivityTypeRegistry.OTHER);
  }
}
