package gov.nasa.ammos.plandev.banananation.activities;

import gov.nasa.ammos.plandev.banananation.Mission;
import gov.nasa.ammos.plandev.contrib.metadata.Unit;
import gov.nasa.ammos.plandev.contrib.models.ValidationResult;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Description;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export.Validation;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export.WithDefaults;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Subsystem;

@ActivityType("BakeBananaBread")
@Subsystem("Prepare")
@Description("Bakes banana bread at a certain temperature")
public record BakeBananaBreadActivity(@Description("The baking temperature in degrees Fahrenheit") double temperature,
                                      @Description("Tablespoons of sugar to add") @Unit("tbl") int tbSugar,
                                      boolean glutenFree) {

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
    mission.plant.add(-2);
    return mission.plant.get();
  }

  public static @WithDefaults final class Defaults {
    public static double temperature = 350.0;
  }
}
