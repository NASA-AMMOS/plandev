package gov.nasa.ammos.plandev.banananation;

import gov.nasa.ammos.plandev.contrib.models.Accumulator;
import gov.nasa.ammos.plandev.contrib.models.Register;
import gov.nasa.ammos.plandev.contrib.models.counters.Counter;
import gov.nasa.ammos.plandev.contrib.serialization.mappers.BooleanValueMapper;
import gov.nasa.ammos.plandev.contrib.serialization.mappers.DoubleValueMapper;
import gov.nasa.ammos.plandev.contrib.serialization.mappers.EnumValueMapper;
import gov.nasa.ammos.plandev.contrib.serialization.mappers.IntegerValueMapper;
import gov.nasa.ammos.plandev.contrib.serialization.mappers.StringValueMapper;
import gov.nasa.ammos.plandev.merlin.framework.Registrar;
import gov.nasa.ammos.plandev.spice.SpiceLoader;
import spice.basic.CSPICE;
import spice.basic.SpiceErrorException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static gov.nasa.ammos.plandev.contrib.metadata.UnitRegistrar.discreteResource;
import static gov.nasa.ammos.plandev.contrib.metadata.UnitRegistrar.realResource;

public final class Mission {
  /**
   * How many stress-test resources of each kind exist. The StressResources activity drives the
   * first N of each, so users can vary the number of large profiles on a timeline without
   * rebuilding the model.
   */
  public static final int STRESS_RESOURCE_POOL_SIZE = 4;

  public final Accumulator fruit;
  public final AdditiveRegister peel;
  public final Register<Flag> flag;
  public final Register<Integer> lineCount = Register.forImmutable(0);
  public final Counter<Integer> plant;
  public final Register<String> producer;
  public final Register<Integer> dataLineCount;

  /**
   * Real-valued stress resources, exercising the linear-dynamics profile shape ({initial, rate})
   * that real mission resources use. Rendered as line layers.
   */
  public final List<Accumulator> stressReal;

  /**
   * String-valued stress resources, rendered as x-range layers. Kept separate from the real pool
   * because the two drive different timeline draw paths.
   */
  public final List<Register<String>> stressDiscrete;

  public Mission(final Registrar registrar, final Configuration config) {
    this.fruit = new Accumulator(config.initialConditions().fruit(), 0.0);
    this.peel = AdditiveRegister.create(config.initialConditions().peel());
    this.flag = Register.forImmutable(config.initialConditions().flag());
    this.plant = Counter.ofInteger(config.initialPlantCount());
    this.producer = Register.forImmutable(config.initialProducer());
    this.dataLineCount = Register.forImmutable(countLines(config.initialDataPath()));

    registrar.discrete("/flag", this.flag, new EnumValueMapper<>(Flag.class), "The flag set");
    registrar.discrete("/flag/conflicted", this.flag::isConflicted, new BooleanValueMapper());
    discreteResource(registrar, "/peel", this.peel, new DoubleValueMapper(), "kg");
    realResource(registrar, "/fruit", this.fruit, "bananas", "The number of fruits collected");
    discreteResource(registrar, "/plant", this.plant, new IntegerValueMapper(), "count");
    registrar.discrete("/producer", this.producer, new StringValueMapper(), "The producer of the fruit");
    registrar.discrete("/data/line_count", this.dataLineCount, new IntegerValueMapper());
    registrar.topic("/producer", this.producer.ref, new StringValueMapper());

    // Stress-test resources. Idle (one segment each) unless a StressResources activity drives
    // them, so they cost nothing in normal use.
    final var stressRealPool = new ArrayList<Accumulator>(STRESS_RESOURCE_POOL_SIZE);
    final var stressDiscretePool = new ArrayList<Register<String>>(STRESS_RESOURCE_POOL_SIZE);
    for (int i = 0; i < STRESS_RESOURCE_POOL_SIZE; i++) {
      final var real = new Accumulator(0.0, 0.0);
      realResource(registrar, "/stress/real/" + i, real, "bananas", "Stress-test resource for timeline performance");
      stressRealPool.add(real);

      final var discrete = Register.<String>forImmutable("idle");
      discreteResource(
          registrar,
          "/stress/discrete/" + i,
          discrete,
          new StringValueMapper(),
          "state",
          "Stress-test resource for timeline performance");
      stressDiscretePool.add(discrete);
    }
    this.stressReal = List.copyOf(stressRealPool);
    this.stressDiscrete = List.copyOf(stressDiscretePool);

    // Load SPICE in the Mission constructor
    try {
      SpiceLoader.loadSpice();
      System.out.println(CSPICE.ktotal("ALL"));
    } catch (final SpiceErrorException ex) {
      throw new Error(ex);
    }
  }

  private static int countLines(final Path path) {
    try {
      return (int)Files.lines(path).count();
    } catch (IOException e) {
      throw new Error(e);
    }
  }
}
