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

@WithMetadata(name="unit", annotation=gov.nasa.ammos.plandev.contrib.metadata.Unit.class)

package gov.nasa.ammos.plandev.examples.model.migration;

import gov.nasa.ammos.plandev.contrib.serialization.rulesets.BasicValueMappers;
import gov.nasa.ammos.plandev.examples.model.migration.activities.BakeBananaBreadActivity;
import gov.nasa.ammos.plandev.examples.model.migration.activities.BiteBananaActivity;
import gov.nasa.ammos.plandev.examples.model.migration.activities.ChangeProducerActivity;
import gov.nasa.ammos.plandev.examples.model.migration.activities.GrowBananaActivity;
import gov.nasa.ammos.plandev.examples.model.migration.activities.LineCountBananaActivity;
import gov.nasa.ammos.plandev.examples.model.migration.activities.NewDurationParameterActivity;
import gov.nasa.ammos.plandev.examples.model.migration.activities.PeelBananaActivity;
import gov.nasa.ammos.plandev.examples.model.migration.activities.PickBananaActivity;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel.WithConfiguration;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel.WithMappers;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel.WithActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel.WithMetadata;
