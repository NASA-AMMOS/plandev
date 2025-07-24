package gov.nasa.jpl.aerie.merlin.framework.resources;

import gov.nasa.jpl.aerie.merlin.framework.Resource;

public interface NameableResource<D> extends Resource<D> {
  String getName();
  void setName(String name);
}
