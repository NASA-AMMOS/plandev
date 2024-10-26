package gov.nasa.jpl.aerie.command_expansion.planning_activities;

import gov.nasa.jpl.aerie.command_expansion.Configuration;
import gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.Registrar;
import gov.nasa.jpl.aerie.merlin.framework.junit.MerlinExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;

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
        activity.condition = "G00INT < G01INT";

        ActivityActions.call(mission, activity);
    }
}