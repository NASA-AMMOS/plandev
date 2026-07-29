package gov.nasa.ammos.plandev.examples.model.migration.activities;

import gov.nasa.ammos.plandev.examples.model.migration.Mission;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export.Parameter;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export.Validation;

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
