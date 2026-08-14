package gov.nasa.jpl.aerie.scheduler.solver.stn;

import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Optional;

/**
 * Adapter for TaskNetwork for use with Interval and Duration
 */
public class TaskNetworkAdapter {
  private static final Logger logger = LoggerFactory.getLogger(TaskNetworkAdapter.class);

  private final TaskNetwork tw;

  public TaskNetworkAdapter(TaskNetwork tw){
    this.tw = tw;
  }

  public TaskNetworkAdapter(final Duration planningHorizonDuration){
    this.tw = new TaskNetwork(0, toDouble(planningHorizonDuration));
  }

  public void removeTask(final String name){
    tw.removeTask(name);
  }

  public record TNActData(Interval start, Interval end, Interval duration) {}

  public void addDurationInterval(String nameAct, Duration lb, Duration ub){
    tw.addDurationInterval(nameAct,toDouble(lb), toDouble(ub));
  }

  public Interval getStartInterval(String actName) {
    var st = tw.getStartInterval(actName);
    return toWin(st.getLeft(), st.getRight());
  }

  public TNActData getAllData(String nameAct){
    var data = tw.getAllData(nameAct);
    return new TNActData(toWin(data.start()), toWin(data.end()), toWin(data.duration()));
  }

  public Interval getEndInterval(String actName){
    return toWin(tw.getEndInterval(actName));
  }

  public Interval getDurationInterval(String actName){
    return toWin(tw.getDurationInterval(actName));
  }

  /**
   * Adds an enveloppe at absolute times t1, t2 for activity nameAct
   */
  public void addEnveloppe(String nameAct, String envName, Duration t1, Duration t2){
    tw.addEnveloppe(nameAct,envName,toDouble(t1),toDouble(t2));
  }

  public void addStartInterval(String actName, Duration t1, Duration t2){
    tw.addStartInterval(actName,toDouble(t1),toDouble(t2));
  }

  /**
   * Adds an absolute time interval for activity
   */
  public void addEndInterval(String actName, Duration lb, Duration ub){
   tw.addEndInterval(actName,toDouble(lb),toDouble(ub));
  }

  public void changeStartInterval(String actName, Duration lb, Duration ub){
    //we can use addStartInterval because the underlying will not create new edges
    tw.addStartInterval(actName, toDouble(lb), toDouble(ub));
  }

  public void changeEndInterval(String actName, Duration lb, Duration ub){
    //we can use addStartInterval because the underlying will not create new edges
    tw.addEndInterval(actName, toDouble(lb), toDouble(ub));
  }

  public void startsAfterStart(
      final String actBefore,
      final String actAfter,
      final Duration lb,
      final Duration ub){
    tw.startsAfterStart(actBefore, actAfter, toDouble(lb), toDouble(ub));
  }

  public String toDOT() {
    return this.tw.toDOT();
  }

  public void startsAfterEnd(String actBefore, String actAfter){
    tw.startsAfterEnd(actBefore,actAfter);
  }

  public void addAct(String name){
    tw.addAct(name);
  }

  public boolean solveConstraints(){
    return tw.propagate();
  }

  private Duration toDur(double d){
    return Duration.of(Math.round(d), Duration.MICROSECOND);
  }

  private double toDouble(Duration dur){
    return (double) dur.in(Duration.MICROSECOND);
  }

  private Interval toWin(double d1, double d2){
    return Interval.between(toDur(d1), toDur(d2));
  }

  private Interval toWin(Pair<Double, Double> pair){
    return toWin(pair.getLeft(), pair.getRight());
  }

  public static Optional<TNActData> reduceActivityTemporalConstraints(
      final Interval startInterval,
      final Interval endInterval,
      final Interval durationInterval,
      final Collection<Interval> envelopes){
    final TaskNetwork tw = new TaskNetwork();
    final String actName = "ACT";
    final TaskNetworkAdapter tnw = new TaskNetworkAdapter(tw);
    tnw.addAct(actName);
    if(startInterval != null){
      tnw.addStartInterval(actName, startInterval.start, startInterval.end);
    }
    if(endInterval != null){
      tnw.addEndInterval(actName, endInterval.start, endInterval.end);
    }
    if(durationInterval != null){
      tnw.addDurationInterval(actName, durationInterval.start, durationInterval.end);
    }
    var i = 0;
    for(final var enveloppe: envelopes){
      tnw.addEnveloppe(actName, "ENV"+(i++), enveloppe.start, enveloppe.end);
    }
    if(tnw.solveConstraints()){
      return Optional.of(tnw.getAllData(actName));
    } else{
      logger.debug("Inconsistent static temporal constraints, cannot place activity in interval");
      logger.debug("Start range " + startInterval);
      logger.debug("End range " + endInterval);
      logger.debug("Duration range " + durationInterval);
      logger.debug("Envelopes: " + envelopes);
      return Optional.empty();
    }
  }

}
