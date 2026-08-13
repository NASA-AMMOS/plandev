package gov.nasa.jpl.aerie.e2e.utils;

import com.microsoft.playwright.Playwright;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * This class centralizes shared external event upload functionality for use by end-to-end tests.
 * These upload functions are shared between constraints and scheduling tests.
 */
public class ExternalEventUtils implements AutoCloseable {
  private final HasuraRequests hasura;

  // Base values
  private final String sourceType;
  private final String sourceKey;
  private final String eventType;
  private final String derivationGroup;

  // Alternate values (generated based on base values)
  private final String alternateSourceKey;
  private final String alternateEventType;
  private final String alternateDerivationGroup;

  /**
   * Create a new External Event Utils and upload the appropriate data into the system via the Gateway.
   */
  public ExternalEventUtils(
      Playwright playwright,
      HasuraRequests hasura,
      String sourceType,
      String sourceKey,
      String eventType,
      String derivationGroup
  ) throws IOException {
    this.hasura = hasura;

    // Set base keys
    this.sourceType = sourceType;
    this.sourceKey = sourceKey;
    this.eventType = eventType;
    this.derivationGroup = derivationGroup;

    // Set alternate keys based on base values
    this.alternateSourceKey = "Alternate_" + sourceKey;
    this.alternateEventType = "Alternate_" + eventType;
    this.alternateDerivationGroup = "Alternate_" + derivationGroup;

    // Upload needed data
    uploadSchemas(playwright);
    uploadExternalSourceFiles(playwright);
  }

  /**
   * Remove the uploaded data from the database.
   */
  @Override
  public void close() throws IOException {
    // Cleanup uploaded data
    cleanupDerivationGroups(hasura);
    cleanupSchemas(hasura);
  }

  public String alternateSourceKey() {
    return alternateSourceKey;
  }

  public String alternateEventType() {
    return alternateEventType;
  }

  public String alternateDerivationGroup() {
    return alternateDerivationGroup;
  }

  /**
   * Upload the schemas for the External Source and Event Types (both base and alternate).
   */
  private void uploadSchemas(Playwright playwright) throws IOException {
    final String event_types = """
        {
          "%s": {
            "type": "object",
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
            },
            "required": ["projectUser", "code"]
          },
          "%s": {
            "type": "object",
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
            },
            "required": ["projectUser", "code"]
          }
        }
        """.formatted(eventType, alternateEventType);

    final String source_types = """
        {
          "%s": {
            "type": "object",
            "properties": {
              "version": {
                  "type": "number"
              },
              "optional": {
                "type": "string"
              }
          },
          "required": ["version"]
          }
        }
        """.formatted(sourceType);

    final JsonObject schema = Json.createObjectBuilder()
                                  .add("event_types", event_types)
                                  .add("source_types", source_types)
                                  .build();

    try (final var gateway = new GatewayRequests(playwright)) {
      gateway.uploadExternalSourceEventTypes(schema);
    }
  }

  /**
   * Upload two external source files: one for each template.
   */
  private void uploadExternalSourceFiles(Playwright playwright) throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      gateway.uploadExternalSource("external_source_A.json", generateExternalSourceA(), derivationGroup);
      gateway.uploadExternalSource("external_source_B.json", generateExternalSourceB(), alternateDerivationGroup);
    }
  }

  /**
   * Remove uploaded External Source and Event Type schemas.
   */
  private void cleanupSchemas(HasuraRequests hasura) throws IOException {
    hasura.deleteExternalSourceType(sourceType);
    hasura.deleteExternalEventType(eventType);
    hasura.deleteExternalEventType(alternateEventType);
  }

  /**
   * Remove database entries for the uploaded external source and derivation groups
   */
  private void cleanupDerivationGroups(HasuraRequests hasura) throws IOException {
    hasura.deleteExternalSource(sourceKey, derivationGroup);
    hasura.deleteExternalSource(alternateSourceKey, alternateDerivationGroup);
    hasura.deleteDerivationGroup(derivationGroup);
    hasura.deleteDerivationGroup(alternateDerivationGroup);
  }

  /**
   * Generate a byte[] representing an external sources file ready to be uploaded to the Gateway.
   *
   * Template A is an external source file that includes:
   *   - one source type (sourceType)
   *   - one event type (eventType)
   * It uses the base sourceKey as its key and is associated with the base derivationGroup.
   */
  private byte[] generateExternalSourceA() {
    final String template =
        """
        {
          "source": {
            "attributes": { "version": 1 },
            "derivation_group_name": "%s",
            "period": {
              "start_time": "2023-01-01T00:00:00",
              "end_time": "2023-01-08T00:00:00"
            },
            "key": "%s",
            "source_type_name": "%s",
            "valid_at": "2024-01-01T00:00:00"
          },
          "events": [
            {
              "attributes": {
                "projectUser": "UserA",
                "code": "A"
              },
              "duration": "01:00:00",
              "event_type_name": "%s",
              "key": "Event_01",
              "start_time": "2023-01-01T01:00:00"
            },
            {
              "attributes": {
                "projectUser": "UserA",
                "code": "A",
                "optional": "present"
              },
              "duration": "01:00:00",
              "event_type_name": "%s",
              "key": "Event_02",
              "start_time": "2023-01-01T03:00:00"
            },
            {
              "attributes": {
                "projectUser": "UserB",
                "code": "B",
                "optional": "present"
              },
              "duration": "01:00:00",
              "event_type_name": "%s",
              "key": "Event_03",
              "start_time": "2023-01-01T05:00:00"
            }
          ]
        }
        """;

    return template
        .formatted(derivationGroup, sourceKey, sourceType, eventType, eventType, eventType)
        .getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Generate a byte[] representing an external sources file ready to be uploaded to the Gateway.
   *
   * Template B is an external source file that includes:
   *   - one source type (sourceType)
   *   - two event types (eventType, alternateEventType)
   * It uses the alternateSourceKey as its key and is associated with the alternateDerivationGroup.
   */
  private byte[] generateExternalSourceB() {
    final String template =
        """
        {
          "source": {
            "attributes": { "version": 2, "optional": "present" },
            "derivation_group_name": "%s",
            "period": {
              "start_time": "2023-01-01T00:00:00",
              "end_time": "2023-01-08T00:00:00"
            },
            "key": "%s",
            "source_type_name": "%s",
            "valid_at": "2024-01-01T00:00:00"
          },
          "events": [
            {
              "attributes": {
                "projectUser": "UserB",
                "code": "B",
                "optional": "present"
              },
              "duration": "01:00:00",
              "event_type_name": "%s",
              "key": "Event_01",
              "start_time": "2023-01-02T01:00:00"
            },
            {
              "attributes": {
                "projectUser": "UserB",
                "code": "B"
              },
              "duration": "01:00:00",
              "event_type_name": "%s",
              "key": "Event_02",
              "start_time": "2023-01-02T03:00:00"
            },
            {
              "attributes": {
                "projectUser": "UserA",
                "code": "A"
              },
              "duration": "01:00:00",
              "event_type_name": "%s",
              "key": "Event_03",
              "start_time": "2023-01-02T05:00:00"
            }
          ]
        }
        """;
    return template
        .formatted(alternateDerivationGroup, alternateSourceKey, sourceType, eventType, alternateEventType, alternateEventType)
        .getBytes(StandardCharsets.UTF_8);
  }
}
