package gov.nasa.jpl.aerie.e2e;

import com.microsoft.playwright.Playwright;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A set of tests focusing on testing gateway functionality for external sources.
 * These tests verify validation of External Source uploads.
 */
@Tag("external_events")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ExternalEventsTests {
  // Requests
  private Playwright playwright;
  private HasuraRequests hasura;

  @BeforeAll
  void beforeAll() {
    // Setup Requests
    playwright = Playwright.create();
    hasura = new HasuraRequests(playwright);
  }

  @AfterAll
  void afterAll() {
    hasura.close();
    playwright.close();
  }

  @BeforeEach
  void beforeEach() throws IOException {
    uploadExternalSourceEventTypes();
  }

  @AfterEach
  void afterEach() throws IOException {
    deleteExternalSourceEventTypes();
  }

  // need a method to upload external event and source types
  private void uploadExternalSourceEventTypes() throws IOException {
    final String event_types = """
        {
          "TestEventType": {
            "type": "object",
            "required": ["projectUser", "code"],
            "properties": {
              "projectUser": {
                "type": "string"
              },
              "code": {
                "type": "string"
              },
              "optional": {
                "type": "string"
              }
            }
          }
        }
        """;

    final String source_types = """
        {
          "TestSourceType": {
            "type": "object",
            "properties": {
              "version": {
                "type": "number"
              },
              "operator": {
                "type": "string"
              },
              "optional": {
                "type": "string"
              }
            },
            "required": ["version", "operator"]
          }
        }
        """;

    final JsonObject schema = Json.createObjectBuilder()
        .add("event_types", event_types)
        .add("source_types", source_types)
        .build();

    try (final var gateway = new GatewayRequests(playwright)) {
      gateway.uploadExternalSourceEventTypes(schema);
    }
  }

  private void deleteExternalSourceEventTypes() throws IOException {
    // delete events
    hasura.deleteEventsBySource("TestExternalSourceKey", "TestDerivationGroup");

    // delete source
    hasura.deleteExternalSource("TestExternalSourceKey", "TestDerivationGroup");

    // delete derivation groups
    hasura.deleteDerivationGroup("TestDerivationGroup"); // set automatically by gateway if not provided by us

    // delete types
    hasura.deleteExternalSourceType("TestSourceType");
    hasura.deleteExternalEventType("TestEventType");
  }


  // test that a source goes in including all the attributes
  @Test
  void correctSourceAndEventAttributes() throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      gateway.uploadExternalSource(Path.of("correct_source_and_event_attributes.json"), "TestDerivationGroup");
    }
  }

  // test that a source fails missing an attribute
  @Test
  void sourceMissingAttribute() throws IOException {
    final var gateway = new GatewayRequests(playwright);
    final RuntimeException ex = assertThrows(RuntimeException.class, () -> gateway.uploadExternalSource(
        Path.of("source_missing_attribute.json"), "TestDerivationGroup"));
    assertTrue(ex.getMessage().contains("should have required property 'operator'"));
  }

  // test that a source fails with an extra attribute
  @Test
  void sourceExtraAttribute() throws IOException {
    final var gateway = new GatewayRequests(playwright);
    final RuntimeException ex = assertThrows(RuntimeException.class, () -> gateway.uploadExternalSource(
        Path.of("source_extra_attribute.json"), "TestDerivationGroup"));
    assertTrue(ex.getMessage().contains("should NOT have additional properties"));
  }

  // test that a source fails with an attribute of the wrong type
  @Test
  void sourceWrongTypeAttribute() throws IOException {
    final var gateway = new GatewayRequests(playwright);
    final RuntimeException ex = assertThrows(RuntimeException.class, () -> gateway.uploadExternalSource(
        Path.of("source_wrong_type_attribute.json"), "TestDerivationGroup"));
    assertTrue(ex.getMessage().contains("should be number"));
  }

  // test that optional attributes (listed in schema, but not marked as required) are okay
  @Test
  void sourceOptionalAttribute() throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      gateway.uploadExternalSource(Path.of("source_optional_attribute.json"), "TestDerivationGroup");
    }
  }

  // test that an event fails missing an attribute
  @Test
  void eventMissingAttribute() throws IOException {
    final var gateway = new GatewayRequests(playwright);
    final RuntimeException ex = assertThrows(RuntimeException.class, () -> gateway.uploadExternalSource(
        Path.of("event_missing_attribute.json"), "TestDerivationGroup"));
    assertTrue(ex.getMessage().contains("should have required property 'code'"));
  }

  // test that an event fails with an extra attribute
  @Test
  void eventExtraAttribute() throws IOException {
    final var gateway = new GatewayRequests(playwright);
    final RuntimeException ex = assertThrows(RuntimeException.class, () -> gateway.uploadExternalSource(
        Path.of("event_extra_attribute.json"), "TestDerivationGroup"));
    assertTrue(ex.getMessage().contains("should NOT have additional properties"));
  }

  // test that an event fails with an attribute of the wrong type
  @Test
  void eventWrongTypeAttribute() throws IOException {
    final var gateway = new GatewayRequests(playwright);
    final RuntimeException ex = assertThrows(RuntimeException.class, () -> gateway.uploadExternalSource(
        Path.of("event_wrong_type_attribute.json"), "TestDerivationGroup"));
    assertTrue(ex.getMessage().contains("should be string"));
  }

  // test that optional attributes (listed in schema, but not marked as required) are okay
  @Test
  void eventOptionalAttribute() throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      gateway.uploadExternalSource(Path.of("event_optional_attribute.json"), "TestDerivationGroup");
    }
  }
}
