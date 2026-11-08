package com.jizza.engine.workflow.forms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FormFieldsTest {

  private static JsonNode json(String s) {
    return Json.read(s.replace('\'', '"'));
  }

  private static final JsonNode FIELDS = json("""
      [ {'id':'a','type':'email','name':'email','label':'Email','required':true},
        {'id':'b','type':'number','name':'qty','label':'Quantity','min':1,'max':10},
        {'id':'c','type':'dropdown','name':'size','label':'Size','choices':['S','M']},
        {'id':'d','type':'section','label':'More'},
        {'id':'l','type':'step_layout','label':'Tell me','children':[
          {'id':'e','type':'multi_line','name':'message','label':'Message','pending':true,
           'binding':{'workflowId':'w','nodeId':'n1','path':['message'],'mode':'template'}},
          {'id':'f','type':'single_line','name':'title','label':'Title',
           'binding':{'workflowId':'w','nodeId':'n1','path':['title'],'mode':'template'}}]} ]
      """);

  @Test
  void acceptsAValidDefinition() {
    assertThat(FormFields.checkDefinition(FIELDS)).isSameAs(FIELDS);
  }

  @Test
  void refusesClashingLinkNames() {
    JsonNode fields = json("[{'id':'a','type':'email','name':'x','label':'A'},{'id':'b','type':'phone','name':'x','label':'B'}]");
    assertThatThrownBy(() -> FormFields.checkDefinition(fields)).isInstanceOf(ValidationException.class)
        .hasMessageContaining("'x'");
  }

  @Test
  void refusesABindingToTheStepsRetry() {
    JsonNode fields = json("[{'id':'a','type':'number','name':'x','label':'A','binding':{'workflowId':'w','nodeId':'n','path':['retry','maxAttempts'],'mode':'template'}}]");
    assertThatThrownBy(() -> FormFields.checkDefinition(fields)).isInstanceOf(ValidationException.class);
  }

  @Test
  void checksAndTypesAnswers() {
    var out = FormFields.checkSubmission(FIELDS, json("{'email':'a@b.co','qty':'3','size':'M','title':'Hi','message':'ignored','extra':1}"), Map.of());
    assertThat(out.path("email").asText()).isEqualTo("a@b.co");
    assertThat(out.path("qty").isNumber()).isTrue();
    assertThat(out.path("size").asText()).isEqualTo("M");
    assertThat(out.path("title").asText()).isEqualTo("Hi");
    assertThat(out.has("message")).as("pending fields are not on the form").isFalse();
    assertThat(out.has("extra")).isFalse();
  }

  @Test
  void reportsEveryProblem() {
    assertThatThrownBy(() -> FormFields.checkSubmission(FIELDS, json("{'qty':'30','size':'XL'}"), Map.of()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Email is required")
        .hasMessageContaining("at most 10")
        .hasMessageContaining("pick one");
  }

  @Test
  void publicFieldsHideBindingsAndPendingFields() {
    JsonNode shown = FormFields.publicFields(FIELDS, Map.of());
    String text = Json.write(shown);
    assertThat(text).doesNotContain("binding").doesNotContain("\"message\"");
    assertThat(shown.get(4).path("children")).hasSize(1);
  }

  @Test
  void refusesSvgImages() {
    JsonNode fields = json("[{'id':'a','type':'image','name':'pic','label':'Picture'}]");
    JsonNode data = json("{'pic':{'name':'x.svg','dataUrl':'data:image/svg+xml;base64,PHN2Zz4='}}");
    assertThatThrownBy(() -> FormFields.checkSubmission(fields, data, Map.of())).isInstanceOf(ValidationException.class);
    JsonNode png = json("{'pic':{'name':'x.png','dataUrl':'data:image/png;base64,iVBORw0KGgo='}}");
    assertThat(FormFields.checkSubmission(fields, png, Map.of()).path("pic").path("type").asText()).isEqualTo("image/png");
  }

  @Test
  void lookupAnswersMustBeAmongTheOptions() {
    JsonNode fields = json("[{'id':'a','type':'lookup','name':'customer','label':'Customer'}]");
    assertThat(FormFields.checkSubmission(fields, json("{'customer':'Acme'}"), Map.of("a", List.of("Acme"))).path("customer").asText()).isEqualTo("Acme");
    assertThatThrownBy(() -> FormFields.checkSubmission(fields, json("{'customer':'Other'}"), Map.of("a", List.of("Acme"))))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void overridesPointBoundStepsAtTheInput() {
    UUID wf = UUID.randomUUID();
    JsonNode fields = json(("[{'id':'a','type':'single_line','name':'subject','label':'S','binding':{'workflowId':'W','nodeId':'n1','path':['arguments','title'],'mode':'template'}},"
        + "{'id':'b','type':'multi_line','name':'brief','label':'B','binding':{'workflowId':'W','nodeId':'n2','path':['instructionExpr'],'mode':'expression'}},"
        + "{'id':'c','type':'single_line','name':'later','label':'L','pending':true,'binding':{'workflowId':'W','nodeId':'n3','path':['x'],'mode':'template'}},"
        + "{'id':'d','type':'single_line','name':'email','label':'E','binding':{'workflowId':'W','nodeId':'start','path':[],'mode':'input'}}]").replace("W", wf.toString()));
    var overrides = DefaultForms.overridesFor(fields, wf);
    assertThat(overrides.path("n1").get(0).path("value").asText()).isEqualTo("{{ input.subject }}");
    assertThat(overrides.path("n2").get(0).path("value").asText()).isEqualTo("input.brief");
    assertThat(overrides.has("n3")).isFalse();
    assertThat(overrides.has("start")).isFalse();
    assertThat(DefaultForms.overridesFor(fields, UUID.randomUUID())).isEmpty();
  }
}
