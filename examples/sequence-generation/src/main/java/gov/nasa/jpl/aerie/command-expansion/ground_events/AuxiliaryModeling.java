package gov.nasa.jpl.aerie.command_expansion.ground_events;

import gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.Result;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

/**
 * This is a catch-all general-purpose modeling tool.
 * It allows you to inject arbitrary behavior into a sequence,
 * as a modeled-but-not-uplinked ground event.
 * <p>
 *     However, because it allows arbitrary code, it can't really be serialized.
 *     Attempting to add one of these to the plan through the UI will result in a deserialization error.
 * </p>
 */
@ActivityType("AuxiliaryModeling")
public class AuxiliaryModeling extends GroundEvent {
    @Export.Parameter
    public AuxiliaryModelingBehavior behavior;

    @ActivityType.EffectModel
    public void run(Mission mission) {
        behavior.run(mission);
    }

    @Override
    public void call(Mission mission) {
        ActivityActions.call(mission, this);
    }

    public interface AuxiliaryModelingBehavior {
        void run(Mission mission);
    }

    public static AuxiliaryModeling of(AuxiliaryModelingBehavior behavior) {
        var result = new AuxiliaryModeling();
        result.behavior = behavior;
        return result;
    }

    // Since we're often writing these in a context where we already have the mission model,
    // sometimes it's cleaner to use that handle directly.
    public static AuxiliaryModeling of(Runnable behavior) {
        return of($ -> behavior.run());
    }

    public static final class ValueMappers {
        public static ValueMapper<AuxiliaryModelingBehavior> behaviorMapper() {
            return new ValueMapper<>() {
                @Override
                public ValueSchema getValueSchema() {
                    return ValueSchema.STRING;
                }

                @Override
                public Result<AuxiliaryModelingBehavior, String> deserializeValue(SerializedValue serializedValue) {
                    return Result.failure(String.format(
                            "%s is not deserializable", AuxiliaryModelingBehavior.class.getSimpleName()));
                }

                @Override
                public SerializedValue serializeValue(AuxiliaryModelingBehavior value) {
                    return SerializedValue.NULL;
                }
            };
        }
    }
}
