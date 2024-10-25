@MissionModel(model = Mission.class)

@WithConfiguration(Configuration.class)

@WithMappers(BasicValueMappers.class)

// Planning activities
@WithActivityType(Warm_Up.class)
@WithActivityType(Do_Observation_Shallow.class)
@WithActivityType(Do_Observation_Moderate.class)
@WithActivityType(Do_Observation_Deep.class)
@WithActivityType(Conditional_Warm_Up.class)

// Command activities
@WithActivityType(CMD_NO_OP.class)
@WithActivityType(PWR_Turn_On_Heater.class)
@WithActivityType(PWR_Turn_Off_Heater.class)
@WithActivityType(SCI_Do_Observation_Moderate.class)
@WithActivityType(SCI_Do_Observation_Deep.class)
@WithActivityType(SCI_Warm_Up_Moderate.class)
@WithActivityType(SCI_Warm_Up_Deep.class)
@WithActivityType(SEQ_ECHO.class)
@WithActivityType(SEQ_ELSE.class)
@WithActivityType(SEQ_END_IF.class)
@WithActivityType(SEQ_IF.class)
@WithActivityType(SEQ_SET_FLOAT.class)
@WithActivityType(SEQ_SET_INT.class)
@WithActivityType(SEQ_SET_STRING.class)

package gov.nasa.jpl.aerie.command_expansion;

import gov.nasa.jpl.aerie.command_expansion.command_activities.*;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.command_expansion.planning_activities.*;
import gov.nasa.jpl.aerie.command_expansion.planning_activities.Warm_Up;
import gov.nasa.jpl.aerie.contrib.serialization.rulesets.BasicValueMappers;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel.WithActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel.WithConfiguration;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel.WithMappers;
