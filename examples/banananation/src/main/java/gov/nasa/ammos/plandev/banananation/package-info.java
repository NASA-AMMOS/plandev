@MissionModel(model = Mission.class)

@WithMappers(BasicValueMappers.class)
//@WithMetadata(name="banannotation", annotation=Banannotation.class)
@WithMetadata(name="unit", annotation=gov.nasa.ammos.plandev.contrib.metadata.Unit.class)
@WithMetadata(name="description", annotation=gov.nasa.ammos.plandev.merlin.framework.annotations.Description.class)

@WithConfiguration(Configuration.class)

@WithActivityType(BiteBananaActivity.class)
@WithActivityType(PeelBananaActivity.class)
@WithActivityType(ParameterTestActivity.class)
@WithActivityType(PickBananaActivity.class)
@WithActivityType(ChangeProducerActivity.class)
@WithActivityType(ThrowBananaActivity.class)
@WithActivityType(GrowBananaActivity.class)
@WithActivityType(LineCountBananaActivity.class)
@WithActivityType(DecomposingActivity.ParentActivity.class)
@WithActivityType(DecomposingActivity.ChildActivity.class)
@WithActivityType(DecomposingActivity.GrandchildActivity.class)
@WithActivityType(DecomposingSpawnActivity.DecomposingSpawnParentActivity.class)
@WithActivityType(DecomposingSpawnActivity.DecomposingSpawnChildActivity.class)
@WithActivityType(DownloadBananaActivity.class)
@WithActivityType(BakeBananaBreadActivity.class)
@WithActivityType(BananaNapActivity.class)
@WithActivityType(DurationParameterActivity.class)
@WithActivityType(ControllableDurationActivity.class)
@WithActivityType(RipenBananaActivity.class)
@WithActivityType(ExceptionActivity.class)

@WithSubsystem("Prepare")
@WithSubsystem("Eat")
@WithSubsystem("Pick")

package gov.nasa.ammos.plandev.banananation;

import gov.nasa.ammos.plandev.banananation.activities.BakeBananaBreadActivity;
import gov.nasa.ammos.plandev.banananation.activities.BananaNapActivity;
//import gov.nasa.ammos.plandev.banananation.activities.Banannotation;
import gov.nasa.ammos.plandev.banananation.activities.BiteBananaActivity;
import gov.nasa.ammos.plandev.banananation.activities.ChangeProducerActivity;
import gov.nasa.ammos.plandev.banananation.activities.ControllableDurationActivity;
import gov.nasa.ammos.plandev.banananation.activities.DecomposingActivity;
import gov.nasa.ammos.plandev.banananation.activities.DecomposingSpawnActivity;
import gov.nasa.ammos.plandev.banananation.activities.DownloadBananaActivity;
import gov.nasa.ammos.plandev.banananation.activities.DurationParameterActivity;
import gov.nasa.ammos.plandev.banananation.activities.ExceptionActivity;
import gov.nasa.ammos.plandev.banananation.activities.GrowBananaActivity;
import gov.nasa.ammos.plandev.banananation.activities.LineCountBananaActivity;
import gov.nasa.ammos.plandev.banananation.activities.ParameterTestActivity;
import gov.nasa.ammos.plandev.banananation.activities.PeelBananaActivity;
import gov.nasa.ammos.plandev.banananation.activities.PickBananaActivity;
import gov.nasa.ammos.plandev.banananation.activities.RipenBananaActivity;
import gov.nasa.ammos.plandev.banananation.activities.ThrowBananaActivity;
import gov.nasa.ammos.plandev.contrib.serialization.rulesets.BasicValueMappers;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel.WithActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel.WithMetadata;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel.WithConfiguration;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel.WithMappers;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel.WithSubsystem;
