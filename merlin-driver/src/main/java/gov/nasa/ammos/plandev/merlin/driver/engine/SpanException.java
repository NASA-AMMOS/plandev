package gov.nasa.jpl.aerie.merlin.driver.engine;

public class SpanException extends RuntimeException {
    public final SpanId spanId;
    public final Throwable cause;

    public SpanException(final SpanId spanId, final Throwable cause) {
        super(cause.getMessage(), cause);
        this.spanId = spanId;
        this.cause = cause;
    }
}
