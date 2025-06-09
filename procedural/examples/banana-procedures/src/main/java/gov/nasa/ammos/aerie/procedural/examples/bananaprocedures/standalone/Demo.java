package gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.standalone;

import gov.nasa.ammos.aerie.procedural.scheduling.utils.DefaultEditablePlanDriver;
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Real;
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults;
import gov.nasa.ammos.aerie.procedural.utils.TypeUtilsEditablePlanAdapter;
import gov.nasa.ammos.aerie.procedural.utils.TypeUtilsPlanAdapter;
import gov.nasa.jpl.aerie.banananation.Configuration;
import gov.nasa.jpl.aerie.banananation.generated.GeneratedModelType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import gov.nasa.jpl.aerie.orchestration.simulation.SimulationUtility;
import gov.nasa.jpl.aerie.types.Plan;
import gov.nasa.jpl.aerie.types.Timestamp;

public class Demo {

  public static void main(String[] args) {
    Configuration c = Configuration.defaultConfiguration();
    final var simStartTime = Instant.EPOCH;
    final var missionModel = SimulationUtility.instantiateMissionModel(new GeneratedModelType(), simStartTime, c);


    Plan plan = new Plan("plan0",
                         new Timestamp(simStartTime),
                         new Timestamp(simStartTime.plus(1, ChronoUnit.HOURS)),
                         Map.of(),
                         Map.of());
    try (final var simulationUtility = new SimulationUtility()) {

      var planDriver = new DefaultEditablePlanDriver(
          new TypeUtilsEditablePlanAdapter(
              new TypeUtilsPlanAdapter(plan),
              simulationUtility,
              missionModel
          )
      );

      final SimulationResults results = planDriver.simulate();
      final Real fruit = results.resource("/fruit", Real.deserializer());
      System.out.println(fruit.collect());
    }
  }
}
