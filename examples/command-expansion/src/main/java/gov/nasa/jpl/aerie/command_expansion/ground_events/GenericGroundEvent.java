package gov.nasa.jpl.aerie.command_expansion.ground_events;

import gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;

import java.util.List;

@ActivityType("GenericGroundEvent")
public class GenericGroundEvent extends GroundEvent {
    @Export.Parameter
    public String name;

    // TODO - I don't love using List<String> here... I'd rather use List<Object> and preserve the primitive JSON type.
    // When I try that, I get an error because we don't have a generic Object mapper, from which to build a List<Object> mapper.
    // Perhaps I could build a List<Object> mapper?
    // Or maybe I could wrap this in a type called "GenericCommandArguments", and write a mapper for that type?
    @Export.Parameter
    public List<String> args = List.of();

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<Object> args() {
        return args.stream().<Object>map($ -> $).toList();
    }

    @ActivityType.EffectModel
    public void run(Mission mission) {
    }

    @Override
    public void call(Mission mission) {
        ActivityActions.call(mission, this);
    }
}
