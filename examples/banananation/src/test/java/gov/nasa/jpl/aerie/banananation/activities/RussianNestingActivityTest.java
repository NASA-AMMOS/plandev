package gov.nasa.jpl.aerie.banananation.activities;

import gov.nasa.jpl.aerie.banananation.SimulationUtility;
import gov.nasa.jpl.aerie.banananation.generated.activities.RussianNestingBananaMapper;
import gov.nasa.jpl.aerie.merlin.driver.SerializedActivity;
import gov.nasa.jpl.aerie.merlin.framework.junit.MerlinExtension;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MILLISECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MINUTE;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.duration;

@ExtendWith(MerlinExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RussianNestingActivityTest {
  private final RussianNestingBananaMapper mapper;

  public RussianNestingActivityTest() {
    this.mapper = new RussianNestingBananaMapper();
  }

  @Test
  public void testDefaultSimulationDoesNotThrow() {
    final var schedule = SimulationUtility.buildSchedule(
        Pair.of(
            duration(1100, MILLISECONDS),
            new SerializedActivity("RussianNestingBanana",
                                   Map.of("pickBananaActivityRecord",
                                          SerializedValue.of(Map.of("id",
                                                                    SerializedValue.of(2),
                                                                    "pickBananaActivity",
                                                                    SerializedValue.of(Map.of("quantity",
                                                                                              SerializedValue.of(10))))),
                                          "pickBananaQuantityOverride", SerializedValue.of(3),
                                          "biteBananaActivity", SerializedValue.of(List.of(SerializedValue.of(Map.of("biteSize",
                                                                                                                     SerializedValue.of(7.0))),
                                                                                           SerializedValue.of(Map.of("biteSize",
                                                                                                                     SerializedValue.of(1.0))))),
                                          "peelBananaActivity", SerializedValue.of(Map.of("peelDirection",
                                                                                          SerializedValue.of("fromStem")))))));

    final var simulationDuration = duration(5, SECONDS);

    var simulationResults = SimulationUtility.simulate(schedule, simulationDuration);

    // verify results
    // 1. Plant count decreased by 3 (200 -> 197) from the pickBananaActivity call using pickBananaQuantityOverride
    var finalPlantCount = simulationResults.discreteProfiles.get("/plant").getValue().get(simulationResults.discreteProfiles.get("/plant").getValue().size()-1).dynamics().asInt().orElseThrow();
    assert(finalPlantCount == 197);

    // 2. Verify first bite banana activity ran, decrementing "/fruit" by 7 (from 4 to -3) in total and setting flag to flag B
    var finalFruitCount = simulationResults.realProfiles.get("/fruit").getValue().get(simulationResults.realProfiles.get("/fruit").getValue().size()-1).dynamics().initial;
    var finalFlagState = simulationResults.discreteProfiles.get("/flag").getValue().get(simulationResults.discreteProfiles.get("/flag").getValue().size()-1).dynamics().asString().orElseThrow();

    assert(finalFruitCount == 4.0 - 7.0);
    assert(finalFlagState == "B");

    // 3. Verify second bite banana activity ran, decrementing "/fruit" by 1 (from -3 to -4) in total and setting flag to flag A
    simulationResults = SimulationUtility.simulate(schedule, duration(35, MINUTE));
    finalFruitCount = simulationResults.realProfiles.get("/fruit").getValue().get(simulationResults.realProfiles.get("/fruit").getValue().size()-1).dynamics().initial;
    finalFlagState = simulationResults.discreteProfiles.get("/flag").getValue().get(simulationResults.discreteProfiles.get("/flag").getValue().size()-1).dynamics().asString().orElseThrow();
    assert(finalFruitCount == 4.0 - 8.0);
    assert(finalFlagState == "A");

    simulationResults = SimulationUtility.simulate(schedule, duration(65, MINUTE));
    // 4. Verify peel banana activity ran educing frruit by 1.0 and peel by 1.0 to -5.0 and 3.0 respectively
    finalFruitCount = simulationResults.realProfiles.get("/fruit").getValue().get(simulationResults.realProfiles.get("/fruit").getValue().size()-1).dynamics().initial;
    var finalPeelState = simulationResults.discreteProfiles.get("/peel").getValue().get(simulationResults.discreteProfiles.get("/peel").getValue().size()-1).dynamics().asReal().orElseThrow();
    assert(finalFruitCount == 4.0 - 9.0);
    assert(finalPeelState == 3.0);
  }

}
