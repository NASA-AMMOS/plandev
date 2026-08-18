@MissionModel(model = Mission.class)

@WithConfiguration(Configuration.class)

@WithMappers(BasicValueMappers.class)

@WithActivityType(SingleActivity.class)

package gov.nasa.ammos.plandev.minimalmissionmodel;

import gov.nasa.ammos.plandev.contrib.serialization.rulesets.BasicValueMappers;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel.WithConfiguration;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel.WithMappers;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel.WithActivityType;
