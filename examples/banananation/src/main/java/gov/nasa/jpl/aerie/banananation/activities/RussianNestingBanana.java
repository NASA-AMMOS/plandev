package gov.nasa.jpl.aerie.banananation.activities;

import gov.nasa.jpl.aerie.banananation.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.AutoValueMapper;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.List;
import java.util.Optional;

import static gov.nasa.jpl.aerie.banananation.generated.ActivityActions.call;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;

/**
 * RussianNestingBanana nests activity types as parameters inside an activity
 *
 * This activity tests the use of activity types as parameters and within compound type parameters. There are a few use cases:
 * 1. Basic activity type parameter which is passed through and called without parent level modeling
 * 2. List of activity types which are just iterated through and called
 * 3. Using a parent level parameter to override information in the call to a child from an activity type parameter
 *
 * @subsystem fruit
 * @contact John Doe
 */
@ActivityType("RussianNestingBanana")
public final class RussianNestingBanana {

  /** Record encapsulating an activity type **/
  @AutoValueMapper.Record
  public record pickBananaWithId(
      int id,
      PickBananaActivity pickBananaActivity
  ) {}

  /** Record type parameter encapsulating an activity type **/
  @Export.Parameter
  public pickBananaWithId pickBananaActivityRecord;

  /** Parent level override parameter, in this case to override call to peel banana
   * found in the pickBannaActivityRecord parameter **/
  @Export.Parameter
  public int pickBananaQuantityOverride = 0;

  /** List of activity type parameter example **/
  @Export.Parameter
  public List<BiteBananaActivity> biteBananaActivity;

  /** Vanilla activity type parameter **/
  @Export.Parameter
  public PeelBananaActivity peelBananaActivity;


  @ActivityType.EffectModel
  public void run(final Mission mission) {
    // if the pickBananaQuantityOverride is preset use that integer instead of the pickBananaActivityRecord
    if(pickBananaQuantityOverride != 0) {
      PickBananaActivity pickBananaActivity = pickBananaActivityRecord.pickBananaActivity;
      pickBananaActivity.quantity = pickBananaQuantityOverride;
      call(mission, pickBananaActivity);
    } else { // else use the record type parameter supplied
      call(mission, pickBananaActivityRecord.pickBananaActivity());
    }
    // call a bite banana for each element in the list of biteBanana activities
    for (final var bite : biteBananaActivity) {
      call(mission, bite);
      delay(Duration.of(30, Duration.MINUTE));
    }
    // call peel banana activity
    call(mission, peelBananaActivity);
  }
}
