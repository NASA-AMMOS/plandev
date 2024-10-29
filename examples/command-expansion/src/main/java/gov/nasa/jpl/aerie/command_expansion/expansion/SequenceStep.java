package gov.nasa.jpl.aerie.command_expansion.expansion;

import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.command_expansion.model.sequencing.FcplSequenceEngine;

public interface SequenceStep {
    void call(Mission mission, FcplSequenceEngine engine);
}
