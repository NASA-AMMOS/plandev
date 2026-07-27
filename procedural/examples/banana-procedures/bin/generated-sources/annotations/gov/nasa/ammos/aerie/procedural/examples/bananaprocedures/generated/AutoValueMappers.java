package gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.generated;

import gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints.ConstFruit;
import gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints.FruitThreshold;
import gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints.ObeyConservationOfBanana;
import gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures.SampleProcedure;
import gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures.SimulationDemo;
import gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures.StayWellFed;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.RecordValueMapper;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("gov.nasa.ammos.aerie.procedural.processor.ProcedureProcessor")
@SuppressWarnings("unchecked")
public final class AutoValueMappers {
  public static ValueMapper<ObeyConservationOfBanana> gov_nasa_ammos_aerie_procedural_examples_bananaprocedures_constraints_ObeyConservationOfBanana(
      ) {
    return new RecordValueMapper<>(
      ObeyConservationOfBanana.class,
      List.of(
      ));
  }

  public static ValueMapper<SampleProcedure> gov_nasa_ammos_aerie_procedural_examples_bananaprocedures_procedures_SampleProcedure(
      final ValueMapper<Integer> quantity_ValueMapper) {
    return new RecordValueMapper<>(
      SampleProcedure.class,
      List.of(
        new RecordValueMapper.Component<>(
          "quantity",
          SampleProcedure::quantity,
          quantity_ValueMapper)));
  }

  public static ValueMapper<SimulationDemo> gov_nasa_ammos_aerie_procedural_examples_bananaprocedures_procedures_SimulationDemo(
      final ValueMapper<Integer> quantity_ValueMapper) {
    return new RecordValueMapper<>(
      SimulationDemo.class,
      List.of(
        new RecordValueMapper.Component<>(
          "quantity",
          SimulationDemo::quantity,
          quantity_ValueMapper)));
  }

  public static ValueMapper<FruitThreshold> gov_nasa_ammos_aerie_procedural_examples_bananaprocedures_constraints_FruitThreshold(
      final ValueMapper<Integer> threshold_ValueMapper) {
    return new RecordValueMapper<>(
      FruitThreshold.class,
      List.of(
        new RecordValueMapper.Component<>(
          "threshold",
          FruitThreshold::threshold,
          threshold_ValueMapper)));
  }

  public static ValueMapper<StayWellFed> gov_nasa_ammos_aerie_procedural_examples_bananaprocedures_procedures_StayWellFed(
      final ValueMapper<Double> bitePeriodHours_ValueMapper) {
    return new RecordValueMapper<>(
      StayWellFed.class,
      List.of(
        new RecordValueMapper.Component<>(
          "bitePeriodHours",
          StayWellFed::bitePeriodHours,
          bitePeriodHours_ValueMapper)));
  }

  public static ValueMapper<ConstFruit> gov_nasa_ammos_aerie_procedural_examples_bananaprocedures_constraints_ConstFruit(
      ) {
    return new RecordValueMapper<>(
      ConstFruit.class,
      List.of(
      ));
  }
}
