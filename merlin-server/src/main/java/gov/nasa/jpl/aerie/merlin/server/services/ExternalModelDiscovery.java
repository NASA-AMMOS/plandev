package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.merlin.protocol.model.InputType.Parameter;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.models.ActivityType;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;

/**
 * Talks to a trusted external-model backend's discovery + introspection endpoints:
 *   GET {base}/models                 -> the catalog of models the backend hosts
 *   GET {base}/introspect?model=<key> -> a model's activity/resource/config types
 * Used to populate the "available models" list and to register a selected model (introspect-on-select).
 */
public final class ExternalModelDiscovery {
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  public record DiscoveredModel(String key, String name, String version, String identityHash) {}

  /**
   * @param capabilities what the backend says PlanDev may DO with this model, keyed by capability
   *   name -- see merlin.mission_model.external_capabilities. Stored verbatim rather than parsed
   *   into a Java type on purpose: merlin's job is to carry it to the client, and a backend
   *   declaring a capability this merlin has never heard of should reach a newer UI intact rather
   *   than be dropped by an older server. Empty when the backend declares none, which reads as
   *   "nothing supported".
   */
  public record Introspection(
      Map<String, ActivityType> activityTypes,
      Map<String, ValueSchema> resourceTypes,
      List<Parameter> parameters,
      String identityHash,
      JsonObject capabilities) {}

  /** The backend's model catalog (one entry for a single-model backend). */
  public static List<DiscoveredModel> listModels(final String baseUrl) throws IOException, InterruptedException {
    final JsonObject o = readObject(get(join(baseUrl, "models")));
    final var out = new ArrayList<DiscoveredModel>();
    final var arr = o.getJsonArray("models");
    if (arr != null) for (final var v : arr) {
      final var m = v.asJsonObject();
      out.add(new DiscoveredModel(
          m.getString("key"),
          m.getString("name", m.getString("key")),
          m.getString("version", ""),
          m.getString("identityHash", "")));
    }
    return out;
  }

  /** One model's types, mapped onto the shapes registerModelTypes expects. */
  public static Introspection introspect(final String baseUrl, final String modelKey)
  throws IOException, InterruptedException {
    final var url = join(baseUrl, "introspect") + "?model=" + URLEncoder.encode(modelKey, StandardCharsets.UTF_8);
    final JsonObject o = readObject(get(url));

    final var activityTypes = new LinkedHashMap<String, ActivityType>();
    final var actArr = o.getJsonArray("activityTypes");
    if (actArr != null) for (final var av : actArr) {
      final var a = av.asJsonObject();
      final var params = new ArrayList<Parameter>();
      final var pArr = a.getJsonArray("parameters");
      if (pArr != null) for (final var pv : pArr) {
        final var p = pv.asJsonObject();
        params.add(new Parameter(p.getString("name"), valueSchemaP.parse(p.get("schema")).getSuccessOrThrow()));
      }
      final var required = new ArrayList<String>();
      final var rArr = a.getJsonArray("requiredParameters");
      if (rArr != null) for (final var s : rArr) required.add(((JsonString) s).getString());
      final var name = a.getString("name");
      // A backend MAY declare computed attributes -- values its model derives while running an activity,
      // which command expansion reads as `computed.*`. Defaults to a closed empty struct, which is both
      // the back-compatible answer and the honest one for a backend that declares nothing: it means "this
      // activity produces no computed attributes", and the ingest gate will hold it to that.
      final var computedSchema = (a.containsKey("computedAttributesSchema") && !a.isNull("computedAttributesSchema"))
          ? valueSchemaP.parse(a.get("computedAttributesSchema")).getSuccessOrThrow()
          : ValueSchema.ofStruct(Map.of());
      activityTypes.put(name, new ActivityType(
          name, params, required, computedSchema, Optional.empty(), Optional.empty()));
    }

    final var resourceTypes = new LinkedHashMap<String, ValueSchema>();
    final var resArr = o.getJsonArray("resourceTypes");
    if (resArr != null) for (final var rv : resArr) {
      final var r = rv.asJsonObject();
      resourceTypes.put(r.getString("name"), valueSchemaP.parse(r.get("schema")).getSuccessOrThrow());
    }

    final var parameters = new ArrayList<Parameter>();
    final var pArr = o.getJsonArray("parameters");
    if (pArr != null) for (final var pv : pArr) {
      final var p = pv.asJsonObject();
      parameters.add(new Parameter(p.getString("name"), valueSchemaP.parse(p.get("schema")).getSuccessOrThrow()));
    }

    final var capabilities = o.get("capabilities");
    return new Introspection(
        activityTypes, resourceTypes, parameters, o.getString("identityHash", ""),
        (capabilities instanceof JsonObject obj) ? obj : JsonValue.EMPTY_JSON_OBJECT);
  }

  private static String join(final String base, final String path) {
    return base.endsWith("/") ? base + path : base + "/" + path;
  }

  private static JsonObject readObject(final String body) {
    try (final var r = Json.createReader(new StringReader(body))) { return r.readObject(); }
  }

  private static String get(final String url) throws IOException, InterruptedException {
    final var resp = HTTP.send(
        HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2) {
      throw new IOException("External backend GET " + url + " -> HTTP " + resp.statusCode() + ": " + resp.body());
    }
    return resp.body();
  }

  private ExternalModelDiscovery() {}
}
