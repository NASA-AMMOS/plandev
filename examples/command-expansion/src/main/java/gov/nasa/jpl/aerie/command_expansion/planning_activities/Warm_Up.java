package gov.nasa.jpl.aerie.command_expansion.planning_activities;

import gov.nasa.jpl.aerie.command_expansion.command_activities.PWR_Turn_Off_Heater;
import gov.nasa.jpl.aerie.command_expansion.command_activities.PWR_Turn_On_Heater;
import gov.nasa.jpl.aerie.command_expansion.expansion.Sequence;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.List;

import static gov.nasa.jpl.aerie.command_expansion.expansion.TimedStep.*;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MINUTES;

// This is an example of a "fully integrated" activity,
// where the commands are fully modeled, and the expansion drives the command-level modeling.

@ActivityType("Warm_Up")
public class Warm_Up {
    // Taking a page from Blackbird, we'll return the expanded sequence as a simple string
    // We may want to wrap this in a computed attributes object with a special form or something, not sure...
    @ActivityType.EffectModel
    public String run(Mission mission) {
        // Note that because we've modeled the commands in this expansion, our entire activity effect model
        // can be, but doesn't need to be, just modeling the expansion.
        var sequence = expand(mission);
        mission.sequencing.run(sequence);
        return sequence.toSeqJson().serialize();
    }

    public Sequence expand(Mission mission) {
        var powerOn = new PWR_Turn_On_Heater();
        var powerOff = new PWR_Turn_Off_Heater();
        return new Sequence(
                this.getClass().getSimpleName(),
                List.of(
                        absolute(currentValue(mission.clock), powerOn),
                        relative(Duration.of(5, MINUTES), powerOff)
                )
        );
    }
}
