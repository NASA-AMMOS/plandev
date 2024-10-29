package gov.nasa.jpl.aerie.command_expansion.planning_activities;

import gov.nasa.jpl.aerie.command_expansion.expansion.SeqJsonSequence;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.command_expansion.model.PowerModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.time.Instant;
import java.util.List;

import static gov.nasa.jpl.aerie.command_expansion.expansion.SeqJsonSequence.*;
import static gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions.call;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialEffects.consumeUniformly;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.*;

// This is an example of a "non-integrated" / "planning-only" activity.
// The goal with this activity is to avoid all the complexity of the in-Aerie sequencing model,
// but still deliver a command expansion easily.

@ActivityType("Do_Observation_Shallow")
public class Do_Observation_Shallow {
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

        // Notice that we can still utilize the Seq JSON model to structure our expansion,
        // but we don't need to actually model at the command level if that's not valuable.
        Instant startTime = currentValue(mission.clock);
        SeqJsonSequence sequence = new SeqJsonSequence(
                this.getClass().getSimpleName(),
                List.of(
                        new SeqJsonCommand(
                                new SeqJsonAbsoluteTime(startTime),
                                "SEQ_ECHO",
                                List.of(SeqJsonCommandArg.of("Observation activity start"))
                        ),
                        new SeqJsonCommand(
                                new SeqJsonCommandCompleteTime(),
                                "SCI_Warm_Up_Shallow",
                                List.of(SeqJsonCommandArg.of(warmupTime.in(SECONDS)))
                        ),
                        new SeqJsonGroundEvent(
                                new SeqJsonRelativeTime(warmupTime),
                                "GroundAdvisory",
                                List.of(SeqJsonCommandArg.of("Observation is starting"))
                        ),
                        new SeqJsonCommand(
                                new SeqJsonRelativeTime(ZERO),
                                "SCI_Do_Observation_Shallow",
                                List.of(
                                        SeqJsonCommandArg.of("CONSTANT_COMMAND_ARG"),
                                        SeqJsonCommandArg.of(observationTime.in(SECONDS))
                                )
                        ),
                        new SeqJsonGroundEvent(
                                new SeqJsonCommandCompleteTime(),
                                "GroundAdvisory",
                                List.of(SeqJsonCommandArg.of("Observation is complete"))
                        ),
                        new SeqJsonGroundEvent(
                                new SeqJsonRelativeTime(cooldownTime),
                                "GroundAdvisory",
                                List.of(SeqJsonCommandArg.of("Cooldown is complete"))
                        ),
                        new SeqJsonCommand(
                                new SeqJsonRelativeTime(ZERO),
                                "SEQ_ECHO",
                                List.of(SeqJsonCommandArg.of("Observation activity complete"))
                        )
                ));

        // Now let's say that we care about the net effect of this activity on the battery, and the overall duration.
        // We can do that activity-level modeling here, beside the expansion.
        // In this case, we're saying this activity uses 15% of the battery over the warmup and observation time.
        consumeUniformly(mission.power.batterySOC, 15, warmupTime.plus(observationTime));
        delay(warmupTime.plus(observationTime).plus(cooldownTime));

        // We re-use the serialization for the in-memory sequence representation to get the final expansion.
        return sequence.serialize();
    }
}
