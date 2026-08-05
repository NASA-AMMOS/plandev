package gov.nasa.ammos.aerie.pymerlin.shim;

import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;
import gov.nasa.jpl.aerie.orchestration.simulation.SimulationUtility;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.ActivityInstance;
import gov.nasa.jpl.aerie.types.Plan;
import gov.nasa.jpl.aerie.types.Timestamp;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 3 (§6) exit-criterion coverage for {@code call()}, which the demo model never uses
 * and which the (since-deleted, roadmap §6.3/§6.6) subprocess oracle never supported either
 * (its {@code _ActivityRunner} had no {@code Calling} branch). Requires a real GraalPy
 * runtime + provisioned {@code python-resources} venv, like {@link DemoModelSimulationTest};
 * skipped unless {@code -Dpymerlin.test.graal=true}.
 *
 * <p>The model ({@code call_model.py}, a bundled test resource): {@code parent_act} call()s
 * {@code child_act}, which delays 3 minutes. If {@code call()} blocks correctly, {@code parent_act}'s
 * own span is 3 minutes long and its {@code PARENT_AFTER} emit lands at t+3min. If it did NOT block
 * (i.e. behaved like spawn), {@code parent_act} would finish at t+0 with a ~zero-length span — so the
 * duration assertion is what actually distinguishes call from spawn.
 */
public final class CallSemanticsTest {

    @Test
    public void callBlocksParentUntilChildCompletes() throws Exception {
        assumeTrue(Boolean.getBoolean("pymerlin.test.graal"),
            "requires a real GraalPy runtime + provisioned python-resources venv; "
            + "skipped without -Dpymerlin.test.graal=true so a stock JDK does not false-fail");

        // Point the shim at the bundled call model for the duration of this test, then restore.
        final Path model = Path.of(
            CallSemanticsTest.class.getResource("/pymerlin_test_models/call_model.py").toURI());
        final String previousRef = System.getProperty("pymerlin.model.ref");
        System.setProperty("pymerlin.model.ref", model.toAbsolutePath() + ":CallMission");

        try {
            final Instant startTime = Instant.parse("2026-01-01T00:00:00Z");
            final Timestamp start = new Timestamp(startTime);
            final Timestamp end = new Timestamp(startTime.plusSeconds(600));

            final Map<ActivityDirectiveId, ActivityDirective> schedule = new HashMap<>();
            schedule.put(new ActivityDirectiveId(0),
                new ActivityDirective(Duration.ZERO, "parent_act", Map.of(), null, true));

            final Plan plan = new Plan("plan", start, end, schedule, Map.of());
            final MissionModel<Unit> missionModel =
                SimulationUtility.instantiateMissionModel(new ShimModelType(), startTime, Map.of());

            final SimulationResults results;
            try (var simUtil = new SimulationUtility()) {
                results = simUtil.simulate(missionModel, plan).get();
            }

            ActivityInstance parent = null;
            ActivityInstance child = null;
            for (final ActivityInstance a : results.simulatedActivities.values()) {
                if (a.type().equals("parent_act")) parent = a;
                if (a.type().equals("child_act")) child = a;
            }

            assertNotNull(parent, "parent_act missing — simulatedActivities=" + results.simulatedActivities);
            assertNotNull(child, "child_act (called) missing — was call() executed? simulatedActivities="
                + results.simulatedActivities);

            System.out.println("[CallSemanticsTest] parent_act.duration = " + parent.duration());
            System.out.println("[CallSemanticsTest] child_act.duration  = " + child.duration());
            System.out.println("[CallSemanticsTest] /stage = " + results.discreteProfiles.get("/stage").segments());

            assertEquals(Duration.of(3, Duration.MINUTES), parent.duration(),
                "parent_act must block on the called child for its full 3-minute delay — a shorter "
                + "span means call() did not block (behaved like spawn)");
            assertEquals(Duration.of(3, Duration.MINUTES), child.duration(),
                "child_act runs its own 3-minute delay");
            assertNotNull(child.parentId(), "child_act should be a child span of parent_act");
        } finally {
            if (previousRef == null) System.clearProperty("pymerlin.model.ref");
            else System.setProperty("pymerlin.model.ref", previousRef);
        }
    }
}
