package gov.nasa.jpl.aerie.streamline_demo;

import gov.nasa.jpl.aerie.contrib.streamline.StreamlineSystem;
import gov.nasa.jpl.aerie.contrib.streamline.StreamlineSystem.InitArgs;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.debugging.Profiling;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.Registrar;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.Registration;
import gov.nasa.jpl.aerie.merlin.framework.ModelActions;

import java.time.Instant;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.Registration.REGISTRAR;

public final class Mission {
  public final DataModel dataModel;
  public final ErrorTestingModel errorTestingModel;
  public final ApproximationModel approximationModel;

  public Mission(final gov.nasa.jpl.aerie.merlin.framework.Registrar registrar$, Instant planStart, final Configuration config) {
    StreamlineSystem.init(InitArgs.builder()
            .baseRegistrar(registrar$)
            .planStart(planStart)
            .errorBehavior(Registrar.ErrorBehavior.Log)
            .build());
      if (config.traceResources) REGISTRAR.setTrace();
    if (config.profileResources) Resource.profileAllResources();
    dataModel = new DataModel(REGISTRAR, config);
    errorTestingModel = new ErrorTestingModel(REGISTRAR, config);
    approximationModel = new ApproximationModel(REGISTRAR, config);
    if (config.profilingDumpTime.isPositive()) {
      ModelActions.defer(config.profilingDumpTime, Profiling::dump);
    }
  }
}
