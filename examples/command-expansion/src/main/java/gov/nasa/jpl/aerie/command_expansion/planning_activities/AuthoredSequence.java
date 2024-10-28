package gov.nasa.jpl.aerie.command_expansion.planning_activities;

import gov.nasa.jpl.aerie.command_expansion.expansion.SeqJsonSequence;
import gov.nasa.jpl.aerie.command_expansion.expansion.Sequence;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;

import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Logging.LOGGER;

@ActivityType("AuthoredSequence")
public class AuthoredSequence {
    @Export.Parameter
    public String sequenceText;

    @ActivityType.EffectModel
    public void run(Mission mission) {
        // For the sake of this demo, I'm just pulling the sequence in through a Parameter.
        // A more realistic example should probably let you pick from stored sequences somehow.
        LOGGER.debug("AuthoredSequence: sequenceText = " + sequenceText);
        var seqJson = SeqJsonSequence.deserialize(sequenceText);
        LOGGER.debug("AuthoredSequence: seqJson = " + seqJson);
        var sequence = Sequence.parse(seqJson);
        LOGGER.debug("AuthoredSequence: sequence = " + sequence);
        mission.sequencing.run(sequence);
    }
}
