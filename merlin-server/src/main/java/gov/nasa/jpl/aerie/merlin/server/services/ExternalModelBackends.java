package gov.nasa.jpl.aerie.merlin.server.services;

import javax.json.Json;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The set of trusted external-model backends an OPERATOR configured via the EXTERNAL_MODEL_BACKENDS
 * environment variable (JSON: [{"name": "...", "url": "http://host:port"}]). Users never supply a URL;
 * they pick a configured backend + a model it discovers. This is the SSRF/trust boundary — merlin only
 * ever calls URLs from here.
 */
public final class ExternalModelBackends {
  private final Map<String, String> byName;  // backend name -> base URL

  public ExternalModelBackends(final Map<String, String> byName) {
    this.byName = new LinkedHashMap<>(byName);
  }

  /** Parse the EXTERNAL_MODEL_BACKENDS env var (empty/absent -> no backends). */
  public static ExternalModelBackends fromEnv() {
    final var json = System.getenv("EXTERNAL_MODEL_BACKENDS");
    final var map = new LinkedHashMap<String, String>();
    if (json != null && !json.isBlank()) {
      try (final var reader = Json.createReader(new StringReader(json))) {
        for (final var v : reader.readArray()) {
          final var o = v.asJsonObject();
          map.put(o.getString("name"), o.getString("url"));
        }
      }
    }
    return new ExternalModelBackends(map);
  }

  public Set<String> names() { return byName.keySet(); }

  public Optional<String> url(final String name) { return Optional.ofNullable(byName.get(name)); }
}
