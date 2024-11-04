package gov.nasa.jpl.aerie.command_expansion.ground_events;

import gov.nasa.jpl.aerie.command_expansion.expansion.SequenceStep;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.command_expansion.model.sequencing.FcplSequenceEngine;

import java.util.List;

public abstract class GroundEvent implements SequenceStep {
    public String name() {
        return this.getClass().getSimpleName();
    }

    public List<Object> args() {
        return List.of();
    }

    // Visitor-like pattern, defers spawn call to each concrete activity,
    // which can statically call the ActivityActions.spawn overload for that concrete activity type.
    public abstract void call(Mission mission);

    @Override
    public final void call(Mission mission, FcplSequenceEngine engine) {
        call(mission);
    }
}
