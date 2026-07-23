package gov.nasa.jpl.powerModel;

import gov.nasa.jpl.activity.Activity;
import gov.nasa.jpl.scheduler.Condition;
import gov.nasa.jpl.scheduler.Scheduler;
import gov.nasa.jpl.time.Time;

import static gov.nasa.jpl.powerModel.Res.*;

/**
 * A forward-dispatch SCHEDULER: while placed in the plan, it watches BatterySoC and automatically
 * spawns a Recharge whenever the battery drops below 30%. The spawned Recharge activities are NOT plan
 * directives — they are created during simulation and come back to PlanDev as spans with no directiveId.
 * This is the Archetype-B behavior we want to exercise end-to-end.
 */
public class AutoRecharge extends Activity implements Scheduler {

    public AutoRecharge(Time t) {
        super(t);
    }

    @Override
    public Condition setCondition() {
        return BatterySoC.whenLessThan(30.0);
    }

    @Override
    public void dispatchOnCondition() {
        // Recharge sets SoC back to 100, which clears the condition (prevents immediate re-fire).
        spawn(new Recharge(now()));
    }
}
