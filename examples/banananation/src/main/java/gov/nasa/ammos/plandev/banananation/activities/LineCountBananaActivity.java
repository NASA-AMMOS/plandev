package gov.nasa.jpl.aerie.banananation.activities;

import gov.nasa.jpl.aerie.banananation.Mission;
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
  public Path path = Path.of("/etc/os-release"); // TODO Make this a required parameter when required parameters are fully
                                                 //  supported. As a placeholder this defaults to a file that should exist.

  @Validation("path must exist")
  @Validation.Subject("path")
  public boolean validatePath() {
    return Files.exists(path);
  }

  @EffectModel
  public void run(final Mission mission) {
    try {
      mission.lineCount.set((int)Files.lines(path).count());
    } catch (IOException e) {
      throw new Error(e);
    }
  }
}
