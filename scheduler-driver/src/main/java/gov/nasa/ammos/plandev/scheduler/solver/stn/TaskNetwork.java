package gov.nasa.jpl.aerie.scheduler.solver.stn;

import org.apache.commons.lang3.tuple.Pair;

import java.util.HashMap;
import java.util.Map;

/**
 * A task network is a set of tasks and temporal constraints between these tasks.
 * Each task has a start timepoint and end timepoint. Those are constrained by the planning horizon and can be further
 * constrained with start, end, duration and enveloppe intervals.
 * The underlying representation is a STN.
 */
public class TaskNetwork {

  private final Map<String, String> startActTimepoints;
  private final Map<String, String> endActTimepoints;
  private final String startHorizon = "SI";
  private final String endHorizon = "EI";

  double stHorizon = 0;

  private final STN stn;

  public TaskNetwork(){
    this(0., Double.MAX_VALUE);
  }

  public TaskNetwork(double horizonStart, double horizonEnd){
    stn = new STN();
    startActTimepoints = new HashMap<>();
    endActTimepoints = new HashMap<>();
    setHorizon(horizonStart, horizonEnd);
  }

  public record TNActData(Pair<Double,Double> start, Pair<Double,Double> end, Pair<Double,Double> duration) {}

  public TNActData getAllData(String nameAct){
    var st = getStartInterval(nameAct);
    var et = getEndInterval(nameAct);
    var d =  getDurationInterval(nameAct);
    return new TNActData(st, et, d);
  }

  public void removeTask(final String name){
    stn.removeTimepoint(this.startActTimepoints.get(name));
    stn.removeTimepoint(this.endActTimepoints.get(name));
  }

  /**
   * Horizon says that all activities must start after the horizon start and end before the horizon end
   * @param start start horizon
   * @param end end horizon
   */
  protected void setHorizon(double start, double end){
    stHorizon = start;
    stn.addTimepoint(startHorizon);
    stn.addTimepoint(endHorizon);
    stn.addDurCst(startHorizon, endHorizon, end-start, end-start);

    for(var nameTp:startActTimepoints.entrySet()){
      stn.addBeforeCst(nameTp.getValue(), endHorizon);
      stn.addBeforeCst(startHorizon,nameTp.getValue());
    }
    for(var nameTp:endActTimepoints.entrySet()){
      stn.addBeforeCst(nameTp.getValue(), endHorizon);
      stn.addBeforeCst(startHorizon,nameTp.getValue());
    }
  }

  public void addDurationInterval(String nameAct, double lb, double ub){
    stn.addDurCst(startActTimepoints.get(nameAct), endActTimepoints.get(nameAct), lb, ub);
  }


  public Pair<Double,Double> getStartInterval(String actName) {
    var st = startActTimepoints.get(actName);
    return Pair.of(-stn.getDist(st, startHorizon) + stHorizon, stn.getDist(startHorizon, st) + stHorizon);
  }
  public Pair<Double, Double> getEndInterval(String actName){
    var et = endActTimepoints.get(actName);
    return Pair.of(-stn.getDist(et, startHorizon) + stHorizon, stn.getDist(startHorizon, et) + stHorizon);
  }

  public Pair<Double, Double> getDurationInterval(String actName){
    var et = endActTimepoints.get(actName);
    var st = startActTimepoints.get(actName);
    return Pair.of(Math.abs(stn.getDist(et, st)), Math.abs(stn.getDist(st, et)));
  }


  private void failIfActDoesNotExist(String nameAct){
    if(!startActTimepoints.containsKey(nameAct)){
      throw new IllegalArgumentException("Activity " + nameAct + " does not exist in the task network.");
    }
  }

  /**
   * Adds an enveloppe at absolute times t1, t2 for activity nameAct
   */
  public void addEnveloppe(String nameAct, String envName, double t1, double t2){
    failIfActDoesNotExist(nameAct);

    var stAct = startActTimepoints.get(nameAct);
    var etAct = endActTimepoints.get(nameAct);

    var stenvTpName = "st"+envName;
    var etenvTpName = "et"+envName;

    stn.addTimepoint(stenvTpName);
    stn.addTimepoint(etenvTpName);

    stn.addDurCst(startHorizon, stenvTpName, t1-stHorizon, t1-stHorizon);
    stn.addDurCst(startHorizon, etenvTpName, t2-stHorizon, t2-stHorizon);

    stn.addBeforeCst(stenvTpName,etenvTpName);

    //enveloppe start is before act start
    stn.addBeforeCst(stenvTpName, stAct);
    //act starts after end of enveloppe
    stn.addBeforeCst(etAct,etenvTpName);
  }

  public void addStartInterval(String actName, double t1, double t2){
    failIfActDoesNotExist(actName);
    var stAct = startActTimepoints.get(actName);
    stn.addDurCst(startHorizon, stAct, t1-stHorizon, t2-stHorizon);
  }

  /**
   * Adds an absolute time interval for activity
   */
  public void addEndInterval(String actName, double lb, double ub){
    failIfActDoesNotExist(actName);
    var etAct = endActTimepoints.get(actName);
    stn.addDurCst(startHorizon, etAct,lb-stHorizon, ub-stHorizon);
  }


  public void startsAfterEnd(String actBefore, String actAfter){
    failIfActDoesNotExist(actBefore);
    failIfActDoesNotExist(actAfter);
    stn.addBeforeCst(endActTimepoints.get(actBefore),startActTimepoints.get(actAfter));
  }

  public void startsAfterStart(
      final String actBefore,
      final String actAfter,
      final double lb,
      final double ub){
    failIfActDoesNotExist(actBefore);
    failIfActDoesNotExist(actAfter);
    stn.addDurCst(startActTimepoints.get(actBefore), startActTimepoints.get(actAfter), lb, ub);
  }

  public String toDOT() {
    return this.stn.toDOT();
  }

  public void addAct(String name){
    var namevertexst = "st"+name;
    var namevertexet = "et"+name;

    stn.addTimepoint(namevertexst);
    stn.addTimepoint(namevertexet);
    startActTimepoints.put(name, namevertexst);
    endActTimepoints.put(name, namevertexet);

    stn.addBeforeCst(namevertexst, namevertexet);
    stn.addBeforeCst(startHorizon,namevertexst);
    stn.addBeforeCst(startHorizon,namevertexet);
    stn.addBeforeCst(namevertexst,endHorizon);
    stn.addBeforeCst(namevertexet,endHorizon);
  }

  public boolean propagate(){
    return stn.update();
  }
}
