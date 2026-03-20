package gov.nasa.jpl.aerie.e2e.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import gov.nasa.jpl.aerie.e2e.types.User;

import java.io.*;
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

  private static final ObjectMapper mapper = new ObjectMapper();

  /**
   * Auto-login the AerieE2eTests user, whose token is used to upload mission model JARs
   */
  private void login() throws IOException {
    if(token != null) return;
    final var response = request.post("/auth/login", RequestOptions.create()
                                                                   .setHeader("Content-Type", "application/json")
                                                                   .setData(JsonNodeFactory.instance.objectNode()
                                                                                .put("username", "AerieE2eTests")
                                                                                .put("password", "password")
                                                                                .toString()));
    // Process Response
    if(!response.ok()){
      throw new IOException(response.statusText());
    }
    final ObjectNode bodyJson = (ObjectNode) mapper.readTree(response.text());
    if(!bodyJson.get("success").booleanValue()){
      System.err.println("Login failed");
      throw new RuntimeException(bodyJson.toString());
    }
    token = bodyJson.get("token").textValue();
  }

  /**
   * Login a particular user to get a JWT Auth Token
   * @param user the User to be logged in
   * @return the JWT token from login
   */
  public String login(User user) throws IOException {
    final var response = request.post("/auth/login", RequestOptions.create()
                                                                   .setHeader("Content-Type", "application/json")
                                                                   .setData(JsonNodeFactory.instance.objectNode()
                                                                                .put("username", user.name())
                                                                                .put("password", "password")
                                                                                .toString()));
    // Process Response
    if (!response.ok()) {
      throw new IOException(response.statusText());
    }
    final ObjectNode bodyJson = (ObjectNode) mapper.readTree(response.text());
    if (!bodyJson.get("success").booleanValue()) {
      System.err.println("Login failed");
      throw new RuntimeException(bodyJson.toString());
    }
    return bodyJson.get("token").textValue();
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
    final ObjectNode bodyJson = (ObjectNode) mapper.readTree(response.text());
    if(bodyJson.has("errors")){
      System.err.println("Errors in response: \n" + bodyJson.get("errors"));
      throw new RuntimeException(bodyJson.toString());
    }
    return bodyJson.get("id").intValue();
  }


  public void uploadExternalSourceEventTypes(ObjectNode schema) throws RuntimeException {
    final var response = request.post("/uploadExternalSourceEventTypes", RequestOptions.create()
                                                                                       .setHeader("Authorization", "Bearer "+token)
                                                                                 .setHeader("Content-Type", "application/json")
                                                                                 .setData(schema.toString()));
    // Process Response
    try {
      final ObjectNode bodyJson = (ObjectNode) mapper.readTree(response.text());
      if(!(bodyJson.has("createExternalEventTypes") && bodyJson.has("createExternalSourceTypes"))){
        System.err.println("Upload failed");
        throw new RuntimeException(bodyJson.toString());
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void uploadExternalSource(String externalSourcePath, String derivationGroupName) throws RuntimeException, IOException {
    byte[] buffer = Files.readAllBytes(Path.of("src/test/resources/" + externalSourcePath));
    FilePayload filePayload = new FilePayload(externalSourcePath, "application/json", buffer);

    final var response = request.post("/uploadExternalSource", RequestOptions.create()
                                                                             .setHeader(
                                                                                 "Authorization",
                                                                                 "Bearer " + token)
                                                                             .setMultipart(FormData
                                                                                               .create()
                                                                                               .set(
                                                                                                   "external_source_file",
                                                                                                   filePayload
                                                                                               )
                                                                                               .set(
                                                                                                   "derivation_group_name",
                                                                                                   derivationGroupName
                                                                                               )
                                                                             )
    );
    final ObjectNode bodyJson = (ObjectNode) mapper.readTree(response.text());
    if (!bodyJson.has("createExternalSource")) {
      System.err.println("Upload failed");
      throw new RuntimeException(bodyJson.toString());
    }
  }
}
