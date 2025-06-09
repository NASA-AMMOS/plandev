package gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.standalone;

import gov.nasa.jpl.aerie.banananation.Configuration;
import gov.nasa.jpl.aerie.banananation.Mission;
import gov.nasa.jpl.aerie.banananation.generated.GeneratedModelType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
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
      var results =
          simulationUtility.simulate(missionModel, plan).get();
      //var latest = new MerlinToProcedureSimulationResultsAdapter(results, plan);
      System.out.println(results.realProfiles);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }
}
