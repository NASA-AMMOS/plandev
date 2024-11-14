package gov.nasa.jpl.aerie.sequence_generation.models;


import gov.nasa.jpl.aerie.contrib.streamline.modeling.Registrar;
import gov.nasa.jpl.aerie.sequence_generation.models.files.FileSystemModel;

public class SequencingFswModel {
  // TODO state to track onboard files (doesn't need to be a resource!)
  // TODO states to track sequence engines

  private final FileSystemModel fileSystem;
  public SequencingFswModel(Registrar registrar, FileSystemModel fileSystem$) {
    fileSystem = fileSystem$;
  }
}
