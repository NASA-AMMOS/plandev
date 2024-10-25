package gov.nasa.jpl.aerie.command_expansion.planning_activities;

import gov.nasa.jpl.aerie.command_expansion.command_activities.CMD_NO_OP;
import gov.nasa.jpl.aerie.command_expansion.command_activities.SCI_Do_Observation_Moderate;
import gov.nasa.jpl.aerie.command_expansion.command_activities.SCI_Warm_Up_Moderate;
import gov.nasa.jpl.aerie.command_expansion.expansion.Sequence;
import gov.nasa.jpl.aerie.command_expansion.expansion.TimedCommand;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.command_expansion.model.PowerModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.time.Instant;
import java.util.List;

import static gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions.call;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialEffects.consumeUniformly;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.spawn;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MINUTES;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;

// This is an example of splitting the difference between the "deep" and "shallow" models.
// We use the actual command types, but we don't take advantage of any command modeling nor sequence engine modeling.
// We might imagine doing something like this as a half-way step while migrating from shallow to deep modeling,
// where the main benefit of the command types is compile-time type checking on the arguments.
// These modeling-free command classes could easily be auto-generated from the command dictionary.

@ActivityType("Do_Observation_Moderate")
public class Do_Observation_Moderate {
    @Export.Parameter
    public Duration warmupTime = Duration.of(10, MINUTES);

    @Export.Parameter
    public Duration observationTime = Duration.of(60, MINUTES);

    @Export.Parameter
    public Duration cooldownTime = Duration.of(10, MINUTES);

    @Export.Parameter
    public boolean modelCommands = false;

    @ActivityType.EffectModel
    public String run(Mission mission) {
        // Note that the modeling and expansion may depend on modeled states.
        if (currentValue(mission.power.heater) == PowerModel.HeaterState.OFF) {
            call(mission, new Warm_Up());
        }

        var warmUpCmd = new SCI_Warm_Up_Moderate();
        warmUpCmd.duration = (int) warmupTime.in(SECONDS);

        var doObsCmd = new SCI_Do_Observation_Moderate();
        doObsCmd.duration = (int) observationTime.in(SECONDS);

        Instant startTime = currentValue(mission.clock);
        Sequence sequence = new Sequence(
                this.getClass().getSimpleName(),
                List.of(
                        TimedCommand.absolute(startTime, warmUpCmd),
                        TimedCommand.relative(warmupTime, doObsCmd),
                        TimedCommand.commandComplete(new CMD_NO_OP()),
                        TimedCommand.relative(cooldownTime, new CMD_NO_OP())
                )
        );

        // Since we're not doing any "real" command-level modeling, we'll still do some activity-level modeling.
        consumeUniformly(mission.power.batterySOC, 15, warmupTime.plus(observationTime));

        if (modelCommands) {
            // We can then model the duration by modeling the sequence, which we're assuming will be a bunch of no-ops for
            // the commands, but at least we'd more-or-less understand the command timing.
            mission.sequencing.run(sequence);
        } else {
            // Alternatively, if the mission doesn't want to take on the complexity of in-Aerie sequence modeling at all,
            // we can just do an activity-level model again for the duration.
            delay(warmupTime.plus(observationTime).plus(cooldownTime));
        }

        return sequence.toSeqJson().serialize();
    }
}
