package gov.nasa.jpl.aerie.command_expansion.planning_activities;

import gov.nasa.jpl.aerie.command_expansion.command_activities.*;
import gov.nasa.jpl.aerie.command_expansion.expansion.Sequence;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.List;
import java.util.regex.Pattern;

import static gov.nasa.jpl.aerie.command_expansion.command_activities.SEQ_IF.Operator.*;
import static gov.nasa.jpl.aerie.command_expansion.expansion.TimedCommand.*;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;

@ActivityType("Conditional_Warm_Up")
public class Conditional_Warm_Up {
    @Export.Parameter
    public String condition;

    @ActivityType.EffectModel
    public String run(Mission mission) {
        var matcher = Pattern
                .compile("^\\s*(G\\d+(?:INT|FLT))\\s*(<=?|>=?|==|!=)\\s*(G\\d+(?:INT|FLT))\\s*$")
                .matcher(condition);

        if (!matcher.find()) {
            throw new IllegalArgumentException(String.format(
                    "Condition '%s' is not in required format.", condition));
        }

        var ifCmd = new SEQ_IF();
        ifCmd.leftVariable = matcher.group(1);
        ifCmd.rightVariable = matcher.group(3);
        ifCmd.operator = switch (matcher.group(2)) {
            case "<" -> LESS_THAN;
            case "<=" -> LESS_THAN_OR_EQUALS;
            case ">" -> GREATER_THAN;
            case ">=" -> GREATER_THAN_OR_EQUALS;
            case "==" -> EQUALS;
            case "!=" -> NOT_EQUALS;
            default -> throw new RuntimeException(); // this case is impossible because of the regex above.
        };

        var sequence = new Sequence(
                this.getClass().getSimpleName(),
                List.of(
                        absolute(currentValue(mission.clock), ifCmd),
                        commandComplete(SEQ_ECHO.of(String.format(
                                "Condition '%s' was TRUE, 'then' branch taken.", condition))),
                        commandComplete(new PWR_Turn_On_Heater()),
                        relative(Duration.of(10, SECONDS), new PWR_Turn_Off_Heater()),
                        commandComplete(new SEQ_ELSE()),
                        commandComplete(SEQ_ECHO.of(String.format(
                                "Condition '%s' was FALSE, 'else' branch taken.", condition))),
                        commandComplete(new SEQ_END_IF())
                )
        );

        mission.sequencing.run(sequence);

        return sequence.toSeqJson().serialize();
    }
}
