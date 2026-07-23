package gov.nasa.jpl.powerModel;

import gov.nasa.jpl.activity.Activity;
import gov.nasa.jpl.time.Time;
import gov.nasa.jpl.time.Duration;

/** A decomposing activity: a science pass = collect science then downlink it. Exercises decomposition
 *  (the two children come back as child spans of this activity). */
public class SciencePass extends Activity {
    public SciencePass(Time t) {
        super(t);
    }

    public void decompose() {
        spawn(new CollectScience(getStart(), new Duration("00:05:00")));
        spawn(new Downlink(getStart().add(new Duration("00:05:00")), new Duration("00:05:00")));
    }
}
