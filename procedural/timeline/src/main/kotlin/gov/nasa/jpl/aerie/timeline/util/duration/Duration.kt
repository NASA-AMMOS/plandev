package gov.nasa.jpl.aerie.timeline.util.duration

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration.*
import gov.nasa.jpl.aerie.timeline.Interval
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Negation operator for durations. */
operator fun Duration.unaryMinus(): Duration = this.negate()

/** Create an interval between two durations, including both endpoints, using the `..` operator. */
operator fun Duration.rangeTo(other: Duration): Interval = Interval.between(this, other)

/** Create an interval between two durations, including the start and excluding the end, using the `..<` operator. */
operator fun Duration.rangeUntil(other: Duration): Interval = Interval.betweenClosedOpen(this, other)

/**
 * Divides this by a duration divisor to produce a long, rounded down.
 *
 * To calculate this division as a double, use [ratioOver].
 */
operator fun Duration.div(divisor: Duration): Long = this.dividedBy(divisor)
/** Divides this by a long divisor. */
operator fun Duration.div(divisor: Long): Duration = this.dividedBy(divisor)
/** Divides this by a double divisor. */
operator fun Duration.div(divisor: Double): Duration = roundNearest(1 / divisor, this)

/** Remainder division between this and another duration. */
operator fun Duration.rem(divisor: Duration): Duration = this.remainderOf(divisor)

/** Adds a duration to an instant, to produce another instant. */
operator fun Instant.plus(duration: Duration): Instant = plusMillis(duration / MILLISECOND)
    .plusNanos(1000 * ((duration % MILLISECOND) / MICROSECOND))

/** Subtracts a duration from an instant, to produce another instant. */
operator fun Instant.minus(other: Instant): Duration = microseconds(other.until(this, ChronoUnit.MICROS))
