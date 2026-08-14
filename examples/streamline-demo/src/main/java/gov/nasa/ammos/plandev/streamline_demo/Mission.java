package gov.nasa.jpl.aerie.streamline_demo;

import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.debugging.Profiling;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.Registrar;
import gov.nasa.jpl.aerie.merlin.framework.ModelActions;

public final class Mission {
  public final DataModel dataModel;
  public final ErrorTestingModel errorTestingModel;
  public final ApproximationModel approximationModel;

  public Mission(final gov.nasa.jpl.aerie.merlin.framework.Registrar registrar$, final Configuration config) {
    var registrar = new Registrar(registrar$, Registrar.ErrorBehavior.Log);
    if (config.traceResources) registrar.setTrace();
    if (config.profileResources) Resource.profileAllResources();
    dataModel = new DataModel(registrar, config);
    errorTestingModel = new ErrorTestingModel(registrar, config);
    approximationModel = new ApproximationModel(registrar, config);
    if (config.profilingDumpTime.isPositive()) {
      ModelActions.defer(config.profilingDumpTime, Profiling::dump);
    }
  }
}
