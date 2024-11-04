package gov.nasa.jpl.aerie.command_expansion.command_activities;

import gov.nasa.jpl.aerie.command_expansion.expansion.SequenceStep;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.command_expansion.model.sequencing.FcplSequenceEngine;

import java.util.List;

public abstract class Command implements SequenceStep {
    public String stem() {
        return this.getClass().getSimpleName();
    }

    public List<Object> args() {
        return List.of();
    }

    // Visitor-like pattern, defers spawn call to each concrete activity,
    // which can statically call the ActivityActions.spawn overload for that concrete activity type.
    public abstract void call(Mission mission);

    // Crucially, this is *not* a parameter settable by the user.
    protected FcplSequenceEngine engine = null;

    // Alternatively - we could use an "engine ID" parameter that the operator *could* set directly,
    // with the expectation that this command would somehow load itself into the indicated engine...
    // Not sure how that would work just yet.

    // Either way, we then expose a method for the engine to call which gives this command the engine.
    @Override
    public final void call(Mission mission, FcplSequenceEngine engine) {
        this.engine = engine;
        call(mission);
    }
}
