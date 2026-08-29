package com.jizza.engine.connectors.tools.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.dto.GeneratedToolDto;
import com.jizza.engine.api.error.ValidationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class OpenApiToolGeneratorTest {

  private final UUID connectionId = UUID.randomUUID();
  private OpenApiToolGenerator generator;
  private OpenApiToolGenerator.Parsed parsed;

  @BeforeEach
  void setUp() throws IOException {
    generator = new OpenApiToolGenerator();
    byte[] document = new ClassPathResource("petstore-openapi.json").getContentAsByteArray();
    parsed = generator.parse(connectionId, document);
  }

  private GeneratedToolDto tool(String name) {
    return parsed.tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
  }

  @Test
  void generatesOneToolPerOperation() {
    assertThat(parsed.tools()).extracting(GeneratedToolDto::name)
        .containsExactlyInAnyOrder("listpets", "createpet", "showpetbyid", "delete_pets_petid");
  }

  @Test
  void takesTheServerUrlFromTheDocument() {
    assertThat(parsed.serverUrl()).isEqualTo("https://api.petstore.test/v1");
  }

  @Test
  void carriesMethodPathAndSummaryOntoTheTool() {
    GeneratedToolDto show = tool("showpetbyid");
    assertThat(show.httpMethod()).isEqualTo("GET");
    assertThat(show.pathTemplate()).isEqualTo("/pets/{petId}");
    assertThat(show.description()).isEqualTo("Fetch one pet");
    assertThat(show.connectionId()).isEqualTo(connectionId);
  }

  @Test
  void marksOnlyStateChangingMethodsAsSideEffecting() {
    assertThat(tool("listpets").sideEffecting()).isFalse();
    assertThat(tool("showpetbyid").sideEffecting()).isFalse();
    assertThat(tool("createpet").sideEffecting()).isTrue();
    assertThat(tool("delete_pets_petid").sideEffecting()).isTrue();
  }

  @Test
  void buildsAnInputSchemaFromParameters() {
    var schema = tool("showpetbyid").inputSchema();
    assertThat(schema.path("type").asText()).isEqualTo("object");
    assertThat(schema.path("properties").path("petId").path("type").asText()).isEqualTo("string");
    assertThat(schema.path("required").findValuesAsText("")).isEmpty();
    assertThat(schema.path("required").toString()).contains("petId");
    assertThat(tool("showpetbyid").paramMapping().path("path").toString()).contains("petId");
  }

  @Test
  void buildsAnInputSchemaFromTheRequestBody() {
    var create = tool("createpet");
    var body = create.inputSchema().path("properties").path("body");
    assertThat(body.path("properties").path("name").path("type").asText()).isEqualTo("string");
    assertThat(create.paramMapping().path("bodyProperty").asText()).isEqualTo("body");
    assertThat(create.inputSchema().path("required").toString()).contains("body");
  }

  @Test
  void neverAsksTheModelForACredential() {
    // The document declares an Authorization header parameter; auth is injected server-side, so it
    // must not appear as something the model is expected to fill in.
    var schema = tool("listpets").inputSchema();
    assertThat(schema.path("properties").has("Authorization")).isFalse();
    assertThat(schema.path("properties").has("limit")).isTrue();
    assertThat(tool("listpets").paramMapping().path("header").toString())
        .doesNotContain("Authorization");
  }

  @Test
  void fallsBackToMethodAndPathWhenThereIsNoOperationId() {
    assertThat(tool("delete_pets_petid").description()).isEqualTo("Remove a pet");
  }

  @Test
  void namesAreUniqueEvenWhenTheDocumentRepeatsAnOperationId() {
    var used = new java.util.LinkedHashSet<String>();
    assertThat(generator.uniqueName("listPets", "GET", "/pets", used)).isEqualTo("listpets");
    assertThat(generator.uniqueName("listPets", "GET", "/pets", used)).isEqualTo("listpets_2");
    assertThat(generator.uniqueName("listPets", "GET", "/pets", used)).isEqualTo("listpets_3");
  }

  @Test
  void rejectsSomethingThatIsNotASpecification() {
    assertThatThrownBy(() -> generator.parse(connectionId,
        "this is just prose about an API".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("could not be read as an OpenAPI specification");
  }

  @Test
  void rejectsASpecificationWithNoOperations() {
    String empty = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"x\",\"version\":\"1\"},\"paths\":{}}";
    assertThatThrownBy(() -> generator.parse(connectionId, empty.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(ValidationException.class);
  }
}
