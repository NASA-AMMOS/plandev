package gov.nasa.jpl.aerie.foomissionmodel.activities;

import gov.nasa.jpl.aerie.foomissionmodel.Mission;
import gov.nasa.jpl.aerie.foomissionmodel.models.ImagerMode;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;

import java.util.List;

import static gov.nasa.jpl.aerie.foomissionmodel.generated.ActivityActions.call;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.*;
import static gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import static gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Parameter;
import static gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Validation;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;

@ActivityType("foo")
public final class FooActivity {
  @Parameter
  public int x = 0;

  @Parameter
  public String y = "test";

  @Parameter
  public Integer z; // No default value specified, therefore this parameter is required

  @Parameter
  public List<Vector3D> vecs = List.of(new Vector3D(0.0, 0.0, 0.0));

  @Validation("x cannot be exactly 99")
  @Validation.Subject("x")
  public boolean validateX() {
    return (x != 99);
  }

  @Validation("y cannot be 'bad'")
  @Validation.Subject("y")
  public boolean validateY() {
    return !y.equals("bad");
  }

  @EffectModel
  public void run(final Mission mission) {
    final var data = mission.data;
    final var complexData = mission.complexData;

    complexData.beginImaging(ImagerMode.HI_RES, 60);

    if (y.equals("test")) {
      data.rate.add(x);
    } else if (y.equals("spawn")) {
      call(mission, new FooActivity());
    }

    data.rate.add(1.0);
    delay(1, SECOND);
    waitUntil(data.isBetween(50.0, 100.0));

    mission.simpleData.downlinkData();

    data.rate.add(2.0);
    data.rate.add(data.rate.get());
    delay(10, SECOND);

    complexData.endImaging();

    mission.simpleData.a.deactivate();
    mission.simpleData.b.deactivate();
    delay(1, SECOND);

    mission.activitiesExecuted.add(1);
  }
}
