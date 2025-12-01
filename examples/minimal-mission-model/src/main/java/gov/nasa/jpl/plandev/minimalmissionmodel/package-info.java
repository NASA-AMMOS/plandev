@MissionModel(model = Mission.class)

@WithConfiguration(Configuration.class)

@WithMappers(BasicValueMappers.class)

@WithActivityType(SingleActivity.class)

package gov.nasa.jpl.plandev.minimalmissionmodel;

import gov.nasa.jpl.plandev.contrib.serialization.rulesets.BasicValueMappers;
import gov.nasa.jpl.plandev.merlin.framework.annotations.MissionModel;
import gov.nasa.jpl.plandev.merlin.framework.annotations.MissionModel.WithConfiguration;
import gov.nasa.jpl.plandev.merlin.framework.annotations.MissionModel.WithMappers;
import gov.nasa.jpl.plandev.merlin.framework.annotations.MissionModel.WithActivityType;
