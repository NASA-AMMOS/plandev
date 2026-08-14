@MissionModel(model = Mission.class)

@WithConfiguration(Configuration.class)

@WithMappers(FooValueMappers.class)
@WithMappers(BasicValueMappers.class)

@WithActivityType(BasicActivity.class)
@WithActivityType(FooActivity.class)
@WithActivityType(BarActivity.class)
@WithActivityType(SolarPanelNonLinear.class)
@WithActivityType(SolarPanelNonLinearTimeDependent.class)
@WithActivityType(ControllableDurationActivity.class)
@WithActivityType(OtherControllableDurationActivity.class)
@WithActivityType(BasicFooActivity.class)
@WithActivityType(ZeroDurationUncontrollableActivity.class)
@WithActivityType(DaemonCheckerActivity.class)
@WithActivityType(DaemonCheckerSpawner.class)
@WithActivityType(DaemonTaskActivity.class)

@WithActivityType(DecompositionTestActivities.ParentActivity.class)
@WithActivityType(DecompositionTestActivities.ChildActivity.class)
@WithActivityType(LateRiserActivity.class)

package gov.nasa.jpl.aerie.foomissionmodel;

import gov.nasa.jpl.aerie.contrib.serialization.rulesets.BasicValueMappers;
import gov.nasa.jpl.aerie.foomissionmodel.activities.BarActivity;
import gov.nasa.jpl.aerie.foomissionmodel.activities.BasicActivity;
import gov.nasa.jpl.aerie.foomissionmodel.activities.BasicFooActivity;
import gov.nasa.jpl.aerie.foomissionmodel.activities.ControllableDurationActivity;
import gov.nasa.jpl.aerie.foomissionmodel.activities.DaemonCheckerActivity;
import gov.nasa.jpl.aerie.foomissionmodel.activities.DaemonCheckerSpawner;
import gov.nasa.jpl.aerie.foomissionmodel.activities.DaemonTaskActivity;
import gov.nasa.jpl.aerie.foomissionmodel.activities.DecompositionTestActivities;
import gov.nasa.jpl.aerie.foomissionmodel.activities.FooActivity;
import gov.nasa.jpl.aerie.foomissionmodel.activities.LateRiserActivity;
import gov.nasa.jpl.aerie.foomissionmodel.activities.OtherControllableDurationActivity;
import gov.nasa.jpl.aerie.foomissionmodel.activities.SolarPanelNonLinear;
import gov.nasa.jpl.aerie.foomissionmodel.activities.SolarPanelNonLinearTimeDependent;
import gov.nasa.jpl.aerie.foomissionmodel.activities.ZeroDurationUncontrollableActivity;
import gov.nasa.jpl.aerie.foomissionmodel.mappers.FooValueMappers;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel.WithActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel.WithConfiguration;
import gov.nasa.jpl.aerie.merlin.framework.annotations.MissionModel.WithMappers;
