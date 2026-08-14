package gov.nasa.jpl.aerie.merlin.driver.resources;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.util.ArrayList;

record ResourceSegments<T> (ValueSchema valueSchema, ArrayList<Segment<T>> segments) {
    record Segment<T> (Duration startOffset, T dynamics) {}

    ResourceSegments(ValueSchema valueSchema, int threshold) {
      this(valueSchema, new ArrayList<>(threshold));
    }

    public ResourceSegments<T> deepCopy(){
      ArrayList<Segment<T>> segmentsCopy = new ArrayList<>(this.segments.size());
      segmentsCopy.addAll(this.segments);
      return new ResourceSegments<>(valueSchema, segmentsCopy);
    }
}
