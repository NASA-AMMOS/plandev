package gov.nasa.jpl.aerie.command_expansion.planning_activities;

import gov.nasa.jpl.aerie.command_expansion.command_activities.*;
import gov.nasa.jpl.aerie.command_expansion.expansion.Sequence;
import gov.nasa.jpl.aerie.command_expansion.ground_events.GroundAdvisory;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.command_expansion.model.PowerModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.time.Instant;
import java.util.List;

import static gov.nasa.jpl.aerie.command_expansion.expansion.TimedStep.*;
import static gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions.call;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Logging.LOGGER;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.*;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.*;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.*;

// This is an example of "deep" modeling, where we have an integrated command model
// and the bulk of the activity behavior is modeled by just running the sequence it expands into.
// That said, this variant still hangs some additional behavior at the activity level
// by monitoring the engine as it runs.

@ActivityType("Do_Observation_Deep_1")
public class Do_Observation_Deep_1 {
    @Export.Parameter
    public Duration warmupTime = Duration.of(10, MINUTES);

    @Export.Parameter
    public Duration observationTime = Duration.of(60, MINUTES);

    @Export.Parameter
    public Duration cooldownTime = Duration.of(10, MINUTES);

    @ActivityType.EffectModel
    public String run(Mission mission) {
        // Note that the modeling and expansion may depend on modeled states.
        if (currentValue(mission.power.heater) == PowerModel.HeaterState.OFF) {
            call(mission, new Warm_Up());
        }

        var warmUpCmd = new SCI_Warm_Up_Deep();
        warmUpCmd.duration = (int) warmupTime.in(SECONDS);

        var doObsCmd = new SCI_Do_Observation_Deep();
        doObsCmd.duration = (int) observationTime.in(SECONDS);

        Instant startTime = currentValue(mission.clock);
        Sequence sequence = new Sequence(
                this.getClass().getSimpleName(),
                List.of(
                        absolute(startTime, SEQ_ECHO.of("Observation activity start")),
                        commandComplete(warmUpCmd),
                        relative(warmupTime, GroundAdvisory.of("Observation is starting")),
                        relative(ZERO, doObsCmd),
                        commandComplete(GroundAdvisory.of("Observation is complete")),
                        relative(cooldownTime, GroundAdvisory.of("Cooldown is complete")),
                        relative(ZERO, SEQ_ECHO.of("Observation activity complete"))
                )
        );

        // Since we're doing "full" command modeling, we might model this activity fully by just running the sequence.
        // mission.sequencing.run(sequence);

        // However, if there were other effects we needed to handle in this activity, we could put those here.
        // By just activating the sequence, we get the engine it's running in, which we can use to do activity modeling
        // in a way that's aware of its sequence:
        var engine = mission.sequencing.activate(sequence);
        // For example, let's say we want to do something when the "Do Observation" command is dispatched:
        waitUntil(when(engine.lastDispatched(doObsCmd)));
        LOGGER.info("Do_Observation_Deep_1 - Stand-in for observation modeling");
        // We can also "step" through the sequence by waiting for the next dispatch to happen
        waitUntil(engine.nextDispatch());
        LOGGER.info("Do_Observation_Deep_1 - Stand-in for cool-down modeling");

        // Note that the span for this activity will cover the full sequence execution,
        // because the engine executes as a child of this activity.
        // We don't need any additional code here to specify that.

        return sequence.toSeqJson().serialize();
    }
}
