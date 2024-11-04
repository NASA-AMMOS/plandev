package gov.nasa.jpl.aerie.command_expansion.model.sequencing;

import gov.nasa.jpl.aerie.command_expansion.expansion.Sequence;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.Registrar;

import java.util.List;
import java.util.stream.IntStream;

import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.replaying;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.spawn;

// Once again, this is a mission-specific model, but we can provide off-the-shelf versions for VML & FCPL.
public class SequencingModel {
    private final Mission mission;
    public List<FcplSequenceEngine> sequenceEngines;

    public SequencingModel(Registrar registrar, Mission mission) {
        this.mission = mission;
        this.sequenceEngines = IntStream.rangeClosed(1, 32)
                .mapToObj(i -> new FcplSequenceEngine(
                        String.format("%02d", i),
                        registrar,
                        mission))
                .toList();
    }

    /**
     * Synchronously run this sequence in the next-available sequencing engine.
     */
    public void run(Sequence sequence) {
        load(sequence).execute();
    }

    /**
     * Load this sequence in the next-available sequencing engine,
     * then asynchronously begin executing it.
     */
    public FcplSequenceEngine activate(Sequence sequence) {
        var engine = load(sequence);
        spawn(replaying(engine::execute));
        return engine;
    }

    public FcplSequenceEngine load(Sequence sequence) {
        var engine = sequenceEngines.stream()
                .filter($ -> !currentValue($.isLoaded()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No empty sequence engines available!"));

        engine.load(sequence);

        return engine;
    }
}
