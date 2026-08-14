package gov.nasa.jpl.aerie.examples.model.migration.activities;

import gov.nasa.jpl.aerie.examples.model.migration.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Parameter;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@ActivityType("LineCount")
public final class LineCountBananaActivity {

  @Parameter
  public Path file = Path.of("/etc/os-release");

  @Validation("path must exist")
  @Validation.Subject("file")
  public boolean validatePath() {
    return Files.exists(file);
  }

  @EffectModel
  public void run(final Mission mission) {
  }
}
