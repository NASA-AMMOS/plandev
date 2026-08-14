package gov.nasa.ammos.plandev.contrib.streamline.debugging;

import gov.nasa.ammos.plandev.merlin.framework.CellRef;
import gov.nasa.ammos.plandev.merlin.framework.Registrar;
import gov.nasa.ammos.plandev.merlin.protocol.model.CellType;
import gov.nasa.ammos.plandev.merlin.protocol.model.EffectTrait;
import gov.nasa.ammos.plandev.merlin.protocol.types.Unit;

import static gov.nasa.ammos.plandev.contrib.serialization.rulesets.BasicValueMappers.string;
import static gov.nasa.ammos.plandev.merlin.protocol.types.Unit.UNIT;

public class SimpleLogger {
    private final CellRef<String, Unit> cellRef = CellRef.allocate(UNIT, new CellType<>() {
        @Override
        public EffectTrait<Unit> getEffectType() {
            return new EffectTrait<>() {
                @Override
                public Unit empty() {
                    return UNIT;
                }

                @Override
                public Unit sequentially(Unit prefix, Unit suffix) {
                    return UNIT;
                }

                @Override
                public Unit concurrently(Unit left, Unit right) {
                    return UNIT;
                }
            };
        }

        @Override
        public Unit duplicate(Unit unit) {
            return unit;
        }

        @Override
        public void apply(Unit unit, Unit s) {
        }
    }, $ -> UNIT);

    public SimpleLogger(String name, Registrar registrar) {
        registrar.topic(name, cellRef, string());
    }

    public void log(String message) {
        cellRef.emit(message);
    }
}
