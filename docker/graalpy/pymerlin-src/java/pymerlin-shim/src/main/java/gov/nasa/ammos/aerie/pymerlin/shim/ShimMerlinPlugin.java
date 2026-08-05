package gov.nasa.ammos.aerie.pymerlin.shim;

import gov.nasa.jpl.aerie.merlin.protocol.model.MerlinPlugin;

public final class ShimMerlinPlugin implements MerlinPlugin {
    @Override
    public ShimModelType getModelType() {
        return new ShimModelType();
    }
}
