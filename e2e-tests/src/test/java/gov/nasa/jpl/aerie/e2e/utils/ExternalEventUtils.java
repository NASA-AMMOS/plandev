package gov.nasa.jpl.aerie.e2e.utils;

import com.microsoft.playwright.Playwright;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.IOException;

/**
 * This class centralizes shared external event upload functionality for use by end-to-end tests.
 * These upload functions are shared between constraints and scheduling tests.
 *
 * It provides two functions that upload a standardized set of source/event types and the sources themselves.
 * These functions introduce:
 *    - one source type (TestType)
 *    - two event types ("TestType", "TestType_2"),
 *    - and upload sources keyed by "Test.json" and "NewTest.json", which represent
 *        "scheduling_source_A.json" and "scheduling_source_B.json", falling under two
 *        separate derivation groups: "TestGroup" and "TestGroup_2", respectively.
 */
public class ExternalEventUtils {
  public final static String SOURCE_TYPE = "TestType";
  public final static String EVENT_TYPE = "TestType";
  public final static String ADDITIONAL_EVENT_TYPE = EVENT_TYPE + "_2";
  public final static String SOURCE_KEY = "Test.json";
  public final static String ADDITIONAL_SOURCE_KEY = "NewTest.json";

  public final static String DERIVATION_GROUP = "TestGroup";
  public final static String ADDITIONAL_DERIVATION_GROUP = DERIVATION_GROUP + "_2";


  public static void uploadExternalSourceEventTypes(Playwright playwright) throws IOException {
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
        """.formatted(EVENT_TYPE, ADDITIONAL_EVENT_TYPE);

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
        """.formatted(SOURCE_TYPE);

    final JsonObject schema = Json.createObjectBuilder()
                                  .add("event_types", event_types)
                                  .add("source_types", source_types)
                                  .build();

    try (final var gateway = new GatewayRequests(playwright)) {
      gateway.uploadExternalSourceEventTypes(schema);
    }
  }

  public static void uploadExternalSources(Playwright playwright) throws IOException {
    try (final var gateway = new GatewayRequests(playwright)) {
      gateway.uploadExternalSource("scheduling_source_A.json", DERIVATION_GROUP);
      gateway.uploadExternalSource("scheduling_source_B.json", ADDITIONAL_DERIVATION_GROUP);
    }
  }
}
