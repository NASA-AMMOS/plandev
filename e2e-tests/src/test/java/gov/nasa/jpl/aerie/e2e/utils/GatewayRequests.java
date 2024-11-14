package gov.nasa.jpl.aerie.e2e.utils;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

public class GatewayRequests implements AutoCloseable {
  private final APIRequestContext request;
  private static String token;

  public GatewayRequests(Playwright playwright) throws IOException {
    request = playwright.request().newContext(
            new APIRequest.NewContextOptions()
                    .setBaseURL(BaseURL.GATEWAY.url));
    login();
  }

  private void login() throws IOException {
    if(token != null) return;
    final var response = request.post("/auth/login", RequestOptions.create()
                                                                   .setHeader("Content-Type", "application/json")
                                                                   .setData(Json.createObjectBuilder()
                                                                                .add("username", "AerieE2eTests")
                                                                                .add("password", "password")
                                                                                .build()
                                                                                .toString()));
    // Process Response
    if(!response.ok()){
      throw new IOException(response.statusText());
    }
    try(final var reader = Json.createReader(new StringReader(response.text()))){
      final JsonObject bodyJson = reader.readObject();
      if(!bodyJson.getBoolean("success")){
        System.err.println("Login failed");
        throw new RuntimeException(bodyJson.toString());
      }
      token = bodyJson.getString("token");
    }
  }

  @Override
  public void close() {
    request.dispose();
  }

  /**
   * Uploads the Banananation JAR
   */
  public int uploadJarFile() throws IOException {
    return uploadJarFile("../examples/banananation/build/libs/banananation.jar");
  }

  /**
   * Uploads the Foo JAR
   */
  public int uploadFooJar() throws IOException {
    return uploadJarFile("../examples/foo-missionmodel/build/libs/foo-missionmodel.jar");
  }

  /**
   * Uploads the JAR found at searchPath
   * @param jarPath is relative to the e2e-tests directory.
   */
  public int uploadJarFile(String jarPath) throws IOException {
    // Build File Payload
    final Path absolutePath = Path.of(jarPath).toAbsolutePath();
    final byte[] buffer = Files.readAllBytes(absolutePath);
    final FilePayload payload = new FilePayload(
        absolutePath.getFileName().toString(),
        "application/java-archive",
        buffer);

    final var response = request.post("/file", RequestOptions.create()
                                                             .setHeader("Authorization", "Bearer "+token)
                                                             .setMultipart(FormData.create().set("file", payload)));

    // Process Response
    if(!response.ok()){
      throw new IOException(response.statusText());
    }
    try(final var reader = Json.createReader(new StringReader(response.text()))){
      final JsonObject bodyJson = reader.readObject();
      if(bodyJson.containsKey("errors")){
        System.err.println("Errors in response: \n" + bodyJson.get("errors"));
        throw new RuntimeException(bodyJson.toString());
      }
      return bodyJson.getInt("id");
    }
  }


  // TODO: add upload external event type
  public void uploadExternalEventType() throws IOException {
//  {
//    "$schema": "http://json-schema.org/draft-07/schema",
//    "title": "TestEventType",
//    "description": "Schema for the attributes of the DSNContact External Event Type.",
//    "type": "object",
//    "properties": {
//        "projectUser": {
//            "type": "string"
//        },
//        "code": {
//            "type": "string"
//        }
//    }
//}

    final var schema = Json.createObjectBuilder()
                           .add("$schema", "http://json-schema.org/draft-07/schema")
                           .add("title", "TestEventType")
                           .add("description", "Schema for the attributes of the DSNContact External Event Type.")
                           .add("type", "object")
                           .add("properties", Json.createObjectBuilder()
                                                  .add("projectUser", Json.createObjectBuilder()
                                                                      .add("type", "string")
                                                  )
                                                  .add("code", Json.createObjectBuilder()
                                                                     .add("type", "string")
                                                  )
                           )
                           .build();

    final var response = request.post("/uploadExternalEventType", RequestOptions.create()
                                                                                 .setHeader("Content-Type", "application/json")
                                                                                 .setData(Json.createObjectBuilder()
                                                                                              .add("external_event_type_name", "TestEventType")
                                                                                              .add("attribute_schema", schema)
                                                                                              .build()
                                                                                              .toString()));
    // Process Response
    if(!response.ok()){
      throw new IOException(response.statusText());
    }
    try(final var reader = Json.createReader(new StringReader(response.text()))){
      final JsonObject bodyJson = reader.readObject();
      if(!bodyJson.containsKey("data")){
        System.err.println("Upload failed");
        throw new RuntimeException(bodyJson.toString());
      }
    }
  }


  // TODO: add upload external source type
  public void uploadExternalSourceType() throws IOException {
//    {
//        "$schema": "http://json-schema.org/draft-07/schema",
//        "title": "DSN Contact Confirmed",
//        "description": "Schema for the attributes of the DSN Contact Confirmed External Source Type.",
//        "type": "object",
//        "properties": {
//            "version": {
//                "type": "number"
//            },
//            "wrkcat": {
//                "type": "string"
//            }
//        }
//    }

    final var schema = Json.createObjectBuilder()
                           .add("$schema", "http://json-schema.org/draft-07/schema")
                           .add("title", "TestSourceType")
                           .add("description", "Schema for the attributes of the DSN Contact Confirmed External Source Type.")
                           .add("type", "object")
                           .add("properties", Json.createObjectBuilder()
                                                  .add("version", Json.createObjectBuilder()
                                                                      .add("type", "number")
                                                  )
                                                  .add("wrkcat", Json.createObjectBuilder()
                                                                     .add("type", "string")
                                                  )
                           )
                           .build();

    final var response = request.post("/uploadExternalSourceType", RequestOptions.create()
                                                                                 .setHeader("Content-Type", "application/json")
                                                                                 .setData(Json.createObjectBuilder()
                                                                                              .add("external_source_type_name", "TestSourceType")
                                                                                              .add("attribute_schema", schema)
                                                                                              .add("allowed_event_types", Json.createArrayBuilder()
                                                                                                                              .add("TestEventType")
                                                                                              )
                                                                                              .build()
                                                                                              .toString()));
    if(!response.ok()){
      throw new IOException(response.statusText());
    }
    try(final var reader = Json.createReader(new StringReader(response.text()))){
      final JsonObject bodyJson = reader.readObject();
      if(!bodyJson.containsKey("data")){
        System.err.println("Upload failed");
        throw new RuntimeException(bodyJson.toString());
      }
    }
  }

  // TODO: add upload external source
  public void uploadExternalSource() throws IOException {
//    {
//        "$schema": "http://json-schema.org/draft-07/schema",
//        "title": "DSN Contact Confirmed",
//        "description": "Schema for the attributes of the DSN Contact Confirmed External Source Type.",
//        "type": "object",
//        "properties": {
//            "version": {
//                "type": "number"
//            },
//            "wrkcat": {
//                "type": "string"
//            }
//        }
//    }

//    attributes: externalSourceAttributes,
//    derivation_group_name: derivationGroupInsert.name,
//    end_time: endTimeFormatted,
//    external_events: {
//      data: null, // updated after this map is created
//    },
//    key: externalSourceKey,
//    source_type_name: externalSourceTypeName,
//    start_time: startTimeFormatted,
//    valid_at: validAtFormatted,

//    attributes: externalEvent.attributes,
//    duration: externalEvent.duration,
//    event_type_name: externalEvent.event_type,
//    key: externalEvent.key,
//    start_time: externalEvent.start_time,

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
                                   .add("attributes", Json.createObjectBuilder()
                                                          .add("version", 79)
                                                          .add("wrkcat", "1A1")
                                   )
                                   .add("derivation_group_name", "TestDerivationGroup")
                                   .add("end_time", "2024-01-28T00:00:00+00:00")
                                   .add("external_events", Json.createObjectBuilder()
                                       .add("data", externalEvents)
                                   )
                                   .add("key", "TestExternalSourceKey")
                                   .add("source_type_name", "TestSourceType")
                                   .add("start_time", "2024-01-21T00:00:00+00:00")
                                   .add("valid_at", "2024-01-19T00:00:00+00:00");

    final var response = request.post("/uploadExternalSource", RequestOptions.create()
                                                                                 .setHeader(
                                                                                     "Content-Type",
                                                                                     "application/json")
                                                                                 .setData(externalSource.build().toString()));
    if (!response.ok()) {
      throw new IOException(response.statusText());
    }
    try (final var reader = Json.createReader(new StringReader(response.text()))) {
      final JsonObject bodyJson = reader.readObject();
      if (!bodyJson.containsKey("data")) {
        System.err.println("Upload failed");
        throw new RuntimeException(bodyJson.toString());
      }
    }
  }
}
