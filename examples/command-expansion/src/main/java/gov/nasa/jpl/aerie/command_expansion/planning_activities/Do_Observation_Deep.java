package gov.nasa.jpl.aerie.command_expansion.planning_activities;

import gov.nasa.jpl.aerie.command_expansion.command_activities.*;
import gov.nasa.jpl.aerie.command_expansion.expansion.Sequence;
import gov.nasa.jpl.aerie.command_expansion.expansion.TimedCommand;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.command_expansion.model.PowerModel;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.time.Instant;
import java.util.List;

import static gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions.call;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Logging.LOGGER;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.*;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteResourceMonad.map;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialEffects.consumeUniformly;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.*;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MINUTES;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;

// This is an example of splitting the difference between the "deep" and "shallow" models.
// We use the actual command types, but we don't take advantage of any command modeling nor sequence engine modeling.
// We might imagine doing something like this as a half-way step while migrating from shallow to deep modeling,
// where the main benefit of the command types is compile-time type checking on the arguments.
// These modeling-free command classes could easily be auto-generated from the command dictionary.

@ActivityType("Do_Observation_Deep")
public class Do_Observation_Deep {
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
                        TimedCommand.absolute(startTime, warmUpCmd),
                        TimedCommand.relative(warmupTime, doObsCmd),
                        TimedCommand.commandComplete(new CMD_NO_OP()),
                        TimedCommand.relative(cooldownTime, new CMD_NO_OP())
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
        LOGGER.info("Do_Observation_Deep - Stand-in for observation modeling");
        // We can also "step" through the sequence by waiting for the next dispatch to happen
        waitUntil(engine.nextDispatch());
        LOGGER.info("Do_Observation_Deep - Stand-in for cool-down modeling");

        // When we're done, we can just wait for the sequence to be unloaded to declare the activity done.
        waitUntil(when(not(engine.isLoadedWith(sequence))));

        return sequence.toSeqJson().serialize();
    }
}
