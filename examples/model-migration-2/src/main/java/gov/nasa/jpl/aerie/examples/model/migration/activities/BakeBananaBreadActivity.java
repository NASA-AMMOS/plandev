package gov.nasa.jpl.aerie.examples.model.migration.activities;

import gov.nasa.jpl.aerie.examples.model.migration.Mission;
import gov.nasa.jpl.aerie.contrib.models.ValidationResult;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.AutoValueMapper;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Validation;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.WithDefaults;

@ActivityType("BakeBananaBread")
public record BakeBananaBreadActivity(Temperature temperature, int tbSugar, boolean glutenFree) {

  public enum Scale {
    C,
    F,
    K
  }

  @AutoValueMapper.Record
  public record Temperature(double value, Scale scale) {}

  @Validation
  public ValidationResult validateTemperatures() {
    if (this.temperature.value() < 0) {
      return new ValidationResult(false, "temperature", "Temperature must be positive");
    }

    return new ValidationResult(!glutenFree || temperature.value() >= 100,
      "glutenFree",
      "Gluten-free bread must be baked at a temperature >= 100");
  }

  @EffectModel
  public int run(final Mission mission) {
    return 0;
  }

  public static @WithDefaults final class Defaults {
    public static Temperature temperature = new Temperature(350.0, Scale.F);
  }
}
