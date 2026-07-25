package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.models.ActivityType;
import gov.nasa.jpl.aerie.merlin.server.models.ExternalSpan;
import gov.nasa.jpl.aerie.merlin.server.models.ProfileSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Closed-world validation of results arriving FROM an external model backend.
 *
 * <p>A JAR model cannot lie to merlin about its own shape: the same classloaded object that declares
 * a resource is the one that produces its profile, so the two agree by construction. An external
 * backend has no such guarantee -- it is a separate process, separately versioned, that hands us JSON.
 * Nothing downstream re-checks it: profiles land in {@code profile_segment} and spans in {@code span}
 * verbatim, and the first symptom of a mismatch is a constraint silently reading a resource that was
 * never registered, or a UI row for a span type that does not exist. This gate is where that gets
 * caught, at the boundary, while we still know which backend produced it.
 *
 * <p><b>Warn-only by default.</b> We do not yet know what real adapters emit in the tail cases, and a
 * gate that rejects on its first day would break working simulations to enforce a rule we invented.
 * So the default is to observe and log; {@code EXTERNAL_INGEST_GATE=reject} flips it to enforcing once
 * the warnings have gone quiet, and {@code off} disables it entirely.
 *
 * <p><b>No size limits here.</b> A legitimate simulation may produce an enormous number of spans and
 * segments; counting them is observability, not a cap. Nothing in this class rejects on volume.
 */
public final class ExternalResultsGate {
  private static final Logger log = LoggerFactory.getLogger(ExternalResultsGate.class);

  public enum Mode {
    /** Run no checks at all. */
    OFF,
    /** Check and log; never fail an ingest. */
    WARN,
    /** Check and throw on the first violation set, aborting the ingest. */
    REJECT;

    public static Mode fromEnv() {
      final var raw = System.getenv("EXTERNAL_INGEST_GATE");
      if (raw == null || raw.isBlank()) return WARN;
      try {
        return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (final IllegalArgumentException ex) {
        log.warn("EXTERNAL_INGEST_GATE='{}' is not one of off|warn|reject; defaulting to warn", raw);
        return WARN;
      }
    }
  }

  /**
   * Names that become BARE TypeScript identifiers in the generated constraints/scheduling typings, so an
   * illegal character produces code that does not compile rather than a cosmetic problem. Two sites force
   * this: an activity type is emitted as an unquoted enum member and concatenated into an interface name
   * ({@code ParameterType} + name), and a parameter is emitted as an unquoted object-type key
   * ({@code TypescriptCodeGenerationService.java:31,58,133}).
   */
  private static final Pattern TS_IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]{0,127}");

  /**
   * Resource names, by contrast, are ALWAYS emitted quoted ({@code TypescriptCodeGenerationService.java:38,44,50})
   * and are only ever row values in Postgres -- never column or field names. So spaces and dots are fine,
   * and real adaptations use both: the adapter flattens arrayed resources to {@code Name.Index}, and
   * Blackbird lets a bin be named {@code "my bin"}. Only genuinely unusable text is worth flagging.
   */
  private static final Pattern RESOURCE_NAME = Pattern.compile("[^\\p{Cntrl}]{1,255}");

  /** How many distinct findings to retain and log. Counting continues past this; reporting does not. */
  private static final int MAX_REPORTED = 50;

  private final String context;
  private final Mode mode;
  private final Map<String, ActivityType> activityTypes;
  private final Map<String, ValueSchema> resourceTypes;
  private final Set<Long> knownDirectiveIds;
  private final long simDurationUs;

  private final List<String> findings = new ArrayList<>();
  private int findingCount = 0;

  // Bookkeeping accumulated across the stream, checked as a whole in finish().
  private final Set<Long> spanIds = new HashSet<>();
  private final Map<Long, Long> spanParents = new HashMap<>();
  private final Map<String, Long> segmentDurationByResource = new HashMap<>();
  private int spanCount = 0;
  private long segmentCount = 0;

  private ExternalResultsGate(
      final String context,
      final Mode mode,
      final Map<String, ActivityType> activityTypes,
      final Map<String, ValueSchema> resourceTypes,
      final Set<Long> knownDirectiveIds,
      final long simDurationUs)
  {
    this.context = context;
    this.mode = mode;
    this.activityTypes = activityTypes;
    this.resourceTypes = resourceTypes;
    this.knownDirectiveIds = knownDirectiveIds;
    this.simDurationUs = simDurationUs;
  }

  /**
   * A gate whose closed world is the registered model. {@code activityTypes} and {@code resourceTypes}
   * come from the same {@code activity_type}/{@code resource_type} rows the UI reads, so "unknown" here
   * means exactly "the UI has nowhere to put this".
   *
   * @param knownDirectiveIds the directives we sent; a returned span may claim to belong to one of these
   *                          and no others.
   */
  public static ExternalResultsGate of(
      final String context,
      final Map<String, ActivityType> activityTypes,
      final Map<String, ValueSchema> resourceTypes,
      final Set<Long> knownDirectiveIds,
      final long simDurationUs)
  {
    return new ExternalResultsGate(
        context, Mode.fromEnv(), activityTypes, resourceTypes, knownDirectiveIds, simDurationUs);
  }

  /** Same, with the mode given explicitly rather than read from the environment. */
  static ExternalResultsGate withMode(
      final Mode mode,
      final Map<String, ActivityType> activityTypes,
      final Map<String, ValueSchema> resourceTypes,
      final Set<Long> knownDirectiveIds,
      final long simDurationUs)
  {
    return new ExternalResultsGate("test", mode, activityTypes, resourceTypes, knownDirectiveIds, simDurationUs);
  }

  public static ExternalResultsGate disabled() {
    return new ExternalResultsGate("", Mode.OFF, Map.of(), Map.of(), Set.of(), Long.MAX_VALUE);
  }

  public boolean enabled() {
    return this.mode != Mode.OFF;
  }

  /** Everything the gate objected to, in the order found (capped at {@link #MAX_REPORTED}). */
  List<String> findings() {
    return List.copyOf(this.findings);
  }

  // --- profiles -------------------------------------------------------------------------------

  /**
   * A resource the backend produced must be one the model registered, with the schema it registered.
   * A schema drift (backend now says {@code int} where {@code resource_type} says {@code real}) is the
   * versioning-skew symptom: the segments will still store, and constraints will then read them through
   * the stale schema.
   */
  public void checkResourceProfile(final String name, final ValueSchema schema) {
    if (this.mode == Mode.OFF) return;
    checkResourceName(name);
    if (this.resourceTypes.isEmpty()) return;   // nothing registered yet: no closed world to check against
    final var registered = this.resourceTypes.get(name);
    if (registered == null) {
      finding("resource '" + name + "' is not a registered resource type of this model");
    } else if (!registered.equals(schema)) {
      finding("resource '" + name + "' has schema " + schema + " but is registered as " + registered);
    }
  }

  public void checkRealSegment(final String name, final long durationUs, final double initial, final double rate) {
    if (this.mode == Mode.OFF) return;
    checkSegmentDuration(name, durationUs);
    if (!Double.isFinite(initial)) finding("resource '" + name + "' has a non-finite initial value (" + initial + ")");
    if (!Double.isFinite(rate)) finding("resource '" + name + "' has a non-finite rate (" + rate + ")");
  }

  public void checkDiscreteSegment(final String name, final long durationUs, final SerializedValue value) {
    if (this.mode == Mode.OFF) return;
    checkSegmentDuration(name, durationUs);
    final var registered = this.resourceTypes.get(name);
    if (registered == null) return;             // already reported by checkResourceProfile
    final var problem = nonconformance(value, registered);
    if (problem != null) finding("resource '" + name + "' segment value does not match its schema: " + problem);
  }

  private void checkSegmentDuration(final String name, final long durationUs) {
    this.segmentCount++;
    if (durationUs < 0) {
      finding("resource '" + name + "' has a segment of negative duration (" + durationUs + "us)");
      return;
    }
    // Profile time is cumulative, so a profile that runs past the simulation is a clock disagreement
    // between us and the backend -- the segments would extend beyond the dataset's own bounds.
    final var total = this.segmentDurationByResource.merge(name, durationUs, Long::sum);
    if (total > this.simDurationUs && total - durationUs <= this.simDurationUs) {
      finding("resource '" + name + "' profile runs " + total + "us, past the simulation duration of "
              + this.simDurationUs + "us");
    }
  }

  // --- spans ----------------------------------------------------------------------------------

  /**
   * @param parentId    null for a root span
   * @param directiveId null for a span the backend created on its own (decomposition, or -- for a
   *                    forward-dispatch model like Blackbird -- its own scheduler placing an activity)
   */
  public void checkSpan(
      final long spanId,
      final String type,
      final long startOffsetUs,
      final long durationUs,
      final Map<String, SerializedValue> arguments,
      final Long parentId,
      final Long directiveId)
  {
    if (this.mode == Mode.OFF) return;
    this.spanCount++;

    if (!this.spanIds.add(spanId)) finding("duplicate spanId " + spanId);
    if (parentId != null) this.spanParents.put(spanId, parentId);

    checkTypescriptIdentifier("activity type", type);
    final var activityType = this.activityTypes.get(type);
    if (activityType == null) {
      if (!this.activityTypes.isEmpty()) {
        finding("span " + spanId + " has type '" + type + "', which is not a registered activity type");
      }
    } else {
      checkArguments("span " + spanId + " (" + type + ")", activityType, arguments);
    }

    // A span may only claim a directive we actually sent. Anything else would attach simulation output
    // to a directive the backend invented -- or to another plan's.
    if (directiveId != null && !this.knownDirectiveIds.isEmpty() && !this.knownDirectiveIds.contains(directiveId)) {
      finding("span " + spanId + " claims directiveId " + directiveId + ", which was not sent to the backend");
    }

    if (durationUs < 0) finding("span " + spanId + " has negative duration (" + durationUs + "us)");
    if (startOffsetUs < 0) finding("span " + spanId + " starts before the simulation (" + startOffsetUs + "us)");
    if (startOffsetUs > this.simDurationUs) {
      finding("span " + spanId + " starts at " + startOffsetUs + "us, past the simulation duration of "
              + this.simDurationUs + "us");
    }
  }

  /** Arguments on a span are the model's own record of what it ran; they must fit the declared parameters. */
  private void checkArguments(
      final String subject, final ActivityType activityType, final Map<String, SerializedValue> arguments)
  {
    final var declared = new HashMap<String, ValueSchema>();
    for (final var p : activityType.parameters()) declared.put(p.name(), p.schema());

    for (final var arg : arguments.entrySet()) {
      final var schema = declared.get(arg.getKey());
      if (schema == null) {
        finding(subject + " has argument '" + arg.getKey() + "', which is not a declared parameter");
        continue;
      }
      final var problem = nonconformance(arg.getValue(), schema);
      if (problem != null) finding(subject + " argument '" + arg.getKey() + "' " + problem);
    }
    for (final var required : activityType.requiredParameters()) {
      if (!arguments.containsKey(required)) {
        finding(subject + " is missing required parameter '" + required + "'");
      }
    }
  }

  /**
   * The push counterpart of the streaming checks: an adapter that POSTs a finished result set through
   * {@code ingestExternalSimulationResults} bypasses {@link ExternalSimulationBackend} entirely, so the
   * same closed world has to be applied here or the push path is a hole in the pull path's gate.
   */
  public void checkIngest(final ProfileSet profiles, final List<ExternalSpan> spans) {
    if (this.mode == Mode.OFF) return;

    profiles.realProfiles().forEach((name, profile) -> {
      checkResourceProfile(name, profile.schema());
      for (final var segment : profile.segments()) {
        // An empty dynamics is a gap -- a legitimate "this resource has no value here", not a violation.
        segment.dynamics().ifPresentOrElse(
            d -> checkRealSegment(name, segment.extent().in(Duration.MICROSECONDS), d.initial, d.rate),
            () -> checkSegmentDuration(name, segment.extent().in(Duration.MICROSECONDS)));
      }
    });
    profiles.discreteProfiles().forEach((name, profile) -> {
      checkResourceProfile(name, profile.schema());
      for (final var segment : profile.segments()) {
        final var durationUs = segment.extent().in(Duration.MICROSECONDS);
        segment.dynamics().ifPresentOrElse(
            v -> checkDiscreteSegment(name, durationUs, v),
            () -> checkSegmentDuration(name, durationUs));
      }
    });

    for (final var span : spans) {
      checkSpan(
          span.spanId(),
          span.type(),
          span.startOffset().in(Duration.MICROSECONDS),
          // An unfinished span has no duration yet; that is a state, not a bad value.
          span.duration().map(d -> d.in(Duration.MICROSECONDS)).orElse(0L),
          span.arguments(),
          span.parentId().orElse(null),
          span.directiveId().orElse(null));
    }
  }

  // --- model type registration (push path) ------------------------------------------------------

  /**
   * The push counterpart: an adapter calling {@code registerModelTypes} declares the closed world rather
   * than being checked against it, so all we can check is that the names it declares are usable and that
   * required parameters actually exist.
   */
  public void checkDeclaredTypes(
      final Map<String, ActivityType> declaredActivityTypes, final Map<String, ValueSchema> declaredResourceTypes)
  {
    if (this.mode == Mode.OFF) return;
    for (final var name : declaredResourceTypes.keySet()) checkResourceName(name);
    for (final var entry : declaredActivityTypes.entrySet()) {
      final var type = entry.getValue();
      checkTypescriptIdentifier("activity type", entry.getKey());
      if (!entry.getKey().equals(type.name())) {
        finding("activity type keyed as '" + entry.getKey() + "' declares name '" + type.name() + "'");
      }
      final var declared = new HashSet<String>();
      for (final var p : type.parameters()) {
        checkTypescriptIdentifier("parameter name", p.name());
        if (!declared.add(p.name())) finding("activity type '" + type.name() + "' declares parameter '" + p.name() + "' twice");
      }
      for (final var required : type.requiredParameters()) {
        if (!declared.contains(required)) {
          finding("activity type '" + type.name() + "' requires parameter '" + required + "', which it does not declare");
        }
      }
    }
  }

  // --- conclusion -----------------------------------------------------------------------------

  /**
   * Run the whole-set structural checks and report. In {@code REJECT} mode this throws, so callers must
   * invoke it BEFORE committing anything: on the pull path the profiles are buffered until after the
   * response is fully parsed, which is exactly the window where aborting is still free.
   */
  public void finish() {
    if (this.mode == Mode.OFF) return;

    for (final var entry : this.spanParents.entrySet()) {
      if (!this.spanIds.contains(entry.getValue())) {
        finding("span " + entry.getKey() + " has parentId " + entry.getValue() + ", which is not a span in this result");
      }
    }
    // A cycle would make the span tree unrenderable and can hang naive consumers walking parents.
    for (final var start : this.spanParents.keySet()) {
      final var walked = new HashSet<Long>();
      var current = start;
      while (current != null && walked.add(current)) current = this.spanParents.get(current);
      if (current != null) {
        finding("span " + start + " is part of a parent cycle");
        break;
      }
    }

    if (this.findingCount == 0) {
      log.debug("external results gate: {} clean ({} spans, {} segments)", this.context, this.spanCount, this.segmentCount);
      return;
    }

    final var summary = "External simulation results failed %d closed-world check(s) for %s (%d spans, %d segments).%s"
        .formatted(
            this.findingCount, this.context, this.spanCount, this.segmentCount,
            this.findingCount > this.findings.size() ? " First " + this.findings.size() + " shown:" : "");
    if (this.mode == Mode.REJECT) {
      throw new RuntimeException(summary + "\n  " + String.join("\n  ", this.findings));
    }
    log.warn("{}\n  {}", summary, String.join("\n  ", this.findings));
  }

  private void finding(final String message) {
    this.findingCount++;
    if (this.findings.size() < MAX_REPORTED) this.findings.add(message);
  }

  /** For names that must survive as bare TypeScript identifiers -- activity types and parameters. */
  private void checkTypescriptIdentifier(final String kind, final String name) {
    if (name == null || !TS_IDENTIFIER.matcher(name).matches()) {
      finding(kind + " '" + name + "' is not a legal TypeScript identifier, so the generated constraint"
              + " and scheduling typings for this model will not compile");
    }
  }

  private void checkResourceName(final String name) {
    if (name == null || !RESOURCE_NAME.matcher(name).matches()) {
      finding("resource name '" + name + "' is empty, over 255 characters, or contains control characters");
    }
  }

  /**
   * Structural conformance of a value to a schema. Returns {@code null} when it conforms, otherwise a
   * message naming the first thing that did not. Deliberately structural only -- a schema carries no
   * nullability, so an explicit null is accepted everywhere rather than guessed at.
   */
  static String nonconformance(final SerializedValue value, final ValueSchema schema) {
    if (value == null || value.isNull()) return null;
    return schema.match(new ValueSchema.Visitor<String>() {
      @Override public String onReal() { return value.asReal().isPresent() ? null : wanted("real"); }
      @Override public String onInt() { return value.asInt().isPresent() ? null : wanted("int"); }
      @Override public String onBoolean() { return value.asBoolean().isPresent() ? null : wanted("boolean"); }
      @Override public String onString() { return value.asString().isPresent() ? null : wanted("string"); }
      @Override public String onPath() { return value.asString().isPresent() ? null : wanted("path"); }

      /** Durations cross the wire as whole microseconds, matching {@code Duration.MICROSECONDS}. */
      @Override public String onDuration() { return value.asInt().isPresent() ? null : wanted("duration (integer microseconds)"); }

      @Override public String onSeries(final ValueSchema element) {
        final var list = value.asList().orElse(null);
        if (list == null) return wanted("series");
        for (var i = 0; i < list.size(); i++) {
          final var problem = nonconformance(list.get(i), element);
          if (problem != null) return "at [" + i + "]: " + problem;
        }
        return null;
      }

      @Override public String onStruct(final Map<String, ValueSchema> fields) {
        final var map = value.asMap().orElse(null);
        if (map == null) return wanted("struct");
        for (final var field : fields.entrySet()) {
          final var sub = map.get(field.getKey());
          if (sub == null) return "is missing field '" + field.getKey() + "'";
          final var problem = nonconformance(sub, field.getValue());
          if (problem != null) return "at ." + field.getKey() + ": " + problem;
        }
        for (final var key : map.keySet()) {
          if (!fields.containsKey(key)) return "has unexpected field '" + key + "'";
        }
        return null;
      }

      @Override public String onVariant(final List<ValueSchema.Variant> variants) {
        final var actual = value.asString().orElse(null);
        if (actual == null) return wanted("variant (as a string)");
        for (final var variant : variants) {
          if (variant.key().equals(actual) || variant.label().equals(actual)) return null;
        }
        return "is '" + actual + "', which is not one of " + variants.stream().map(ValueSchema.Variant::key).toList();
      }

      @Override public String onMeta(final Map<String, SerializedValue> metadata, final ValueSchema target) {
        return nonconformance(value, target);
      }

      private String wanted(final String type) {
        return "is " + value + " where the schema says " + type;
      }
    });
  }
}
