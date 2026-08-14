package gov.nasa.jpl.aerie.scheduler.worker;

import gov.nasa.jpl.aerie.scheduler.server.remotes.ResultsCellRepository;
import gov.nasa.jpl.aerie.scheduler.server.remotes.SpecificationRepository;

public record Stores(SpecificationRepository specifications, ResultsCellRepository results) { }
