package gov.nasa.jpl.powerModel;

import gov.nasa.jpl.activity.Activity;
import gov.nasa.jpl.time.Time;
import gov.nasa.jpl.time.Duration;

import static gov.nasa.jpl.powerModel.Res.*;

/** Downlink: empties the data buffer and draws some battery. */
public class Downlink extends Activity {
    private final Duration dur;

    public Downlink(Time t, Duration d) {
        super(t, d);
        this.dur = d;
        setDuration(d);
    }

    public void model() {
        Mode.set("Downlink");
        DataBuffer.subtract(50);
        BatterySoC.subtract(15.0);
        waitFor(dur);
        Mode.set("Idle");
    }
}
