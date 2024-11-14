package gov.nasa.jpl.aerie.sequence_generation.models.files;

import gov.nasa.jpl.aerie.contrib.serialization.mappers.IntegerValueMapper;
import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.Registrar;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;

import java.util.HashMap;

import static gov.nasa.jpl.aerie.contrib.serialization.rulesets.BasicValueMappers.$int;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.discreteResource;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteResourceMonad.map;

public class FileSystemModel {
  public MutableResource<Discrete<HashMap<String, OnboardFile>>> onboardFiles;

  public FileSystemModel(Registrar registrar) {
    onboardFiles = discreteResource(new HashMap<>());
    registrar.discrete("numOnboardFiles", map(onboardFiles, onboardFiles -> onboardFiles.size()), $int());
  }

  void addOnboardFile(String path, String contents, Boolean overwrite) {
    // TODO implement
  }

  void addOnboardFile(String path, String contents) {
    addOnboardFile(path, contents, Boolean.FALSE);
  }
}
