package gov.nasa.ammos.aerie.pymerlin.shim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import gov.nasa.jpl.aerie.merlin.protocol.driver.CellId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Querier;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.CellType;
import gov.nasa.jpl.aerie.merlin.protocol.model.DirectiveType;
import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType;
import gov.nasa.jpl.aerie.merlin.protocol.model.ModelType;
import gov.nasa.jpl.aerie.merlin.protocol.model.OutputType;
import gov.nasa.jpl.aerie.merlin.protocol.model.Resource;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import org.graalvm.polyglot.Value;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.ask;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.emit;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.spawnWithSpan;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.callWithSpan;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.threaded;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.waitUntil;

/**
 * Generic Aerie ModelType implementation that delegates all model behaviour to Python,
 * running in-process via GraalPy ({@link GraalBridge}). Each activity runs on its own
 * Java {@code ThreadedTask} thread; delay/emit/spawn/call are driven by direct host
 * callbacks from Python into {@link PyActions} (roadmap §6) — there is no subprocess,
 * no wire protocol, and no drive loop interpreting yield responses.
 *
 * The model reference (e.g. "path/to/model.py:Mission") is read from the system
 * property {@code pymerlin.model.ref}, which is injected into the JAR manifest
 * by {@code pymerlin package} and set at startup.
 */
public final class ShimModelType implements ModelType<Map<String, SerializedValue>, Unit> {

    // --- per-resource cell bookkeeping ---
    // A cell is discrete (snapshots the last emit'd value, String-backed), linear
    // (continuously integrates value + rate·t, backed by RealDynamics — roadmap §7.2),
    // or evolving (autonomously stepped by a Python evolution function).
    private sealed interface Cell permits DiscreteCell, LinearCell, EvolvingCell {}

    private record DiscreteCell(
        Topic<String> topic,
        CellId<String[]> cellId,
        String valueType   // "float", "int", "bool", or "str"
    ) implements Cell {}

    private record LinearCell(
        Topic<LinearEffect> topic,
        CellId<double[]> cellId   // state is [value, rate]
    ) implements Cell {}

    /**
     * A discrete effect on a {@link LinearCell}. Either component is optional: a discrete
     * {@code emit} sets the value (keeping the rate), {@code set_rate} sets the rate
     * (keeping the ramped value). Last-writer-wins on composition, mirroring the discrete
     * String cell's trait — pymerlin runs one activity per cell per tick.
     */
    private record LinearEffect(Double newValue, Double newRate) {}

    /**
     * A cell whose value evolves autonomously via a Python evolution function.
     * State is held as a GraalPy {@link Value} (the raw
     * Python object), so {@code step()} can call the evolution function without
     * per-step serialization. Effects are Python objects too, so a value whose type has
     * no faithful string form (a {@code Duration}, say) survives a write unchanged.
     */
    private record EvolvingCell(
        // Effects carry the new value as a live Python object, not a string: an evolving
        // cell's type is arbitrary Python, and str() is lossy for some of it (a Duration
        // has no string form that parses back). String emits still work -- they are
        // converted on the way in, see directEmitCell.
        Topic<Value> topic,
        CellId<Value[]> cellId,
        Value evolutionFn,
        String valueType   // "float", "int", "bool", or "str"
    ) implements Cell {}

    private final Map<String, Cell> resourceCells = new HashMap<>();

    // Python _parse_value, retained from instantiate(): string emits targeting an evolving
    // cell must be converted to a typed Python object before becoming an effect.
    private Value parseValueFn;

    // Indexed cell list — Python CellRef references cells by integer index (Phase 4, §7).
    // Populated during instantiate(); order matches the order Python's registrar.cells sees them.
    private final List<Cell> cellsByIndex = new ArrayList<>();
    private final Map<String, Topic<Map<String, SerializedValue>>> inputTopics  = new HashMap<>();
    private final Map<String, Topic<Unit>>                         outputTopics = new HashMap<>();

    // --- per-parameter metadata ---
    private record ParamInfo(
        String name,
        ValueSchema schema,
        boolean required,
        SerializedValue defaultValue
    ) {}

    // Activity names populated either by instantiate() or by a one-shot query in getDirectiveTypes().
    private volatile Set<String> activityNames = null;

    // Per-activity ordered parameter metadata, populated alongside activityNames.
    private final Map<String, List<ParamInfo>> activityParams = new HashMap<>();

    // Model configuration parameter metadata (roadmap §7), populated by the one-shot
    // getConfigurationType() path or during instantiate(); null until first fetched.
    private volatile List<ParamInfo> configParams = null;

    private final AtomicLong activityCounter = new AtomicLong(0);

    // Bridge is shared across all activity executions for a given simulation.
    private volatile PyBridge bridge = null;

    // Host callback object handed to Python for every activity execution (roadmap §6).
    // Stateless (delegates to ModelActions on the calling ThreadedTask thread), so one
    // shared instance serves every activity.
    private final PyActions pyActions = new PyActions(this);

    // -----------------------------------------------------------------
    // ModelType interface
    // -----------------------------------------------------------------

    @Override
    public Map<String, ? extends DirectiveType<Unit, ?, ?>> getDirectiveTypes() {
        // Aerie may call this on a fresh instance (before instantiate()) to extract
        // activity type metadata for the DB. Start a one-shot bridge if needed.
        if (activityNames == null) {
            fetchActivityNames();
        }
        return buildDirectiveTypes();
    }

    private synchronized void fetchActivityNames() {
        if (activityNames != null) return; // double-checked
        String modelRef = resolveModelRef();
        try (PyBridge oneShot = PyBridge.create(modelRef)) {
            JsonObject types = oneShot.getActivityTypes();
            Set<String> names = new LinkedHashSet<>();
            if (types != null) {
                for (String name : types.keySet()) {
                    names.add(name);
                    activityParams.put(name, parseParams(types.getAsJsonObject(name)));
                }
            }
            activityNames = names;
        } catch (Exception e) {
            throw new RuntimeException("[PyMerlin] Could not fetch activity types: " + e.getMessage(), e);
        }
    }

    @Override
    public InputType<Map<String, SerializedValue>> getConfigurationType() {
        // Aerie may call this on a fresh instance (before instantiate()) to store the
        // configuration schema. Fetch config params via a one-shot bridge if needed.
        if (configParams == null) {
            fetchConfigParams();
        }
        return configInputType();
    }

    private synchronized void fetchConfigParams() {
        if (configParams != null) return; // double-checked
        String modelRef = resolveModelRef();
        try (PyBridge oneShot = PyBridge.create(modelRef)) {
            configParams = parseParams(oneShot.getConfigParameters());
        } catch (Exception e) {
            throw new RuntimeException("[PyMerlin] Could not fetch configuration parameters: " + e.getMessage(), e);
        }
    }

    @Override
    public Unit instantiate(Instant planStart, Map<String, SerializedValue> configuration, Initializer builder) {
        String modelRef = resolveModelRef();
        try {
            bridge = PyBridge.create(modelRef);
            // Config must be set before any query that builds the Python model state
            // (getCells below triggers it). Serialize the instantiated config to JSON.
            bridge.setConfiguration(configToJson(configuration));
        } catch (Exception e) {
            throw new RuntimeException("[PyMerlin] Failed to start bridge: " + e.getMessage(), e);
        }

        // Populate config param metadata from the live bridge (avoids a second startup).
        try {
            configParams = parseParams(bridge.getConfigParameters());
        } catch (Exception e) {
            throw new RuntimeException("[PyMerlin] get_config failed: " + e.getMessage(), e);
        }

        // Populate activityNames from the live bridge (avoids a second startup).
        try {
            JsonObject types = bridge.getActivityTypes();
            Set<String> names = new LinkedHashSet<>();
            if (types != null) {
                for (String name : types.keySet()) {
                    names.add(name);
                    activityParams.put(name, parseParams(types.getAsJsonObject(name)));
                    Topic<Map<String, SerializedValue>> inputTopic  = new Topic<>();
                    Topic<Unit>                         outputTopic = new Topic<>();
                    inputTopics.put(name, inputTopic);
                    outputTopics.put(name, outputTopic);
                    builder.topic("ActivityType.Input."  + name, inputTopic,  passthroughOutputType());
                    builder.topic("ActivityType.Output." + name, outputTopic, unitOutputType());
                }
            }
            activityNames = names;
        } catch (Exception e) {
            throw new RuntimeException("[PyMerlin] get_activity_types failed: " + e.getMessage(), e);
        }

        // Phase 4 (§7): allocate a real Aerie cell for every Python cell.
        // Cell indices match the Python registrar.cells order so CellRef._cell_index
        // on the Python side maps directly to cellsByIndex on the Java side.
        try {
            JsonArray cells = bridge.getCells();
            List<Value> evolutionFns = bridge.getEvolutionFunctions();
            List<Value> initialValues = bridge.getInitialValues();
            List<Value> resourceProjections = bridge.getResourceProjections();
            Value parseValueFn = bridge.getParseValueFn();
            this.parseValueFn = parseValueFn;

            if (cells != null) {
                for (int i = 0; i < cells.size(); i++) {
                    JsonObject cellMeta = cells.get(i).getAsJsonObject();
                    String initialValue = cellMeta.has("initial") ? cellMeta.get("initial").getAsString() : "";
                    String vtype = cellMeta.has("type") ? cellMeta.get("type").getAsString() : "str";
                    String resName = (cellMeta.has("resource") && !cellMeta.get("resource").isJsonNull())
                        ? cellMeta.get("resource").getAsString() : null;
                    boolean evolving = cellMeta.has("evolving") && cellMeta.get("evolving").getAsBoolean();
                    Value evolutionFn = (evolving && i < evolutionFns.size()) ? evolutionFns.get(i) : null;

                    if ("linear".equals(vtype)) {
                        // Continuously-integrating cell (roadmap §7.2): value ramps by `rate`
                        // per second between events, exposed as an Aerie RealDynamics resource.
                        double initial = parseDoubleOr(initialValue, 0.0);
                        double rate = cellMeta.has("rate") ? parseDoubleOr(cellMeta.get("rate").getAsString(), 0.0) : 0.0;
                        // Absent bounds mean unbounded -- only clamp what the model asked to clamp.
                        Double minimum = cellMeta.has("minimum") && !cellMeta.get("minimum").isJsonNull()
                            ? parseDoubleOr(cellMeta.get("minimum").getAsString(), Double.NEGATIVE_INFINITY) : null;
                        Double maximum = cellMeta.has("maximum") && !cellMeta.get("maximum").isJsonNull()
                            ? parseDoubleOr(cellMeta.get("maximum").getAsString(), Double.POSITIVE_INFINITY) : null;
                        Topic<LinearEffect> topic = new Topic<>();
                        CellId<double[]> cellId = allocateLinearCell(builder, initial, rate, minimum, maximum, topic);
                        LinearCell lc = new LinearCell(topic, cellId);
                        cellsByIndex.add(lc);

                        if (resName != null) {
                            resourceCells.put(resName, lc);
                            final CellId<double[]> capturedCell = cellId;
                            final Double capturedMin = minimum;
                            final Double capturedMax = maximum;
                            builder.resource(resName, new Resource<RealDynamics>() {
                                @Override public String getType() { return "real"; }
                                @Override public OutputType<RealDynamics> getOutputType() { return realOutputType(); }
                                @Override public RealDynamics getDynamics(Querier q) {
                                    double[] s = q.getState(capturedCell);
                                    double value = s[0];
                                    double slope = s[1];
                                    // Report a flat profile once pinned at a bound. RealDynamics
                                    // is extrapolated across the segment, so a battery sitting at
                                    // 100% with a positive rate would otherwise be DRAWN climbing
                                    // past 100 even though step() clamps the stored value.
                                    //
                                    // The epsilon matters: getExpiry schedules the crossing, and
                                    // the value at that instant can land a hair under the bound
                                    // through floating-point error. An exact `>=` would then miss,
                                    // leaving a segment that ramps visibly past the bound -- the
                                    // whole bug this guards against.
                                    final double eps = 1e-9;
                                    if (capturedMax != null && value >= capturedMax - eps && slope > 0) slope = 0.0;
                                    if (capturedMin != null && value <= capturedMin + eps && slope < 0) slope = 0.0;
                                    return RealDynamics.linear(value, slope);
                                }
                            });
                        }
                        continue;
                    }

                    // Cell-evolution roadmap: cells with a Python evolution function get an
                    // EvolvingCell whose step() calls the function on every time advance.
                    if (evolutionFn != null) {
                        Topic<Value> topic = new Topic<>();
                        // Take the initial value as the live Python object. It must NOT be
                        // rebuilt from cellMeta's `initial` string: that is str(value), and
                        // _parse_value dispatches on its `reference` argument's runtime type,
                        // so passing the string as its own reference falls through to the
                        // str branch and stringifies the cell. A tuple-valued cell then hands
                        // its evolution function "(0.0, 0.0)" instead of (0.0, 0.0) and fails
                        // on the first step() with "too many values to unpack".
                        Value pyInitial = (i < initialValues.size()) ? initialValues.get(i) : null;
                        if (pyInitial == null || pyInitial.isNull()) {
                            pyInitial = parseValueFn.execute(initialValue, initialValue);
                        }
                        // Optional re-sampling interval, driving CellType.getExpiry. Absent
                        // means the stepped value never expires, so the engine only samples
                        // the cell when something reads it -- correct for evolution that is
                        // linear in time, but it renders nonlinear evolution as one cliff
                        // spanning whatever gap sat between two reads.
                        Duration resolution = null;
                        if (cellMeta.has("resolution_micros") && !cellMeta.get("resolution_micros").isJsonNull()) {
                            try {
                                resolution = Duration.of(
                                    Long.parseLong(cellMeta.get("resolution_micros").getAsString()),
                                    Duration.MICROSECONDS);
                            } catch (NumberFormatException e) {
                                System.err.println("[PyMerlin] bad resolution_micros for cell " + i
                                    + ": " + cellMeta.get("resolution_micros"));
                            }
                        }
                        CellId<Value[]> cellId = allocateEvolvingCell(
                            builder, pyInitial, evolutionFn, parseValueFn, resolution, topic);
                        EvolvingCell ec = new EvolvingCell(topic, cellId, evolutionFn, vtype);
                        cellsByIndex.add(ec);

                        if (resName != null) {
                            resourceCells.put(resName, ec);
                            final CellId<Value[]> capturedCell = cellId;
                            final String capturedVtype = vtype;
                            // Projection turning the raw cell value into the published
                            // resource value (e.g. (temp, heat) -> temp). Null means the
                            // resource is the raw value itself.
                            final Value projection = (i < resourceProjections.size())
                                ? resourceProjections.get(i) : null;
                            final String cellDynamics = (cellMeta.has("dynamics") && !cellMeta.get("dynamics").isJsonNull())
                                ? cellMeta.get("dynamics").getAsString() : "discrete";
                            if ("real".equals(cellDynamics)) {
                                // Real evolving resource (roadmap §3.2, Option A):
                                // each segment carries {initial, rate} where rate is the
                                // secant over one resolution interval:
                                //   slope = (value_at(t + r) - value_at(t)) / r_seconds
                                // Requires resolution (validated Python-side in Step 1).
                                final Value capturedEvolutionFn = evolutionFn;
                                final long capturedResolutionMicros = resolution != null
                                    ? resolution.in(Duration.MICROSECONDS) : 0L;
                                final double capturedResolutionSeconds = capturedResolutionMicros / 1_000_000.0;
                                final String capturedResName = resName;
                                builder.resource(resName, new Resource<RealDynamics>() {
                                    @Override public String getType() { return "real"; }
                                    @Override public OutputType<RealDynamics> getOutputType() { return realOutputType(); }
                                    @Override public RealDynamics getDynamics(Querier q) {
                                        Value raw = q.getState(capturedCell)[0];
                                        // Project the raw cell value to a double for 'now'.
                                        double now = toDouble(raw, projection, capturedResName, "value");
                                        // Lookahead: call the evolution function one resolution
                                        // interval ahead to get the far end of the secant.
                                        // This is a pure read — no mutation of cell state.
                                        final Value ahead;
                                        try {
                                            ahead = capturedEvolutionFn.execute(raw, capturedResolutionMicros);
                                        } catch (RuntimeException e) {
                                            throw new RuntimeException(
                                                "Resource '" + capturedResName + "' (dynamics='real'): evolution "
                                                + "function raised during slope lookahead: " + e.getMessage()
                                                + " (raw cell value: " + raw + ")", e);
                                        }
                                        double next = toDouble(ahead, projection, capturedResName, "lookahead");
                                        double slope = capturedResolutionSeconds > 0.0
                                            ? (next - now) / capturedResolutionSeconds : 0.0;
                                        return RealDynamics.linear(now, slope);
                                    }
                                });
                            } else {
                                builder.resource(resName, new Resource<String>() {
                                    @Override public String getType() { return "discrete"; }
                                    @Override public OutputType<String> getOutputType() { return typedOutputType(capturedVtype); }
                                    @Override public String getDynamics(Querier q) {
                                        Value raw = q.getState(capturedCell)[0];
                                        if (projection != null) {
                                            return projection.execute(raw).asString();
                                        }
                                        return raw.toString();
                                    }
                                });
                            }
                        }
                        continue;
                    }

                    Topic<String> topic = new Topic<>();
                    CellId<String[]> cellId = allocateStringCell(builder, initialValue, topic);
                    DiscreteCell rc = new DiscreteCell(topic, cellId, vtype);
                    cellsByIndex.add(rc);

                    if (resName != null) {
                        resourceCells.put(resName, rc);
                        final String capturedName = resName;
                        final CellId<String[]> capturedCell = cellId;
                        final String capturedVtype = vtype;
                        builder.resource(capturedName, new Resource<String>() {
                            @Override public String getType() { return "discrete"; }
                            @Override public OutputType<String> getOutputType() { return typedOutputType(capturedVtype); }
                            @Override public String getDynamics(Querier q) {
                                return q.getState(capturedCell)[0];
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("[PyMerlin] getCells failed: " + e.getMessage(), e);
        }

        return Unit.UNIT;
    }

    // -----------------------------------------------------------------
    // Model ref resolution
    // -----------------------------------------------------------------

    private static String resolveModelRef() {
        // 1. Check system property (set by tests or external tooling)
        String sysProp = System.getProperty("pymerlin.model.ref");
        if (sysProp != null && !sysProp.isBlank()) return sysProp;

        // 2. Read from the JAR that physically contains ShimModelType.
        //    We cannot use getClassLoader().getResource("META-INF/MANIFEST.MF")
        //    because the parent classloader's MANIFEST.MF would be found first.
        try {
            URL jarUrl = ShimModelType.class.getProtectionDomain().getCodeSource().getLocation();
            if (jarUrl != null) {
                // Build a jar: URL to access the manifest entry directly
                URL manifestUrl = new URL("jar:" + jarUrl.toExternalForm() + "!/META-INF/MANIFEST.MF");
                try (InputStream is = manifestUrl.openStream()) {
                    Manifest mf = new Manifest(is);
                    String ref = mf.getMainAttributes().getValue("Pymerlin-Model-Ref");
                    if (ref != null && !ref.isBlank()) {
                        return extractIfBundled(ref.trim());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[PyMerlin] Could not read manifest: " + e.getMessage());
        }

        throw new RuntimeException("[PyMerlin] No Pymerlin-Model-Ref found in JAR manifest. " +
            "Did you build the JAR with 'pymerlin package'?");
    }

    /**
     * If the model ref points to a bundled resource (pymerlin_models/...), extract
     * the file (or entire package directory) to a temp directory and return a ref
     * pointing at the extracted path.
     *
     * Single-file:  pymerlin_models/model.py:ClassName
     * Package dir:  pymerlin_models/mypkg/model.py:ClassName
     */
    private static String extractIfBundled(String ref) throws IOException {
        if (!ref.contains(":")) return ref;
        String[] parts = ref.split(":", 2);
        String resourcePath = parts[0];   // e.g. pymerlin_models/mypkg/model.py
        String className    = parts[1];

        // Check whether the resource is actually bundled in our JAR.
        URL resource = ShimModelType.class.getClassLoader().getResource(resourcePath);
        if (resource == null) {
            // Not bundled — treat as a filesystem path
            return ref;
        }

        String simId = UUID.randomUUID().toString().substring(0, 8);
        Path tmpDir = Files.createTempDirectory("pymerlin-model-" + simId + "-");

        // Determine if this is a package (resourcePath has >2 segments, i.e. pymerlin_models/<pkg>/<file>)
        Path rp = Path.of(resourcePath);
        boolean isPackage = rp.getNameCount() >= 3; // pymerlin_models / pkg / file.py

        if (isPackage) {
            // Extract every resource under pymerlin_models/<pkg>/ from the JAR.
            String pkgPrefix = rp.getParent().toString().replace('\\', '/') + "/";
            String pkgName   = rp.getParent().getFileName().toString();
            Path pkgDest     = tmpDir.resolve(pkgName);
            Files.createDirectories(pkgDest);

            // Walk the JAR entries via the jar: URL protocol.
            URL jarUrl = ShimModelType.class.getProtectionDomain().getCodeSource().getLocation();
            JarURLConnection conn = (JarURLConnection) new URL("jar:" + jarUrl.toExternalForm() + "!/").openConnection();
            try (JarFile jar = conn.getJarFile()) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.startsWith(pkgPrefix) || entry.isDirectory()) continue;
                    String relative = name.substring(pkgPrefix.length()); // e.g. "model.py" or "sub/foo.py"
                    Path dest = pkgDest.resolve(relative);
                    Files.createDirectories(dest.getParent());
                    try (InputStream is = jar.getInputStream(entry)) {
                        Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            Path modelFile = pkgDest.resolve(rp.getFileName().toString());
            System.out.println("[PyMerlin] Extracted bundled package to: " + pkgDest);
            return modelFile.toAbsolutePath() + ":" + className;
        } else {
            // Single file: extract just that one resource.
            String fileName = rp.getFileName().toString();
            Path dest = tmpDir.resolve(fileName);
            try (InputStream is = resource.openStream()) {
                Files.copy(is, dest);
            }
            System.out.println("[PyMerlin] Extracted bundled model to: " + dest);
            return dest.toAbsolutePath() + ":" + className;
        }
    }

    // -----------------------------------------------------------------
    // Directive types — built after instantiate() populates inputTopics
    // -----------------------------------------------------------------

    private Map<String, DirectiveType<Unit, Map<String, SerializedValue>, Unit>> buildDirectiveTypes() {
        Map<String, DirectiveType<Unit, Map<String, SerializedValue>, Unit>> result = new HashMap<>();
        Set<String> names = activityNames != null ? activityNames : Set.of();
        for (String name : names) {
            final String activityName = name;
            result.put(name, new DirectiveType<>() {
                @Override
                public InputType<Map<String, SerializedValue>> getInputType() {
                    return activityInputType(activityName);
                }

                @Override
                public OutputType<Unit> getOutputType() {
                    return unitOutputType();
                }

                @Override
                public TaskFactory<Unit> getTaskFactory(Unit model, Map<String, SerializedValue> args) {
                    return threaded(() -> runActivity(activityName, args));
                }
            });
        }
        return result;
    }

    // -----------------------------------------------------------------
    // Activity execution loop
    // -----------------------------------------------------------------

    private Unit runActivity(String activityName, Map<String, SerializedValue> args) {
        String actId = "act-" + activityCounter.incrementAndGet();

        // Serialize args to native JSON types so both bridges receive the correct types
        Map<String, JsonElement> argsJson = new LinkedHashMap<>();
        for (Map.Entry<String, SerializedValue> e : args.entrySet()) {
            argsJson.put(e.getKey(), serializedValueToJson(e.getValue()));
        }

        try {
            emit(args, inputTopics.get(activityName));
            // Runs the Python function directly on this ThreadedTask thread (roadmap §6).
            // delay/emit/spawn/call happen via pyActions callbacks; returns when done.
            bridge.runActivityDirect(actId, activityName, argsJson, pyActions);
            emit(Unit.UNIT, outputTopics.get(activityName));
        } catch (Exception e) {
            throw new RuntimeException("[PyMerlin] Activity " + activityName + " failed: " + e.getMessage(), e);
        }
        return Unit.UNIT;
    }

    // -----------------------------------------------------------------
    // Direct-call callbacks (Phase 3, §6) — invoked from Python via PyActions,
    // synchronously, on the ThreadedTask thread currently running the activity.
    // -----------------------------------------------------------------

    void directDelay(long micros) {
        delay(Duration.of(micros, Duration.MICROSECONDS));
    }

    void directSpawn(String name, String argsJson) {
        Map<String, SerializedValue> args = parseChildArgs(argsJson);
        spawnWithSpan(threaded(() -> runActivity(name, args)));
    }

    void directCall(String name, String argsJson) {
        Map<String, SerializedValue> args = parseChildArgs(argsJson);
        callWithSpan(threaded(() -> runActivity(name, args)));
    }

    /** Parse the JSON args string PyActions hands over (from Python's _child_args_json). */
    private static Map<String, SerializedValue> parseChildArgs(String argsJson) {
        Map<String, SerializedValue> result = new LinkedHashMap<>();
        if (argsJson == null || argsJson.isBlank()) return result;
        JsonObject obj = com.google.gson.JsonParser.parseString(argsJson).getAsJsonObject();
        for (String key : obj.keySet()) {
            result.put(key, jsonToSerializedValue(obj.get(key)));
        }
        return result;
    }

    void applyEmit(String resourceName, String value) {
        Cell cell = resourceCells.get(resourceName);
        if (cell instanceof DiscreteCell dc) {
            emit(value, dc.topic());
        } else if (cell instanceof LinearCell lc) {
            emit(new LinearEffect(parseDoubleOr(value, 0.0), null), lc.topic());
        } else if (cell instanceof EvolvingCell ec) {
            emit(toEvolvingValue(ec, value), ec.topic());
        } else {
            System.err.println("[PyMerlin] emit for unknown resource: " + resourceName);
        }
    }

    /**
     * Convert a string emit into the typed Python object an evolving cell stores, using the
     * cell's current value as the type reference {@code _parse_value} dispatches on.
     */
    private Value toEvolvingValue(EvolvingCell ec, String value) {
        return parseValueFn.execute(value, ask(ec.cellId())[0]);
    }

    // -----------------------------------------------------------------
    // Phase 4 (roadmap §7) — cell-index-based access from Python CellRef
    // -----------------------------------------------------------------

    String directAsk(int cellIndex) {
        Cell cell = cellsByIndex.get(cellIndex);
        if (cell instanceof LinearCell lc) {
            // Value at the current instant — the engine has already stepped the cell to now.
            return Double.toString(ask(lc.cellId())[0]);
        }
        if (cell instanceof EvolvingCell ec) {
            // The engine has already stepped the cell to now via the evolution function.
            // Return the Python object's string representation.
            Value pyObj = ask(ec.cellId())[0];
            return pyObj.toString();
        }
        return ask(((DiscreteCell) cell).cellId())[0];
    }

    /**
     * Read an evolving cell as the live Python object, bypassing the string round-trip
     * {@link #directAsk} performs. Returns null for any other cell kind.
     * <p>
     * Needed because an evolving cell's value type is arbitrary Python: a Duration-valued
     * cell (pymerlin.clock) stringifies to "+00:00:00.0000.0", and no parse on the Python
     * side recovers a Duration from that. Handing back the object sidesteps the problem
     * rather than adding a parser per type.
     */
    Object directAskObject(int cellIndex) {
        Cell cell = cellsByIndex.get(cellIndex);
        if (cell instanceof EvolvingCell ec) {
            return ask(ec.cellId())[0];
        }
        return null;
    }

    void directEmitCell(int cellIndex, String value) {
        Cell cell = cellsByIndex.get(cellIndex);
        if (cell instanceof LinearCell lc) {
            // Discrete jump of the integrated value (rate unchanged).
            emit(new LinearEffect(parseDoubleOr(value, 0.0), null), lc.topic());
        } else if (cell instanceof EvolvingCell ec) {
            emit(toEvolvingValue(ec, value), ec.topic());
        } else {
            emit(value, ((DiscreteCell) cell).topic());
        }
    }

    /**
     * Write an evolving cell from a live Python object, skipping the string conversion
     * {@link #directEmitCell} performs. Counterpart to {@link #directAskObject}.
     */
    void directEmitCellObject(int cellIndex, Value value) {
        Cell cell = cellsByIndex.get(cellIndex);
        if (cell instanceof EvolvingCell ec) {
            emit(value, ec.topic());
        } else {
            // Not an evolving cell -- fall back to the string path so the write is not lost.
            directEmitCell(cellIndex, value == null ? "" : value.toString());
        }
    }

    void directSetRate(int cellIndex, double rate) {
        Cell cell = cellsByIndex.get(cellIndex);
        if (cell instanceof LinearCell lc) {
            emit(new LinearEffect(null, rate), lc.topic());
        } else {
            System.err.println("[PyMerlin] set_rate on non-linear cell index: " + cellIndex);
        }
    }

    void directWaitUntil(BooleanSupplier pyCondition) {
        waitUntil((positive, atEarliest, atLatest) ->
            (pyCondition.getAsBoolean() == positive)
                ? Optional.of(atEarliest) : Optional.empty());
    }

    // -----------------------------------------------------------------
    // Cell allocation
    // -----------------------------------------------------------------

    /**
     * Allocate a continuously-integrating cell (roadmap §7.2). State is {@code [value, rate]};
     * {@code step} advances {@code value += rate · elapsedSeconds} before any query, exactly
     * like {@code contrib}'s {@code LinearIntegrationCell}, so a {@code RealDynamics} resource
     * reading this cell ramps smoothly between discrete effects instead of stepping.
     */
    /** Clamp {@code v} into [minimum, maximum]; either bound may be null (unbounded). */
    private static double clampTo(double v, Double minimum, Double maximum) {
        if (minimum != null && v < minimum) return minimum;
        if (maximum != null && v > maximum) return maximum;
        return v;
    }

    /**
     * Allocate a continuously-integrating cell, optionally bounded.
     * <p>
     * {@code minimum}/{@code maximum} may be null (unbounded). When set, they clamp both the
     * integrated value and discrete jumps, mirroring Aerie's {@code ClampedIntegrator}: a
     * physically bounded quantity — battery charge, a tank, a buffer — otherwise integrates
     * straight past its limit and a battery at 100% keeps charging to 130%.
     */
    private static CellId<double[]> allocateLinearCell(
            Initializer builder, double initial, double rate,
            Double minimum, Double maximum, Topic<LinearEffect> topic) {
        final double initialClamped = clampTo(initial, minimum, maximum);
        return builder.allocate(
            new double[]{initialClamped, rate},
            new CellType<LinearEffect, double[]>() {
                @Override
                public EffectTrait<LinearEffect> getEffectType() {
                    return new EffectTrait<>() {
                        @Override public LinearEffect empty() { return new LinearEffect(null, null); }
                        @Override public LinearEffect sequentially(LinearEffect a, LinearEffect b) { return combine(a, b); }
                        @Override public LinearEffect concurrently(LinearEffect a, LinearEffect b) { return combine(a, b); }
                        private LinearEffect combine(LinearEffect a, LinearEffect b) {
                            return new LinearEffect(
                                b.newValue() != null ? b.newValue() : a.newValue(),
                                b.newRate()  != null ? b.newRate()  : a.newRate());
                        }
                    };
                }
                @Override public void apply(double[] state, LinearEffect effect) {
                    // Clamp discrete jumps too, not just integration -- an emit past the
                    // bound would otherwise park the value out of range until the next step.
                    if (effect.newValue() != null) state[0] = clampTo(effect.newValue(), minimum, maximum);
                    if (effect.newRate()  != null) state[1] = effect.newRate();
                }
                @Override public double[] duplicate(double[] state) { return new double[]{state[0], state[1]}; }
                @Override public void step(double[] state, Duration elapsed) {
                    state[0] = clampTo(
                        state[0] + state[1] * elapsed.ratioOver(Duration.SECOND),
                        minimum, maximum);
                }
                /**
                 * Expire when the ramp is due to hit a bound, so the engine cuts a segment
                 * there.
                 * <p>
                 * Clamping the stored value in {@code step()} is not sufficient on its own:
                 * a real profile segment is {@code {initial, rate}} and gets EXTRAPOLATED
                 * across its whole extent. A battery at 99.58% charging into a segment with
                 * no other event in it is drawn climbing straight past 100 to wherever the
                 * slope ends up, even though the stored value stopped at 100. Expiring at
                 * the crossing splits that into a rising segment that ends exactly at the
                 * bound and a flat one after it.
                 */
                @Override public Optional<Duration> getExpiry(double[] state) {
                    double value = state[0];
                    double rate  = state[1];
                    if (rate == 0.0) return Optional.empty();
                    Double bound = rate > 0 ? maximum : minimum;
                    if (bound == null) return Optional.empty();
                    double secondsToBound = (bound - value) / rate;
                    // Already at or past the bound: step() pins the value and getDynamics
                    // reports a flat slope, so there is nothing further to schedule.
                    if (secondsToBound <= 0.0) return Optional.empty();
                    // Round UP, at microsecond granularity: expiring even slightly early
                    // leaves the engine re-querying just short of the bound and scheduling
                    // the same crossing again, and rounding to a coarser unit would leave a
                    // visible sliver of overshoot in the segment before the flat one.
                    double microsToBound = secondsToBound * 1_000_000.0;
                    if (microsToBound > (double) Long.MAX_VALUE) return Optional.empty();
                    return Optional.of(Duration.of((long) Math.ceil(microsToBound), Duration.MICROSECONDS));
                }
            },
            e -> e,
            topic
        );
    }

    private static double parseDoubleOr(String s, double fallback) {
        if (s == null) return fallback;
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    /**
     * Convert a GraalPy {@link Value} (optionally projected) to a Java {@code double}.
     * Used by the real evolving-cell {@code getDynamics} to extract the numeric value
     * for both the current and lookahead samples.
     *
     * Throws rather than returning a sentinel on failure. A non-numeric value here used
     * to become {@code Double.NaN}, which is silently accepted by RealDynamics and only
     * surfaces much later inside {@code SerializedValue.of} as
     * "Character N is neither a decimal digit number..." -- a BigDecimal parse error with
     * no mention of the model, the cell, or the value that caused it. Failing at the point
     * of conversion keeps the resource name and the offending value in the message.
     *
     * @param raw        The raw cell value (live Python object).
     * @param projection A Python callable {@code raw -> published_value}, or {@code null}
     *                   when the resource IS the raw value (no {@code .map(fn)}).
     * @param resName    Resource name, for diagnostics only.
     * @param what       Which sample this is ("value" or "lookahead"), for diagnostics only.
     */
    private static double toDouble(Value raw, Value projection, String resName, String what) {
        final Value target;
        try {
            target = (projection != null) ? projection.execute(raw) : raw;
        } catch (RuntimeException e) {
            // A projection or evolution function that raises reaches us as a PolyglotException.
            throw new RuntimeException(
                "Resource '" + resName + "' (dynamics='real'): " + what
                + " projection raised " + e.getClass().getSimpleName() + ": " + e.getMessage()
                + " (raw cell value: " + raw + ")", e);
        }
        if (target.isNumber()) return target.asDouble();
        // Projections come back as strings (_wrap_projection stringifies), so a numeric
        // repr is still valid here -- only a genuinely unparseable value is an error.
        String s = target.toString();
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) {
            throw new RuntimeException(
                "Resource '" + resName + "' (dynamics='real') produced a non-numeric "
                + what + ": " + s + " -- dynamics='real' requires a number. Either return "
                + "a float from the evolution function, or add a .map(fn) projection that "
                + "selects a numeric element, or use dynamics='discrete'.", e);
        }
    }

    /**
     * Allocate an evolving cell. State is {@code Value[1]} holding
     * the Python object. {@code step()} calls the Python evolution function with
     * {@code (currentValue, elapsedMicros)} and stores the result; the wrapper in
     * {@code _server.py} converts microseconds to a {@code pymerlin.duration.Duration}
     * before calling the user's function.
     */
    private static CellId<Value[]> allocateEvolvingCell(
            Initializer builder, Value initialValue, Value evolutionFn,
            Value parseValueFn, Duration resolution, Topic<Value> topic) {
        return builder.allocate(
            new Value[]{initialValue},
            new CellType<Value, Value[]>() {
                @Override
                public EffectTrait<Value> getEffectType() {
                    return new EffectTrait<>() {
                        @Override public Value empty()                        { return null; }
                        @Override public Value sequentially(Value a, Value b) { return b != null ? b : a; }
                        @Override public Value concurrently(Value a, Value b) { return b != null ? b : a; }
                    };
                }
                @Override public void apply(Value[] state, Value effect) {
                    if (effect != null) {
                        // Already a Python object -- directEmitCell converts string emits
                        // before they reach here, so no parsing is needed at this point.
                        state[0] = effect;
                    }
                }
                @Override public Value[] duplicate(Value[] state) { return new Value[]{state[0]}; }
                @Override public void step(Value[] state, Duration elapsed) {
                    long micros = elapsed.in(Duration.MICROSECONDS);
                    state[0] = evolutionFn.execute(state[0], micros);
                }
                /**
                 * How long this stepped value stays valid. Aerie samples a discrete resource
                 * only when the cell is queried, so without an expiry a span of simulation
                 * with no reads becomes a single profile segment holding just its endpoint --
                 * an exponential decay renders as one cliff instead of a curve. Declaring a
                 * resolution makes the engine re-query at that cadence.
                 *
                 * Empty (the default) is correct for evolution that is linear in time, where
                 * intermediate samples add nothing to the profile.
                 */
                @Override public Optional<Duration> getExpiry(Value[] state) {
                    return Optional.ofNullable(resolution);
                }
            },
            s -> s,
            topic
        );
    }

    private static CellId<String[]> allocateStringCell(Initializer builder, String initial, Topic<String> topic) {
        return builder.allocate(
            new String[]{initial},
            new CellType<String, String[]>() {
                @Override
                public EffectTrait<String> getEffectType() {
                    return new EffectTrait<>() {
                        @Override public String empty()                             { return null; }
                        @Override public String sequentially(String a, String b)   { return b != null ? b : a; }
                        @Override public String concurrently(String a, String b)   { return b != null ? b : a; }
                    };
                }
                @Override public void apply(String[] state, String effect) { if (effect != null) state[0] = effect; }
                @Override public String[] duplicate(String[] state)        { return new String[]{state[0]}; }
            },
            s -> s,
            topic
        );
    }

    // -----------------------------------------------------------------
    // Type stubs
    // -----------------------------------------------------------------

    private static String serializedValueToString(SerializedValue sv) {
        // Best-effort: extract the primitive value as a string
        return sv.match(new SerializedValue.Visitor<>() {
            @Override public String onNull()              { return "null"; }
            @Override public String onBoolean(boolean v)  { return Boolean.toString(v); }
            @Override public String onNumeric(BigDecimal v) { return v.toPlainString(); }
            @Override public String onString(String v)    { return v; }
            @Override public String onMap(Map<String, SerializedValue> m) { return m.toString(); }
            @Override public String onList(List<SerializedValue> l)       { return l.toString(); }
        });
    }

    private static SerializedValue coerceToSchema(SerializedValue val, ValueSchema schema) {
        String raw = serializedValueToString(val);
        if (schema == ValueSchema.REAL) {
            try { return SerializedValue.of(Double.parseDouble(raw)); }
            catch (NumberFormatException e) { return val; }
        } else if (schema == ValueSchema.INT) {
            try { return SerializedValue.of(Long.parseLong(raw)); }
            catch (NumberFormatException e) {
                try { return SerializedValue.of((long) Double.parseDouble(raw)); }
                catch (NumberFormatException e2) { return val; }
            }
        } else if (schema == ValueSchema.BOOLEAN) {
            return SerializedValue.of(Boolean.parseBoolean(raw));
        }
        return val; // STRING and others pass through unchanged
    }

    private static JsonElement serializedValueToJson(SerializedValue sv) {
        return sv.match(new SerializedValue.Visitor<JsonElement>() {
            @Override public JsonElement onNull()             { return JsonNull.INSTANCE; }
            @Override public JsonElement onBoolean(boolean v) { return new JsonPrimitive(v); }
            @Override public JsonElement onNumeric(BigDecimal v) { return new JsonPrimitive(v); }
            @Override public JsonElement onString(String v)   { return new JsonPrimitive(v); }
            @Override public JsonElement onMap(Map<String, SerializedValue> m) {
                JsonObject obj = new JsonObject();
                for (var entry : m.entrySet()) obj.add(entry.getKey(), serializedValueToJson(entry.getValue()));
                return obj;
            }
            @Override public JsonElement onList(List<SerializedValue> l) {
                JsonArray arr = new JsonArray();
                for (var item : l) arr.add(serializedValueToJson(item));
                return arr;
            }
        });
    }

    /** Serialize an instantiated configuration to a JSON object string for Python. */
    private static String configToJson(Map<String, SerializedValue> config) {
        JsonObject obj = new JsonObject();
        if (config != null) {
            for (Map.Entry<String, SerializedValue> e : config.entrySet()) {
                obj.add(e.getKey(), serializedValueToJson(e.getValue()));
            }
        }
        return obj.toString();
    }

    /**
     * The model configuration InputType (roadmap §7). Structurally identical to
     * {@link #activityInputType}, but backed by {@link #configParams} — the model
     * constructor's post-registrar parameters — instead of a named activity's parameters.
     */
    private InputType<Map<String, SerializedValue>> configInputType() {
        return new InputType<>() {
            private List<ParamInfo> params() {
                return configParams != null ? configParams : List.of();
            }

            @Override
            public List<InputType.Parameter> getParameters() {
                List<InputType.Parameter> result = new ArrayList<>();
                for (ParamInfo p : params()) result.add(new InputType.Parameter(p.name(), p.schema()));
                return result;
            }

            @Override
            public List<String> getRequiredParameters() {
                List<String> result = new ArrayList<>();
                for (ParamInfo p : params()) if (p.required()) result.add(p.name());
                return result;
            }

            @Override
            public Map<String, SerializedValue> instantiate(Map<String, SerializedValue> args) {
                Map<String, SerializedValue> merged = new LinkedHashMap<>();
                for (ParamInfo p : params()) {
                    SerializedValue val = args.containsKey(p.name()) ? args.get(p.name())
                                       : p.defaultValue();
                    if (val != null) merged.put(p.name(), coerceToSchema(val, p.schema()));
                }
                for (Map.Entry<String, SerializedValue> e : args.entrySet()) {
                    merged.putIfAbsent(e.getKey(), e.getValue());
                }
                return merged;
            }

            @Override
            public Map<String, SerializedValue> getArguments(Map<String, SerializedValue> v) { return v; }

            @Override
            public List<InputType.ValidationNotice> getValidationFailures(Map<String, SerializedValue> v) { return List.of(); }
        };
    }

    private InputType<Map<String, SerializedValue>> activityInputType(String activityName) {
        return new InputType<>() {
            private List<ParamInfo> params() {
                return activityParams.getOrDefault(activityName, List.of());
            }

            @Override
            public List<InputType.Parameter> getParameters() {
                List<InputType.Parameter> result = new ArrayList<>();
                for (ParamInfo p : params()) result.add(new InputType.Parameter(p.name(), p.schema()));
                return result;
            }

            @Override
            public List<String> getRequiredParameters() {
                List<String> result = new ArrayList<>();
                for (ParamInfo p : params()) if (p.required()) result.add(p.name());
                return result;
            }

            @Override
            public Map<String, SerializedValue> instantiate(Map<String, SerializedValue> args) {
                Map<String, SerializedValue> merged = new LinkedHashMap<>();
                for (ParamInfo p : params()) {
                    SerializedValue val = args.containsKey(p.name()) ? args.get(p.name())
                                       : p.defaultValue();
                    if (val != null) merged.put(p.name(), coerceToSchema(val, p.schema()));
                }
                // pass through any extra keys the caller provided
                for (Map.Entry<String, SerializedValue> e : args.entrySet()) {
                    merged.putIfAbsent(e.getKey(), e.getValue());
                }
                return merged;
            }

            @Override
            public Map<String, SerializedValue> getArguments(Map<String, SerializedValue> v) { return v; }

            @Override
            public List<InputType.ValidationNotice> getValidationFailures(Map<String, SerializedValue> v) { return List.of(); }
        };
    }

    private static List<ParamInfo> parseParams(JsonObject activityJson) {
        List<ParamInfo> result = new ArrayList<>();
        if (activityJson == null) return result;
        JsonObject parameters = activityJson.getAsJsonObject("parameters");
        if (parameters == null) return result;
        for (String paramName : parameters.keySet()) {
            JsonObject meta = parameters.getAsJsonObject(paramName);
            String typeStr  = meta.has("type")     ? meta.get("type").getAsString()     : "any";
            boolean required = meta.has("required") && meta.get("required").getAsBoolean();
            SerializedValue defaultVal = null;
            if (!required && meta.has("default") && !meta.get("default").isJsonNull()) {
                defaultVal = jsonToSerializedValue(meta.get("default"));
            }
            result.add(new ParamInfo(paramName, pythonTypeToSchema(typeStr), required, defaultVal));
        }
        return result;
    }

    private static ValueSchema pythonTypeToSchema(String pyType) {
        return switch (pyType) {
            case "int"   -> ValueSchema.INT;
            case "float" -> ValueSchema.REAL;
            case "bool"  -> ValueSchema.BOOLEAN;
            default      -> ValueSchema.STRING;
        };
    }

    private static SerializedValue jsonToSerializedValue(JsonElement el) {
        if (el.isJsonNull())              return SerializedValue.of("null");
        if (el.isJsonPrimitive()) {
            var prim = el.getAsJsonPrimitive();
            if (prim.isBoolean()) return SerializedValue.of(prim.getAsBoolean());
            if (prim.isNumber()) {
                double d = prim.getAsDouble();
                if (d == Math.floor(d) && !Double.isInfinite(d)) return SerializedValue.of((long) d);
                return SerializedValue.of(d);
            }
            return SerializedValue.of(prim.getAsString());
        }
        return SerializedValue.of(el.toString());
    }

    private static OutputType<Map<String, SerializedValue>> passthroughOutputType() {
        return new OutputType<>() {
            @Override public ValueSchema getSchema()                              { return ValueSchema.ofStruct(Map.of()); }
            @Override public SerializedValue serialize(Map<String, SerializedValue> v) { return SerializedValue.of(v); }
        };
    }

    private static OutputType<Unit> unitOutputType() {
        return new OutputType<>() {
            @Override public ValueSchema getSchema()           { return ValueSchema.ofStruct(Map.of()); }
            @Override public SerializedValue serialize(Unit v) { return SerializedValue.of(Map.of()); }
        };
    }

    /** Real-resource output type: struct {initial: REAL, rate: REAL}, mirroring
     *  merlin-framework's {@code Registrar.real(...)} so the driver emits real profiles. */
    private static OutputType<RealDynamics> realOutputType() {
        return new OutputType<>() {
            @Override public ValueSchema getSchema() {
                return ValueSchema.ofStruct(Map.of(
                    "initial", ValueSchema.REAL,
                    "rate", ValueSchema.REAL));
            }
            @Override public SerializedValue serialize(RealDynamics d) {
                return SerializedValue.of(Map.of(
                    "initial", SerializedValue.of(d.initial),
                    "rate", SerializedValue.of(d.rate)));
            }
        };
    }

    private static OutputType<String> stringOutputType() {
        return new OutputType<>() {
            @Override public ValueSchema getSchema()             { return ValueSchema.STRING; }
            @Override public SerializedValue serialize(String v) { return SerializedValue.of(v); }
        };
    }

    private static OutputType<String> typedOutputType(String vtype) {
        return switch (vtype) {
            case "float" -> new OutputType<>() {
                @Override public ValueSchema getSchema() { return ValueSchema.REAL; }
                @Override public SerializedValue serialize(String v) {
                    try { return SerializedValue.of(Double.parseDouble(v)); }
                    catch (NumberFormatException e) { return SerializedValue.of(0.0); }
                }
            };
            case "int" -> new OutputType<>() {
                @Override public ValueSchema getSchema() { return ValueSchema.INT; }
                @Override public SerializedValue serialize(String v) {
                    try { return SerializedValue.of(Long.parseLong(v)); }
                    catch (NumberFormatException e) {
                        try { return SerializedValue.of((long) Double.parseDouble(v)); }
                        catch (NumberFormatException e2) { return SerializedValue.of(0L); }
                    }
                }
            };
            case "bool" -> new OutputType<>() {
                @Override public ValueSchema getSchema() { return ValueSchema.BOOLEAN; }
                @Override public SerializedValue serialize(String v) {
                    return SerializedValue.of(Boolean.parseBoolean(v));
                }
            };
            default -> stringOutputType();
        };
    }
}
