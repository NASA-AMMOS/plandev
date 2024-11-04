package gov.nasa.jpl.aerie.command_expansion.command_activities;

import gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;

import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;

import static gov.nasa.jpl.aerie.command_expansion.command_activities.CommandConstants.SEQ_COMMAND_DURATION;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Logging.LOGGER;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteEffects.set;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;

@ActivityType("SEQ_IF")
public class SEQ_IF extends Command {
    @Export.Parameter
    public String leftVariable;

    @Export.Parameter
    public Operator operator;

    @Export.Parameter
    public String rightVariable;

    @Override
    public List<Object> args() {
        return List.of(leftVariable, operator, rightVariable);
    }

    @ActivityType.EffectModel
    public void run(Mission mission) {
        // TODO - error checking & handling
        var sequence = currentValue(engine.loadedSequence()).orElseThrow();

        // Look up the index for each branch of the "if"
        int ifIndex = currentValue(engine.lastDispatchedCommandIndex());
        int elseIndex = -1, endIfIndex = -1, ifDepth = 0;
        for (int i = ifIndex + 1; i < sequence.commands().size(); ++i) {
            var cmd = sequence.commands().get(i);
            if (cmd.step() instanceof SEQ_IF) {
                ++ifDepth;
            } else if (cmd.step() instanceof SEQ_END_IF) {
                if (ifDepth > 0) {
                    --ifDepth;
                } else {
                    endIfIndex = i;
                    break;
                }
            } else if (cmd.step() instanceof SEQ_ELSE && ifDepth == 0) {
                elseIndex = i;
            }
        }

        // If no "else" clause is found, the "else" branch is just the end of the if.
        if (elseIndex < 0) elseIndex = endIfIndex;

        boolean comparison;
        try {
            int leftValue = currentValue(mission.globals.getGlobalInt(leftVariable));
            int rightValue = currentValue(mission.globals.getGlobalInt(rightVariable));
            comparison = operator.intComparison.test(leftValue, rightValue);
        } catch (IllegalArgumentException e) {
            double leftValue = currentValue(mission.globals.getGlobalFloat(leftVariable));
            double rightValue = currentValue(mission.globals.getGlobalFloat(rightVariable));
            comparison = operator.doubleComparison.test(leftValue, rightValue);
        }

        LOGGER.info("SEQ_IF: Condition %s %s %s evaluated to %s",
                leftVariable, operator, rightVariable, comparison);
        if (!comparison) {
            set(engine.nextCommandIndex(), elseIndex + 1);
        }

        delay(SEQ_COMMAND_DURATION);
    }

    @Override
    public void call(Mission mission) {
        ActivityActions.call(mission, this);
    }

    public enum Operator {
        LESS_THAN((x, y) -> x < y, (x, y) -> x < y),
        LESS_THAN_OR_EQUALS((x, y) -> x <= y, (x, y) -> x <= y),
        GREATER_THAN((x, y) -> x > y, (x, y) -> x > y),
        GREATER_THAN_OR_EQUALS((x, y) -> x >= y, (x, y) -> x >= y),
        EQUALS(Objects::equals, Objects::equals),
        NOT_EQUALS((x, y) -> !Objects.equals(x, y), (x, y) -> !Objects.equals(x, y));

        public final BiPredicate<Integer, Integer> intComparison;
        public final BiPredicate<Double, Double> doubleComparison;

        Operator(BiPredicate<Integer, Integer> intComparison, BiPredicate<Double, Double> doubleComparison) {
            this.intComparison = intComparison;
            this.doubleComparison = doubleComparison;
        }
    }
}
