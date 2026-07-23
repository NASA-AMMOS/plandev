package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.merlin.protocol.model.InputType.ValidationNotice;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.SerializedActivity;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonString;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;

/**
 * Delegates activity argument validation + effective-argument resolution to a foreign ("external")
 * model backend over HTTP, so validity is answered authoritatively by the model itself rather than
 * by the shallow stored {@code ValueSchema} check. The backend's {@code /validate} endpoint is derived
 * from its {@code /simulate} URL.
 *
 * <p>The wire response can carry a full {@link ValidationNotice} list per activity; a backend that can
 * only report pass/fail simply returns a single notice on failure. Callers fall back to the stored-schema
 * check when the backend is unreachable (see {@code LocalMissionModelService}).
 */
public final class ExternalValidationBackend {
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  // Validation is on the interactive editing path, so bound how long a slow backend can block it.
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  /** One activity's authoritative verdict. {@code notices} is empty iff valid; {@code effectiveArguments}
   *  is present when the backend resolved defaults. */
  public record ActivityValidation(
      boolean valid,
      List<ValidationNotice> notices,
      Optional<Map<String, SerializedValue>> effectiveArguments) {}

  /**
   * POST the activities to the backend and return one verdict per activity, in input order.
   * @param effectiveOnly when true, the backend only resolves effective arguments (defaults) and skips the
   *   deep construction check — used for form population, where args may be partial and construction would fail.
   * @throws IOException / InterruptedException if the backend is unreachable or returns a non-2xx status.
   */
  public static List<ActivityValidation> validateActivities(
      final String simulateUrl, final List<SerializedActivity> activities, final boolean effectiveOnly)
      throws IOException, InterruptedException
  {
    final var url = URI.create(simulateUrl).resolve("validate");

    final var activitiesB = Json.createArrayBuilder();
    for (final var act : activities) {
      activitiesB.add(Json.createObjectBuilder()
          .add("type", act.getTypeName())
          .add("arguments", serializedValueP.unparse(SerializedValue.of(act.getArguments()))));
    }
    final var body = Json.createObjectBuilder()
        .add("activities", activitiesB)
        .add("effectiveOnly", effectiveOnly)
        .build().toString();

    final var httpResponse = HTTP.send(
        HttpRequest.newBuilder(url)
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    if (httpResponse.statusCode() / 100 != 2) {
      throw new IOException("External validation backend returned HTTP " + httpResponse.statusCode()
          + ": " + httpResponse.body());
    }

    final JsonObject response;
    try (final var reader = Json.createReader(new StringReader(httpResponse.body()))) {
      response = reader.readObject();
    }

    final var results = new ArrayList<ActivityValidation>();
    final var resultsArr = response.getJsonArray("results");
    if (resultsArr != null) {
      for (final var rv : resultsArr) {
        final var r = rv.asJsonObject();
        final boolean valid = r.getBoolean("valid", true);

        final var notices = new ArrayList<ValidationNotice>();
        final var noticesArr = r.getJsonArray("notices");
        if (noticesArr != null) {
          for (final var nv : noticesArr) {
            final var n = nv.asJsonObject();
            final var subjects = new ArrayList<String>();
            final var subjArr = n.getJsonArray("subjects");
            if (subjArr != null) for (final var s : subjArr) subjects.add(((JsonString) s).getString());
            notices.add(new ValidationNotice(subjects, n.getString("message", "invalid arguments")));
          }
        }
        // Guarantee an invalid verdict always carries at least one notice (callers treat empty == valid).
        if (!valid && notices.isEmpty()) notices.add(new ValidationNotice(List.of(), "invalid arguments"));

        Optional<Map<String, SerializedValue>> effective = Optional.empty();
        if (r.containsKey("effectiveArguments") && !r.isNull("effectiveArguments")) {
          effective = serializedValueP.parse(r.get("effectiveArguments")).getSuccessOrThrow().asMap();
        }
        results.add(new ActivityValidation(valid, notices, effective));
      }
    }
    return results;
  }

  private ExternalValidationBackend() {}
}
