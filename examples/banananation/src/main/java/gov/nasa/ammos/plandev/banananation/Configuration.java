package gov.nasa.jpl.aerie.banananation;

import gov.nasa.jpl.aerie.contrib.metadata.Unit;
import gov.nasa.jpl.aerie.merlin.framework.annotations.AutoValueMapper;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;

import java.nio.file.Path;

import static gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Template;

public record Configuration(@Unit("count") int initialPlantCount, String initialProducer, Path initialDataPath, InitialConditions initialConditions) {

  public static final int DEFAULT_PLANT_COUNT = 200;
  public static final String DEFAULT_PRODUCER = "Chiquita";
  public static final Path DEFAULT_DATA_PATH = Path.of("/etc/os-release");
  public static final InitialConditions DEFAULT_INITIAL_CONDITIONS = new InitialConditions(
      4.0,
      4.0,
      Flag.A
  );

  @Export.Validation("plant count must be positive")
  public boolean validateInitialPlantCount() {
    return this.initialPlantCount > 0;
  }

  @Export.Validation("data path must exist")
  public boolean validateInitialDataPath() {
    return initialDataPath.toFile().exists();
  }

  public static @Template Configuration defaultConfiguration() {
    return new Configuration(DEFAULT_PLANT_COUNT, DEFAULT_PRODUCER, DEFAULT_DATA_PATH, DEFAULT_INITIAL_CONDITIONS);
  }

  @AutoValueMapper.Record
  public record InitialConditions(@Unit("bananas") double fruit, @Unit("kg") double peel, Flag flag) {}
}
