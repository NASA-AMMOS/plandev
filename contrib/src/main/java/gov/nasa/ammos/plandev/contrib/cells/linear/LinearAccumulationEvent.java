package gov.nasa.jpl.aerie.contrib.cells.linear;

public sealed interface LinearAccumulationEvent {
  record AddVolume(double deltaVolume) implements LinearAccumulationEvent {}
  record AddRate(double deltaRate) implements LinearAccumulationEvent {}
}
