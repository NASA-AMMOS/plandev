package gov.nasa.jpl.aerie.sequence_generation.models;

import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.Registrar;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;

import static gov.nasa.jpl.aerie.contrib.serialization.rulesets.BasicValueMappers.$enum;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.discreteResource;

public class SpacecraftModel {
  public MutableResource<Discrete<RadioState>> radioState = discreteResource(RadioState.ON);
  public SpacecraftModel(Registrar registrar) {
    registrar.discrete("radioState", radioState, $enum(RadioState.class));
  }

  public enum RadioState {
    OFF,
    WARMUP,
    ON
  }
}
