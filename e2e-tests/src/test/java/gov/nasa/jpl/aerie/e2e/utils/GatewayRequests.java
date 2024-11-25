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


  public void uploadExternalSourceEventTypes(String schema) throws IOException {
    final var response = request.post("/uploadExternalSourceEventTypes", RequestOptions.create()
                                                                                       .setHeader("Authorization", "Bearer "+token)
                                                                                 .setHeader("Content-Type", "application/json")
                                                                                 .setData(schema));
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

  public void uploadExternalSource(JsonObject externalSource) throws IOException {
    final var response = request.post("/uploadExternalSource", RequestOptions.create()
                                                                             .setHeader("Authorization", "Bearer "+token)
                                                                                 .setHeader(
                                                                                     "Content-Type",
                                                                                     "application/json")
                                                                                 .setData(externalSource.toString()));
    if (!response.ok()) {
      throw new IOException(response.statusText() + "\n" + response.text());
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
