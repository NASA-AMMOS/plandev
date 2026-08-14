package gov.nasa.jpl.aerie.scheduler.constraints.resources;

import com.google.common.collect.Lists;
import gov.nasa.jpl.aerie.constraints.model.LinearEquation;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Segment;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.constraints.timeexpressions.TimeExpressionRelative;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.constraints.activities.VariableArgumentComputer;

/**
 * Class allowing to define state query expression for instantiation of parameters
 */
public class StateQueryParam implements VariableArgumentComputer {

  public final String resourceName;
  public final TimeExpressionRelative timeExpr;

  public StateQueryParam(String resourceName, TimeExpressionRelative timeExpression) {
    this.resourceName = resourceName;
    this.timeExpr = timeExpression;
  }

  public SerializedValue getValue(SimulationResults simulationResults, Plan plan, Interval win) {
    var time = timeExpr.computeTime(simulationResults, plan, win);
    if (!time.isSingleton()) {
      throw new RuntimeException(" Time expression in StateQueryParam case must be singleton");
    }
    final var queryT = time.start;

    //TODO: unify necessary generic profile operations in Profile interface to avoid special casing
    if (simulationResults.realProfiles.containsKey(this.resourceName)) {
      //TODO: improve the profile data structure to allow fast time-keyed query
      //for now we look for the last matching profile segment, if any
      final var profile = simulationResults.realProfiles.get(this.resourceName);
      final var piece = profile.profilePieces
          .stream()
          .filter($ -> $.interval().contains(queryT))
          .findFirst()
          .orElseThrow(() -> new Error(
              "Linear profile for %s not have a segment at the desired time %s".formatted(resourceName, queryT))
          );
      return SerializedValue.of(piece.value().valueAt(queryT));
    } else if (simulationResults.discreteProfiles.containsKey(this.resourceName)) {
      //TODO: improve the profile data structure to allow fast time-keyed query
      //for now we look for the last matching profile segment, if any
      final var profile = simulationResults.discreteProfiles.get(this.resourceName);
      final var matchPiece = profile.profilePieces
          .stream()
          .filter($ -> $.interval().contains(queryT))
          .findFirst();
      return matchPiece
          .map(Segment::value)
          .orElseThrow( () -> new Error("The resource " + this.resourceName + " has no value at time " + queryT));
    } else {
      throw new Error("No resource exists with name `" + this.resourceName + "`");
    }
  }
}
