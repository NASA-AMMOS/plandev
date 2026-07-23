package gov.nasa.jpl.powerModel;

import gov.nasa.jpl.activity.Activity;
import gov.nasa.jpl.time.Time;
import gov.nasa.jpl.time.Duration;

import static gov.nasa.jpl.powerModel.Res.*;

/** Recharge the battery to full. Spawned automatically by the AutoRecharge scheduler. */
public class Recharge extends Activity {
    private static final Duration DUR = new Duration("00:10:00");

    public Recharge(Time t) {
        super(t);
        setDuration(DUR);
    }

    public void model() {
        Mode.set("Recharge");
        BatterySoC.set(100.0);
        waitFor(DUR);
        Mode.set("Idle");
    }
}
