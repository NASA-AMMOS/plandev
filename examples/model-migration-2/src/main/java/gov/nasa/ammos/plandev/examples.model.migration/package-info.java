@MissionModel(model = Mission.class)

@WithConfiguration(Configuration.class)

@WithMappers(BasicValueMappers.class)

@WithActivityType(BakeBananaBreadActivity.class)
@WithActivityType(BiteBananaActivity.class)
@WithActivityType(ChangeProducerActivity.class)
@WithActivityType(GrowBananaActivity.class)
@WithActivityType(LineCountBananaActivity.class)
@WithActivityType(PeelBananaActivity.class)
@WithActivityType(PickBananaActivity.class)
@WithActivityType(NewDurationParameterActivity.class)

@WithMetadata(name="unit", annotation=gov.nasa.jpl.aerie.contrib.metadata.Unit.class)

package gov.nasa.jpl.aerie.examples.model.migration;

import gov.nasa.jpl.aerie.contrib.serialization.rulesets.BasicValueMappers;
import gov.nasa.jpl.aerie.examples.model.migration.activities.BakeBananaBreadActivity;
import gov.nasa.jpl.aerie.examples.model.migration.activities.BiteBananaActivity;
import gov.nasa.jpl.aerie.examples.model.migration.activities.ChangeProducerActivity;
import gov.nasa.jpl.aerie.examples.model.migration.activities.GrowBananaActivity;
import gov.nasa.jpl.aerie.examples.model.migration.activities.LineCountBananaActivity;
import gov.nasa.jpl.aerie.examples.model.migration.activities.NewDurationParameterActivity;
import gov.nasa.jpl.aerie.examples.model.migration.activities.PeelBananaActivity;
import gov.nasa.jpl.aerie.examples.model.migration.activities.PickBananaActivity;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel.WithConfiguration;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel.WithMappers;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel.WithActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel.WithMetadata;
