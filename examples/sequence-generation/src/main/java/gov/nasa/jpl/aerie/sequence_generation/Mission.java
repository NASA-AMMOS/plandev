package gov.nasa.jpl.aerie.sequence_generation;


import gov.nasa.jpl.aerie.contrib.streamline.modeling.Registrar;
import gov.nasa.jpl.aerie.sequence_generation.models.SequencingFswModel;
import gov.nasa.jpl.aerie.sequence_generation.models.SpacecraftModel;
import gov.nasa.jpl.aerie.sequence_generation.models.files.FileSystemModel;

import java.time.Instant;

public class Mission {
  public final FileSystemModel fileSystem;
  public final SequencingFswModel sequencingFsw;
  public final SpacecraftModel spacecraft;

  public Mission(final gov.nasa.jpl.aerie.merlin.framework.Registrar coreRegistrar, Instant planStart, final Configuration config) {
    var registrar = new Registrar(coreRegistrar, Registrar.ErrorBehavior.Log);

    fileSystem = new FileSystemModel(registrar);
    sequencingFsw = new SequencingFswModel(registrar, fileSystem);
    spacecraft = new SpacecraftModel(registrar);

  }
}
