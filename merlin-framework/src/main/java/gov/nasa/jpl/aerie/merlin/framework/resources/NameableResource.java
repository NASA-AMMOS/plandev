package gov.nasa.jpl.aerie.merlin.framework.resources;

import gov.nasa.jpl.aerie.merlin.framework.Resource;

/**
 * A resource that can be given a string name. Used by scheduling and constraints
 * to access resources through the mission model rather than with name+deserializer.
 */
public interface NameableResource<D> extends Resource<D> {
  String getName();
  void setName(String name);
}
