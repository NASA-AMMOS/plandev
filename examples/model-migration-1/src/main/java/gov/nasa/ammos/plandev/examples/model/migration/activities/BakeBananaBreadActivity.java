package gov.nasa.jpl.aerie.examples.model.migration.activities;

import gov.nasa.jpl.aerie.examples.model.migration.Mission;
import gov.nasa.jpl.aerie.contrib.models.ValidationResult;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Validation;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.WithDefaults;

@ActivityType("BakeBananaBread")
public record BakeBananaBreadActivity(double temperature, int tbSugar, boolean glutenFree) {

  @Validation
  public ValidationResult validateTemperatures() {
    if (this.temperature < 0) {
      return new ValidationResult(false, "temperature", "Temperature must be positive");
    }

    return new ValidationResult(!glutenFree || temperature >= 100,
      "glutenFree",
      "Gluten-free bread must be baked at a temperature >= 100");
  }

  @EffectModel
  public int run(final Mission mission) {
    return 0;
  }

  public static @WithDefaults final class Defaults {
    public static double temperature = 350.0;
  }
}
