package gov.nasa.jpl.aerie.e2e;

import com.microsoft.playwright.Playwright;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A set of tests focusing on testing gateway functionality for external sources.
 * These tests verify validation of External Source uploads.
 */

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

  // need a method to create external event type
  void uploadExternalEventType( ) throws IOException {
    final JsonObject schema = Json.createObjectBuilder()
                                  .add("$schema", "http://json-schema.org/draft-07/schema")
                                  .add("title", "TestEventType")
                                  .add("description", "Schema for the attributes of the DSNContact External Event Type.")
                                  .add("type", "object")
                                  .add("additionalProperties", false)
                                  .add("properties", Json.createObjectBuilder()
                                                         .add("projectUser", Json.createObjectBuilder()
                                                                                 .add("type", "string")
                                                         )
                                                         .add("code", Json.createObjectBuilder()
                                                                          .add("type", "string")
                                                         )
                                  )
                                  .add("required", Json.createArrayBuilder()
                                      .add("projectUser")
                                      .add("code")
                                  )
                                  .build();

    try (final var gateway = new GatewayRequests(playwright)) {
      gateway.uploadExternalEventType("TestEventType", schema);
    }
  }

  // need a method to create external source type
  void uploadExternalSourceType() throws IOException {
    final String externalSourceTypeName = "TestSourceType";
    final JsonObject schema = Json.createObjectBuilder()
                                  .add("$schema", "http://json-schema.org/draft-07/schema")
                                  .add("title", "TestSourceType")
                                  .add("description", "Schema for the attributes of the DSN Contact Confirmed External Source Type.")
                                  .add("type", "object")
                                  .add("additionalProperties", false)
                                  .add("properties", Json.createObjectBuilder()
                                                         .add("version", Json.createObjectBuilder()
                                                                             .add("type", "number")
                                                         )
                                                         .add("operator", Json.createObjectBuilder()
                                                                            .add("type", "string")
                                                         )
                                  )
                                  .add("required", Json.createArrayBuilder()
                                                       .add("version")
                                                       .add("operator")
                                  )
                                  .build();

    try (final var gateway = new GatewayRequests(playwright)) {
      gateway.uploadExternalSourceType(externalSourceTypeName, schema);
    }
  }

  @BeforeEach
  void beforeEach() throws IOException {
    // upload types
    uploadExternalEventType();
    uploadExternalSourceType();
  }

  @AfterEach
  void afterEach() throws IOException {
    // delete events
    hasura.deleteEventsBySource("TestExternalSourceKey", "TestDerivationGroup");

    // delete source
    hasura.deleteExternalSource("TestExternalSourceKey", "TestDerivationGroup");

    // delete derivation groups
    hasura.deleteDerivationGroup("TestDerivationGroup");

    // delete types
    hasura.deleteExternalSourceType("TestSourceType");
    hasura.deleteExternalEventType("TestEventType");
  }

  // test that a source goes in including all the attributes
  @Test
  void correctSourceAndEventAttributes() throws IOException {
    final var externalEvents = Json.createArrayBuilder()
                                   .add(Json.createObjectBuilder()
                                            .add("attributes", Json.createObjectBuilder()
                                                                   .add("projectUser", "UserA")
                                                                   .add("code", "A")
                                            )
                                            .add("duration", "01:00:00")
                                            .add("event_type_name", "TestEventType")
                                            .add("key", "Event_01")
                                            .add("start_time", "2024-01-21T01:00:00+00:00")
                                   );

    final var externalSource = Json.createObjectBuilder()
                                   .add("source", Json.createObjectBuilder()
                                                      .add("attributes", Json.createObjectBuilder()
                                                                             .add("version", 1)
                                                                             .add("operator", "alpha")
                                                      )
                                                      .add("derivation_group_name", "TestDerivationGroup")
                                                      .add("period", Json.createObjectBuilder()
                                                                         .add("start_time", "2024-01-21T00:00:00+00:00")
                                                                         .add("end_time", "2024-01-28T00:00:00+00:00")
                                                      )
                                                      .add("key", "TestExternalSourceKey")
                                                      .add("source_type_name", "TestSourceType")
                                                      .add("valid_at", "2024-01-19T00:00:00+00:00")
                                   )
                                   .add("external_events", externalEvents)
                                   .build();

    try (final var gateway = new GatewayRequests(playwright)) {
      gateway.uploadExternalSource(externalSource);
    }
  }

  // test that a source fails missing an attribute
  @Test
  void sourceMissingAttribute() throws IOException {
    final var externalEvents = Json.createArrayBuilder()
                                   .add(Json.createObjectBuilder()
                                            .add("attributes", Json.createObjectBuilder()
                                                                   .add("projectUser", "UserA")
                                                                   .add("code", "A")
                                            )
                                            .add("duration", "01:00:00")
                                            .add("event_type_name", "TestEventType")
                                            .add("key", "Event_01")
                                            .add("start_time", "2024-01-21T01:00:00+00:00")
                                   );

    final var externalSource = Json.createObjectBuilder()
        .add("source", Json.createObjectBuilder()
                           .add("attributes", Json.createObjectBuilder()
                                                  .add("version", 1)
                                                  // missing: operator
                           )
                           .add("derivation_group_name", "TestDerivationGroup")
                           .add("period", Json.createObjectBuilder()
                                              .add("start_time", "2024-01-21T00:00:00+00:00")
                                              .add("end_time", "2024-01-28T00:00:00+00:00")
                           )
                           .add("key", "TestExternalSourceKey")
                           .add("source_type_name", "TestSourceType")
                           .add("valid_at", "2024-01-19T00:00:00+00:00")
        )
                                   .add("external_events", externalEvents)
                                   .build();

    final var gateway = new GatewayRequests(playwright);
    final IOException ex = assertThrows(IOException.class, () -> gateway.uploadExternalSource(externalSource));
    assertTrue(ex.getMessage().contains("Source's attributes are invalid"));
  }

  // test that a source fails with an extra attribute
  @Test
  void sourceExtraAttribute() throws IOException {
    final var externalEvents = Json.createArrayBuilder()
                                   .add(Json.createObjectBuilder()
                                            .add("attributes", Json.createObjectBuilder()
                                                                   .add("projectUser", "UserA")
                                                                   .add("code", "A")
                                            )
                                            .add("duration", "01:00:00")
                                            .add("event_type_name", "TestEventType")
                                            .add("key", "Event_01")
                                            .add("start_time", "2024-01-21T01:00:00+00:00")
                                   );

    final var externalSource = Json.createObjectBuilder()
        .add("source", Json.createObjectBuilder()
                           .add("attributes", Json.createObjectBuilder()
                                                  .add("version", 1)
                                                  .add("operator", "alpha")
                                                  .add("extra", "attribute") // extra
                           )
                           .add("derivation_group_name", "TestDerivationGroup")
                           .add("period", Json.createObjectBuilder()
                                              .add("start_time", "2024-01-21T00:00:00+00:00")
                                              .add("end_time", "2024-01-28T00:00:00+00:00")
                           )
                           .add("key", "TestExternalSourceKey")
                           .add("source_type_name", "TestSourceType")
                           .add("valid_at", "2024-01-19T00:00:00+00:00")
        )
                                   .add("external_events", externalEvents)
                                   .build();

    final var gateway = new GatewayRequests(playwright);
    final IOException ex = assertThrows(IOException.class, () -> gateway.uploadExternalSource(externalSource));
    assertTrue(ex.getMessage().contains("Source's attributes are invalid"));
  }

  // test that a source fails with an attribute of the wrong type
  @Test
  void sourceWrongTypeAttribute() throws IOException {
    final var externalEvents = Json.createArrayBuilder()
                                   .add(Json.createObjectBuilder()
                                            .add("attributes", Json.createObjectBuilder()
                                                                   .add("projectUser", "UserA")
                                                                   .add("code", "A")
                                            )
                                            .add("duration", "01:00:00")
                                            .add("event_type_name", "TestEventType")
                                            .add("key", "Event_01")
                                            .add("start_time", "2024-01-21T01:00:00+00:00")
                                   );

    final var externalSource = Json.createObjectBuilder()
        .add("source", Json.createObjectBuilder()
                           .add("attributes", Json.createObjectBuilder()
                                                  .add("version", "string") // expects int
                                                  .add("operator", "alpha")
                           )
                           .add("derivation_group_name", "TestDerivationGroup")
                           .add("period", Json.createObjectBuilder()
                                              .add("start_time", "2024-01-21T00:00:00+00:00")
                                              .add("end_time", "2024-01-28T00:00:00+00:00")
                           )
                           .add("key", "TestExternalSourceKey")
                           .add("source_type_name", "TestSourceType")
                           .add("valid_at", "2024-01-19T00:00:00+00:00")
        )
                                   .add("external_events", externalEvents)
                                   .build();

    final var gateway = new GatewayRequests(playwright);
    final IOException ex = assertThrows(IOException.class, () -> gateway.uploadExternalSource(externalSource));
    assertTrue(ex.getMessage().contains("Source's attributes are invalid"));
  }

  // test that an event fails missing an attribute
  @Test
  void eventMissingAttribute() throws IOException {
    final var externalEvents = Json.createArrayBuilder()
                                   .add(Json.createObjectBuilder()
                                            .add("attributes", Json.createObjectBuilder()
                                                                   .add("projectUser", "UserA")
                                                                   // missing: code
                                            )
                                            .add("duration", "01:00:00")
                                            .add("event_type_name", "TestEventType")
                                            .add("key", "Event_01")
                                            .add("start_time", "2024-01-21T01:00:00+00:00")
                                   );

    final var externalSource = Json.createObjectBuilder()
        .add("source", Json.createObjectBuilder()
                           .add("attributes", Json.createObjectBuilder()
                                                  .add("version", 1)
                                                  .add("operator", "alpha")
                           )
                           .add("derivation_group_name", "TestDerivationGroup")
                           .add("period", Json.createObjectBuilder()
                                              .add("start_time", "2024-01-21T00:00:00+00:00")
                                              .add("end_time", "2024-01-28T00:00:00+00:00")
                           )
                           .add("key", "TestExternalSourceKey")
                           .add("source_type_name", "TestSourceType")
                           .add("valid_at", "2024-01-19T00:00:00+00:00")
        )
                                   .add("external_events", externalEvents)
                                   .build();

    final var gateway = new GatewayRequests(playwright);
    final IOException ex = assertThrows(IOException.class, () -> gateway.uploadExternalSource(externalSource));
    assertTrue(ex.getMessage().contains("External Event"));
    assertTrue(ex.getMessage().contains("does not have a valid set of attributes, per it's type's schema:"));
  }

  // test that an event fails with an extra attribute
  @Test
  void eventExtraAttribute() throws IOException {
    final var externalEvents = Json.createArrayBuilder()
                                   .add(Json.createObjectBuilder()
                                            .add("attributes", Json.createObjectBuilder()
                                                                   .add("projectUser", "UserA")
                                                                   .add("code", "A")
                                                                   .add("extra", "attribute") // extra
                                            )
                                            .add("duration", "01:00:00")
                                            .add("event_type_name", "TestEventType")
                                            .add("key", "Event_01")
                                            .add("start_time", "2024-01-21T01:00:00+00:00")
                                   );

    final var externalSource = Json.createObjectBuilder()
        .add("source", Json.createObjectBuilder()
                           .add("attributes", Json.createObjectBuilder()
                                                  .add("version", 1)
                                                  .add("operator", "alpha")
                           )
                           .add("derivation_group_name", "TestDerivationGroup")
                           .add("period", Json.createObjectBuilder()
                                              .add("start_time", "2024-01-21T00:00:00+00:00")
                                              .add("end_time", "2024-01-28T00:00:00+00:00")
                           )
                           .add("key", "TestExternalSourceKey")
                           .add("source_type_name", "TestSourceType")
                           .add("valid_at", "2024-01-19T00:00:00+00:00")
        )
                                   .add("external_events", externalEvents)
                                   .build();

    final var gateway = new GatewayRequests(playwright);
    final IOException ex = assertThrows(IOException.class, () -> gateway.uploadExternalSource(externalSource));
    assertTrue(ex.getMessage().contains("External Event"));
    assertTrue(ex.getMessage().contains("does not have a valid set of attributes, per it's type's schema:"));
  }

  // test that an event fails with an attribute of the wrong type
  @Test
  void eventWrongTypeAttribute() throws IOException {
    final var externalEvents = Json.createArrayBuilder()
                                   .add(Json.createObjectBuilder()
                                            .add("attributes", Json.createObjectBuilder()
                                                                   .add("projectUser", "UserA")
                                                                   .add("code", 1) // should be a string
                                            )
                                            .add("duration", "01:00:00")
                                            .add("event_type_name", "TestEventType")
                                            .add("key", "Event_01")
                                            .add("start_time", "2024-01-21T01:00:00+00:00")
                                   );

    final var externalSource = Json.createObjectBuilder()
        .add("source", Json.createObjectBuilder()
                           .add("attributes", Json.createObjectBuilder()
                                                  .add("version", 1)
                                                  .add("operator", "alpha")
                           )
                           .add("derivation_group_name", "TestDerivationGroup")
                           .add("period", Json.createObjectBuilder()
                               .add("start_time", "2024-01-21T00:00:00+00:00")
                               .add("end_time", "2024-01-28T00:00:00+00:00")
                           )
                           .add("key", "TestExternalSourceKey")
                           .add("source_type_name", "TestSourceType")
                           .add("valid_at", "2024-01-19T00:00:00+00:00")
        )
                                   .add("external_events", externalEvents)
                                   .build();

    final var gateway = new GatewayRequests(playwright);
    final IOException ex = assertThrows(IOException.class, () -> gateway.uploadExternalSource(externalSource));
    assertTrue(ex.getMessage().contains("External Event"));
    assertTrue(ex.getMessage().contains("does not have a valid set of attributes, per it's type's schema:"));
  }
}
