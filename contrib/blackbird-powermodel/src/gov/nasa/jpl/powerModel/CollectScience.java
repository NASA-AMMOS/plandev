package gov.nasa.jpl.powerModel;

import gov.nasa.jpl.activity.Activity;
import gov.nasa.jpl.time.Time;
import gov.nasa.jpl.time.Duration;

import static gov.nasa.jpl.powerModel.Res.*;

/** Collect science: fills the data buffer and drains the battery over its duration. */
public class CollectScience extends Activity {
    private final Duration dur;

    public CollectScience(Time t, Duration d) {
        super(t, d);
        this.dur = d;
        setDuration(d);
    }

    public void model() {
        Mode.set("Science");
        BatterySoC.subtract(40.0);
        DataBuffer.add(100);
        waitFor(dur);
        Mode.set("Idle");
    }
}
