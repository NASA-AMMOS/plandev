package gov.nasa.ammos.aerie.pymerlin.shim;

import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfile;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;
import gov.nasa.jpl.aerie.orchestration.simulation.SimulationUtility;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.ActivityInstance;
import gov.nasa.jpl.aerie.types.Plan;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end regression check: {@code demo/model.py} simulates correctly through
 * {@code ShimModelType} on the (only, since roadmap §6.3/§6.6) direct-call GraalPy path.
 *
 * <p>Formerly {@code BridgeParityTest}, which compared this same demo-model run under both
 * {@code SubprocessBridge} and {@code GraalBridge} — that comparison was the Phase 2 exit
 * criterion (roadmap §5.5) and it passed, confirmed byte-identical against the real image
 * (roadmap §6.6) before {@code SubprocessBridge} was deleted. There is only one bridge now,
 * so there is nothing left to compare against; this is a plain correctness/regression test.
 *
 * <p>Requires a real GraalPy runtime + provisioned {@code python-resources} venv;
 * {@code assumeTrue}-skips without {@code -Dpymerlin.test.graal=true} so a stock JDK build
 * doesn't false-fail:
 * <pre>./gradlew :pymerlin-shim:test -Dpymerlin.test.graal=true \
 *    -Dpymerlin.resources=/opt/pymerlin/python-resources</pre>
 */
public final class DemoModelSimulationTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private static Map<ActivityDirectiveId, ActivityDirective> demoSchedule() {
        final Map<ActivityDirectiveId, ActivityDirective> schedule = new HashMap<>();
        // collect_data exercises emit + delay + spawn(compress_data); a single directive
        // keeps the run fully deterministic.
        schedule.put(new ActivityDirectiveId(0),
            new ActivityDirective(Duration.ZERO, "collect_data", Map.of(), null, true));
        return schedule;
    }

    /**
     * A stable, order-independent textual projection of the parts of a SimulationResults
     * that a model's behaviour determines: discrete + real resource profiles and the
     * simulated-activity spans.
     */
    private static String canonicalize(SimulationResults r) {
        final List<String> lines = new ArrayList<>();

        r.discreteProfiles.forEach((name, profile) ->
            lines.add("discrete " + name + " = " + profile.segments()));
        r.realProfiles.forEach((name, profile) ->
            lines.add("real " + name + " = " + profile.segments()));

        final List<String> acts = new ArrayList<>();
        for (final ActivityInstance a : r.simulatedActivities.values()) {
            acts.add(a.type() + " start=" + a.start() + " dur=" + a.duration()
                     + " parent=" + a.parentId());
        }
        acts.sort(String::compareTo);
        lines.addAll(acts);

        lines.sort(String::compareTo);
        return String.join("\n", lines);
    }

    @Test
    public void demoModelSimulatesCorrectly() throws Exception {
        assumeTrue(Boolean.getBoolean("pymerlin.test.graal"),
            "requires a real GraalPy runtime + provisioned python-resources venv; "
            + "skipped without -Dpymerlin.test.graal=true so a stock JDK does not false-fail");

        final Timestamp start = new Timestamp(START);
        final Timestamp end = new Timestamp(START.plusSeconds(600));
        final Plan plan = new Plan("plan", start, end, demoSchedule(), Map.of());
        final MissionModel<Unit> model =
            SimulationUtility.instantiateMissionModel(new ShimModelType(), START, Map.of());

        final SimulationResults results;
        try (var simUtil = new SimulationUtility()) {
            results = simUtil.simulate(model, plan).get();
        }

        final List<String> types = new ArrayList<>();
        results.simulatedActivities.values().forEach(a -> types.add(a.type()));
        assertFalse(results.simulatedActivities.isEmpty(),
            "no simulated activities — did the direct-call path regress?");
        assertEquals(true, types.contains("collect_data"), "collect_data should have simulated");
        assertEquals(true, types.contains("compress_data"), "spawned compress_data should have simulated");

        System.out.println("[DemoModelSimulationTest] canonical result:\n" + canonicalize(results));
    }

    /**
     * Regression for roadmap §7.2: {@code /data_volume_mb} is a linear cell, so during
     * {@code downlink} it must ramp down continuously (a real-profile segment with a
     * negative rate) rather than stepping to zero only when the activity ends. Schedules
     * {@code collect_data} at t=0 (fills the buffer to 614.4 MB by t=7min after the spawned
     * {@code compress_data}'s {@code *0.6}) and {@code downlink} at t=8min, then asserts the
     * value ramps linearly at two sample points during the 10-minute drain.
     */
    @Test
    public void downlinkRampsDataVolumeDownContinuously() throws Exception {
        assumeTrue(Boolean.getBoolean("pymerlin.test.graal"),
            "requires a real GraalPy runtime + provisioned python-resources venv; "
            + "skipped without -Dpymerlin.test.graal=true so a stock JDK does not false-fail");

        final long downlinkStartS = 8 * 60;   // 480s — after compress_data settles the buffer
        final Map<ActivityDirectiveId, ActivityDirective> schedule = new HashMap<>();
        schedule.put(new ActivityDirectiveId(0),
            new ActivityDirective(Duration.ZERO, "collect_data", Map.of(), null, true));
        schedule.put(new ActivityDirectiveId(1),
            new ActivityDirective(Duration.of(downlinkStartS, Duration.SECONDS), "downlink", Map.of(), null, true));

        final Timestamp start = new Timestamp(START);
        final Timestamp end = new Timestamp(START.plusSeconds(1200));
        final Plan plan = new Plan("plan", start, end, schedule, Map.of());
        // Configure the low-gain antenna (roadmap §7): downlink then drains over 10 minutes,
        // which this test's rate/sample-point assertions below depend on. Also exercises the
        // config path end-to-end — a bool config parameter changing model behavior.
        final MissionModel<Unit> model =
            SimulationUtility.instantiateMissionModel(
                new ShimModelType(), START, Map.of("high_gain", SerializedValue.of(false)));

        final SimulationResults results;
        try (var simUtil = new SimulationUtility()) {
            results = simUtil.simulate(model, plan).get();
        }

        final ResourceProfile<RealDynamics> profile = results.realProfiles.get("/data_volume_mb");
        assertFalse(profile == null, "/data_volume_mb should be a real profile (linear cell)");

        // The buffer holds 1024 MB at t=5min, then compress_data's *0.6 leaves 614.4 MB by t=7min.
        final double volumeAtDownlinkStart = 1024.0 * 0.6;
        final double expectedRate = -volumeAtDownlinkStart / (10 * 60);

        // Core fix: some segment during the drain has a genuinely negative rate (a ramp, not a step).
        boolean sawNegativeRate = false;
        for (final ProfileSegment<RealDynamics> seg : profile.segments()) {
            if (seg.dynamics().rate < -1e-9) { sawNegativeRate = true; break; }
        }
        assertEquals(true, sawNegativeRate,
            "data_volume_mb should ramp down (negative-rate segment) during downlink, not step");

        // Sample the ramp at 1 and 5 minutes into the drain.
        final double at1min = evalReal(profile, downlinkStartS + 60);
        final double at5min = evalReal(profile, downlinkStartS + 300);
        assertEquals(volumeAtDownlinkStart + expectedRate * 60, at1min, 1.0,
            "value 1min into downlink should follow the linear ramp");
        assertEquals(volumeAtDownlinkStart + expectedRate * 300, at5min, 1.0,
            "value 5min into downlink should follow the linear ramp");
    }

    /**
     * Regression for roadmap §7 model configuration: the model constructor's post-registrar
     * parameters become the configuration schema. {@code Mission.__init__(self, registrar,
     * initial_battery_pct=100.0, high_gain=True)} should surface exactly those two, with the
     * right types and neither marked required (both have defaults).
     */
    @Test
    public void configurationSchemaExposesModelConstructorParams() {
        assumeTrue(Boolean.getBoolean("pymerlin.test.graal"),
            "requires a real GraalPy runtime + provisioned python-resources venv; "
            + "skipped without -Dpymerlin.test.graal=true so a stock JDK does not false-fail");

        final var configType = new ShimModelType().getConfigurationType();

        final Map<String, gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema> params = new HashMap<>();
        configType.getParameters().forEach(p -> params.put(p.name(), p.schema()));

        assertEquals(2, params.size(), "expected exactly the two constructor config params");
        assertEquals(gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.REAL, params.get("initial_battery_pct"),
            "initial_battery_pct should be a REAL config parameter");
        assertEquals(gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.BOOLEAN, params.get("high_gain"),
            "high_gain should be a BOOLEAN config parameter");
        assertEquals(true, configType.getRequiredParameters().isEmpty(),
            "both config params have defaults, so none should be required");
    }

    /** Evaluate a real profile at {@code targetSeconds} past the profile start. */
    private static double evalReal(ResourceProfile<RealDynamics> profile, long targetSeconds) {
        Duration cursor = Duration.ZERO;
        final Duration target = Duration.of(targetSeconds, Duration.SECONDS);
        for (final ProfileSegment<RealDynamics> seg : profile.segments()) {
            final Duration segEnd = cursor.plus(seg.extent());
            if (target.noLongerThan(segEnd) || seg.extent().isEqualTo(Duration.ZERO)) {
                final double into = target.minus(cursor).ratioOver(Duration.SECOND);
                return seg.dynamics().initial + seg.dynamics().rate * into;
            }
            cursor = segEnd;
        }
        final ProfileSegment<RealDynamics> last = profile.segments().get(profile.segments().size() - 1);
        return last.dynamics().initial;
    }
}
