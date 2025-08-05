@MissionModel(model = Mission.class)

@WithMappers(BasicValueMappers.class)
@WithMetadata(name="banannotation", annotation=Banannotation.class)
@WithMetadata(name="unit", annotation=gov.nasa.jpl.aerie.contrib.metadata.Unit.class)

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
@WithActivityType(RussianNestingBanana.class)
package gov.nasa.jpl.aerie.banananation;

import gov.nasa.jpl.aerie.banananation.activities.BakeBananaBreadActivity;
import gov.nasa.jpl.aerie.banananation.activities.BananaNapActivity;
import gov.nasa.jpl.aerie.banananation.activities.Banannotation;
import gov.nasa.jpl.aerie.banananation.activities.BiteBananaActivity;
import gov.nasa.jpl.aerie.banananation.activities.ChangeProducerActivity;
import gov.nasa.jpl.aerie.banananation.activities.ControllableDurationActivity;
import gov.nasa.jpl.aerie.banananation.activities.DecomposingActivity;
import gov.nasa.jpl.aerie.banananation.activities.DecomposingSpawnActivity;
import gov.nasa.jpl.aerie.banananation.activities.DownloadBananaActivity;
import gov.nasa.jpl.aerie.banananation.activities.DurationParameterActivity;
import gov.nasa.jpl.aerie.banananation.activities.ExceptionActivity;
import gov.nasa.jpl.aerie.banananation.activities.GrowBananaActivity;
import gov.nasa.jpl.aerie.banananation.activities.LineCountBananaActivity;
import gov.nasa.jpl.aerie.banananation.activities.ParameterTestActivity;
import gov.nasa.jpl.aerie.banananation.activities.PeelBananaActivity;
import gov.nasa.jpl.aerie.banananation.activities.PickBananaActivity;
import gov.nasa.jpl.aerie.banananation.activities.RipenBananaActivity;
import gov.nasa.jpl.aerie.banananation.activities.RussianNestingBanana;
import gov.nasa.jpl.aerie.banananation.activities.ThrowBananaActivity;
import gov.nasa.jpl.aerie.contrib.serialization.rulesets.BasicValueMappers;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel.WithActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel.WithMetadata;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel.WithConfiguration;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel.WithMappers;
