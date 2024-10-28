package gov.nasa.jpl.aerie.command_expansion.command_activities;

import gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.List;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;

@ActivityType("Generic_Command")
public class Generic_Command extends Command {
    public static final Duration GENERIC_COMMAND_DURATION = Duration.of(1, SECOND);

    @Export.Parameter
    public String stem = "Generic_Command";

    // TODO - I don't love using List<String> here... I'd rather use List<Object> and preserve the primitive JSON type.
    // When I try that, I get an error because we don't have a generic Object mapper, from which to build a List<Object> mapper.
    // Perhaps I could build a List<Object> mapper?
    // Or maybe I could wrap this in a type called "GenericCommandArguments", and write a mapper for that type?
    @Export.Parameter
    public List<String> args = List.of();

    @ActivityType.EffectModel
    public void run(Mission mission) {
        // As the default command model, I'm using just a delay for a default duration.
        // Of course, missions are free to put whatever they want in here.
        delay(GENERIC_COMMAND_DURATION);
    }

    @Override
    public String stem() {
        return stem;
    }

    @Override
    public List<Object> args() {
        return args.stream().<Object>map($ -> $).toList();
    }

    @Override
    public void call(Mission mission) {
        ActivityActions.call(mission, this);
    }
}
