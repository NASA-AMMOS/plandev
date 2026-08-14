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

package gov.nasa.ammos.plandev.foomissionmodel;

import gov.nasa.ammos.plandev.contrib.serialization.rulesets.BasicValueMappers;
import gov.nasa.ammos.plandev.foomissionmodel.activities.BarActivity;
import gov.nasa.ammos.plandev.foomissionmodel.activities.BasicActivity;
import gov.nasa.ammos.plandev.foomissionmodel.activities.BasicFooActivity;
import gov.nasa.ammos.plandev.foomissionmodel.activities.ControllableDurationActivity;
import gov.nasa.ammos.plandev.foomissionmodel.activities.DaemonCheckerActivity;
import gov.nasa.ammos.plandev.foomissionmodel.activities.DaemonCheckerSpawner;
import gov.nasa.ammos.plandev.foomissionmodel.activities.DaemonTaskActivity;
import gov.nasa.ammos.plandev.foomissionmodel.activities.DecompositionTestActivities;
import gov.nasa.ammos.plandev.foomissionmodel.activities.FooActivity;
import gov.nasa.ammos.plandev.foomissionmodel.activities.LateRiserActivity;
import gov.nasa.ammos.plandev.foomissionmodel.activities.OtherControllableDurationActivity;
import gov.nasa.ammos.plandev.foomissionmodel.activities.SolarPanelNonLinear;
import gov.nasa.ammos.plandev.foomissionmodel.activities.SolarPanelNonLinearTimeDependent;
import gov.nasa.ammos.plandev.foomissionmodel.activities.ZeroDurationUncontrollableActivity;
import gov.nasa.ammos.plandev.foomissionmodel.mappers.FooValueMappers;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel.WithActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel.WithConfiguration;
import gov.nasa.ammos.plandev.merlin.framework.annotations.MissionModel.WithMappers;
