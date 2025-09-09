package gov.nasa.jpl.aerie.contrib.models;

import gov.nasa.jpl.aerie.merlin.framework.resources.NameableResource;

/**
 * Provides a field for setting the string name of the resource.
 */
public abstract class NamedResource<D> implements NameableResource<D> {
  private String name = "ERROR: Name was not set during model construction";

  @Override
  public String getName() {
    return name;
  }

  @Override
  public void setName(final String name) {
    this.name = name;
  }
}
