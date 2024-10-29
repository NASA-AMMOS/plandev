package gov.nasa.jpl.aerie.command_expansion.planning_activities;

import gov.nasa.jpl.aerie.command_expansion.Configuration;
import gov.nasa.jpl.aerie.command_expansion.command_activities.Command;
import gov.nasa.jpl.aerie.command_expansion.command_activities.PWR_Turn_On_Heater;
import gov.nasa.jpl.aerie.command_expansion.expansion.SeqJsonSequence;
import gov.nasa.jpl.aerie.command_expansion.expansion.Sequence;
import gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.Registrar;
import gov.nasa.jpl.aerie.merlin.framework.junit.MerlinExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MerlinExtension.class)
public final class Conditional_Warm_UpTest {
    private final Mission mission;

    public Conditional_Warm_UpTest(final Registrar registrar) {
        var config = new Configuration();
        this.mission = new Mission(registrar, Instant.EPOCH, config);
    }

    @Test
    public void test() {
        var activity = new Conditional_Warm_Up();
        activity.condition = "G00INT <= G01INT";

        // We get the serialized sequence as actual text...
        String serializedSequence = ActivityActions.call(mission, activity);

        // We probably want to deserialize to a structured / "shallow" representation
        SeqJsonSequence sequence = SeqJsonSequence.deserialize(serializedSequence);

        // We can of course test that representation directly
        assertEquals(Conditional_Warm_Up.class.getSimpleName(), sequence.id());
        assertTrue(sequence.steps().stream().anyMatch(step ->
                step instanceof SeqJsonSequence.SeqJsonCommand cmd
                        && cmd.stem().equals("PWR_Turn_On_Heater")));

        // If we have the infrastructure to further parse that to a deep representation, we can also test that.
        Sequence deepSequence = Sequence.parse(sequence);
        assertTrue(deepSequence.commands().stream().anyMatch(timedCommand ->
                timedCommand.step() instanceof PWR_Turn_On_Heater));
    }
}