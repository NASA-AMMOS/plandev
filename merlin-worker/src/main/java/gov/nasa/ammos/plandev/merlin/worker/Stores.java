package gov.nasa.jpl.aerie.merlin.worker;

import gov.nasa.jpl.aerie.merlin.server.remotes.MissionModelRepository;
import gov.nasa.jpl.aerie.merlin.server.remotes.PlanRepository;
import gov.nasa.jpl.aerie.merlin.server.remotes.ResultsCellRepository;

public record Stores (PlanRepository plans, MissionModelRepository missionModels, ResultsCellRepository results){}
