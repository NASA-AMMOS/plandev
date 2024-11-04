package gov.nasa.jpl.aerie.command_expansion.planning_activities;

import gov.nasa.jpl.aerie.command_expansion.command_activities.SCI_Do_Observation_Deep;
import gov.nasa.jpl.aerie.command_expansion.command_activities.SCI_Warm_Up_Deep;
import gov.nasa.jpl.aerie.command_expansion.command_activities.SEQ_ECHO;
import gov.nasa.jpl.aerie.command_expansion.expansion.Sequence;
import gov.nasa.jpl.aerie.command_expansion.ground_events.AuxiliaryModeling;
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
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.not;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.when;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.waitUntil;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.*;

// This is an example of "deep" modeling, where we have an integrated command model
// and the entirety of the activity behavior is modeled by just running the sequence it expands into.
// The difference between this and the _1 variant is in how we deal with additional activity-level modeling.
// In this variant, we inject that modeling into the sequence itself with AuxiliaryModeling ground events.

@ActivityType("Do_Observation_Deep_2")
public class Do_Observation_Deep_2 {
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
                        relative(ZERO, AuxiliaryModeling.of(() -> {
                            LOGGER.info("Do_Observation_Deep_2 - Stand-in for observation modeling");
                            delay(2, SECONDS);
                        })),
                        relative(ZERO, doObsCmd),
                        commandComplete(GroundAdvisory.of("Observation is complete")),
                        relative(ZERO, AuxiliaryModeling.of(() -> {
                            LOGGER.info("Do_Observation_Deep_2 - Stand-in for cool-down modeling");
                            delay(2, SECONDS);
                        })),
                        relative(cooldownTime, GroundAdvisory.of("Cooldown is complete")),
                        relative(ZERO, SEQ_ECHO.of("Observation activity complete"))
                )
        );

        // Since we're doing "full" command modeling, we model this activity fully by just running the sequence.
        mission.sequencing.run(sequence);

        return sequence.toSeqJson().serialize();
    }
}
