package gov.nasa.jpl.aerie.banananation;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.Supplier;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;

/**
 * Timer measures both wall clock, CPU time, and counting.
 * Individual instances of Timer capture a single time interval.
 * A resettable static map keeps statistics across multiple Timers,
 * which could be in separate threads.
 * <p>
 * Users of Timer should be careful about threads.  A Timer instance
 * only measures time for the existing thread, excluding the CPU time
 * of spawned threads.  Timers must be instantiated separately for
 * each thread.
 */
public class Timer {

  /**
   * These are the stats that are accumulated across multiple Timers.
   */
  public enum StatType {
    start("start"), end("end"), cpuTime("cpu time"),
    wallClockTime("wall clock time"), count("count");

    public final String string;

    StatType(String string) {
      this.string = string;
    }

    @Override
    public String toString() {
      return string;
    }
  }

  // STATIC MEMBERS

  private static class Logger {
    public void info(String s) {
      System.out.println(s);
    }
  }
  private static final Logger logger = new Logger();//LoggerFactory.getLogger(Timer.class);

  /**
   * Used to set {@linkplain #timeTasks}
   */
  private static String timeTasksProperty = System.getProperty("gov.nasa.jpl.aerie.timeTasks");
  /**
   * Calling code may use this flag to enable/disable the use of Timers.  This has no effect on the functionality
   * of this Timer class.  It is merely kept here to keep the footprint light in calling code.  The default value
   * is false.  It is set by a Java property, {@code gov.nasa.jpl.aerie.timeTasks}.  To set this flag to true,
   * in the command line arguments to java, include {@code-Dgov.nasa.jpl.aerie.timeTasks=ON} or
   * {@code -Dgov.nasa.jpl.aerie.timeTasks=TRUE}.
   */
  public static boolean timeTasks =
      timeTasksProperty != null && (timeTasksProperty.equalsIgnoreCase("ON") ||
                                    timeTasksProperty.equalsIgnoreCase("TRUE"));

  /**
   * System calls for the current time can be 30 ms or more, so we want to adjust wall clock time measurements
   * for that system time so that it does not skew small-duration measurements.
   */
  private static long avgTimeOfSystemCall;
  static {
    // Compute avgTimeOfSystemCall
    Instant t1 = Instant.now();
    Instant t2 = null;
    for (int i = 0; i < 10; ++i) {
      t2 = Instant.now();
    }
    avgTimeOfSystemCall = (instantToNanos(t2) - instantToNanos(t1)) / 10;  // divide by 10, not 11
    logger.info("property gov.nasa.jpl.aerie.timeTasks = " + timeTasksProperty);
    logger.info("Timer.timeTasks = " + timeTasks);
    logger.info("average time of system call = " + avgTimeOfSystemCall + " nanoseconds");
  }

  /**
   * The stats recorded for multiple occurrences (Timer instantiations) -- since it's static and could be accessed
   * by multiple threads, we use a ConcurrentMap for thread safety
   */
  protected static ConcurrentSkipListMap<String, ConcurrentSkipListMap<StatType, Long>> stats = new ConcurrentSkipListMap<>();

  /**
   *  A map from the start time so that we can write stats out in time order
   */
  protected static ConcurrentSkipListMap<Long, ConcurrentSkipListSet<String>> labelsByStartTime = new ConcurrentSkipListMap<>();

  /**
   *  @return the stats map for custom use
   */
  public static ConcurrentSkipListMap<String, ConcurrentSkipListMap<StatType, Long>> getStats() {
    return stats;
  }

  /**
   * This is used to get CPU time measurements
   */
  protected static ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

  /**
   * Clear the existing statically recorded stats maps to start collect stats
   */
  public static void reset() {
    stats.clear();
    labelsByStartTime.clear();
  }

  /**
   * Utility for getting or creating a map nested in another map.
   *
   * @param label key of the outer map
   * @return the inner stat map for the label
   */
  protected static ConcurrentSkipListMap<StatType, Long> getInnerMap(String label) {
    ConcurrentSkipListMap<StatType, Long> innerMap;
    if (stats.keySet().contains(label)) {
      innerMap = stats.get(label);
    } else {
      innerMap = new ConcurrentSkipListMap<>();
      stats.put(label, innerMap);
    }
    return innerMap;
  }

  /**
   * Add the value to the existing one for the stat and label.
   *
   * @param label the thing for which the stat applies
   * @param stat the kind of stat (e.g. "cpu time")
   * @param value the increase in the stat value
   */
  public static void addStat(String label, StatType stat, long value) {
    // Don't add start or end time values.  Call putStat() to overwrite instead of add.
    if (stat == StatType.start || stat == StatType.end) {
      putStat(label, stat, value);
      return;
    }
    // Make sure map entries exist before adding.
    final ConcurrentSkipListMap<StatType, Long> innerMap = getInnerMap(label);
    if (!innerMap.containsKey(stat)) {
      innerMap.put(stat, value);
    } else {
      innerMap.put(stat, innerMap.get(stat) + value);
    }
  }

  /**
   * Insert or overwrite the value of the stat for the label.
   *
   * @param label the thing for which the stat applies
   * @param stat the kind of stat (e.g. "start")
   * @param value the increase in the stat value
   */
  public static void putStat(String label, StatType stat, long value) {
    // Make sure map entries exist before adding.
    final ConcurrentSkipListMap<StatType, Long> innerMap = getInnerMap(label);
    innerMap.put(stat, value);

    // If this is the start time, add to the labelsByStart map.
    if (stat == StatType.start) {
      final ConcurrentSkipListSet<String> timeList;
      if (labelsByStartTime.containsKey(value)) {
        timeList = labelsByStartTime.get(value);
      } else {
        timeList = new ConcurrentSkipListSet<>();
        labelsByStartTime.put(value, timeList);
      }
      timeList.add(label);
    }
  }

  /**
   * Wrap a Timer measurement around a function call
   *
   * @param label the category or name for the interval being timed
   * @param r the Supplier function to be invoked and measured
   * @return the return value of the Supplier when invoked
   * @param <T> the type of the return value
   */
  public static <T> T run(String label, Supplier<T> r) {
    Timer timer = new Timer(label);
    T t = r.get();
    timer.stop();
    return t;
  }

  /**
   * Formats a time duration as a String
   *
   * @param nanoseconds the time duration to format
   * @return the String rendering of the duration
   */
  public static String formatDuration(Long nanoseconds) {
    return (nanoseconds / 1.0e9) + " seconds";
  }

  /**
   * These stats are written out differently.
   */
  protected static TreeSet<StatType> timeAndCountStats =
      new TreeSet<>(Arrays.asList( StatType.start, StatType.end, StatType.count));

  /**
   * Write out the stats for each label ordered by time.
   * @return a string with each stat written on a different line
   */
  public static String summarizeStats() {
    StringBuilder sb = new StringBuilder();
    TreeMap<Long, List<String>> labelsByEnd = new TreeMap<>();
    TreeSet<Long> endTimesCopy;

    // Loop through labels in order of start time.
    for (Long start : labelsByStartTime.keySet()) {  // nanoseconds
      // Write any passed end times before this start
      endTimesCopy = new TreeSet<>(labelsByEnd.keySet()); // copy so that we can remove entries--consider priority queue
      for (Long end : endTimesCopy) {  // nanoseconds
        if ( end > start + 1_000_000L ) break;  // only end times before or roughly at the same time the start
        for (String label: labelsByEnd.get(end)) {
          sb.append(label + ": " + StatType.end + " = " + formatTimestamp(end) + "\n");
        }
        labelsByEnd.remove(end);
      }

      // Write start, duration, and number of occurrences.
      for (String label: labelsByStartTime.get(start)) {
        Long count = 1L;
        final ConcurrentSkipListMap<StatType, Long> statsForLabel = stats.get(label);
        Long end = statsForLabel.get(StatType.end);
        sb.append( label + ": " + StatType.start + " = " + formatTimestamp(start) + "\n");
        // Save away the end time to write out later.
        if ( end != null ) {
          var labels = labelsByEnd.get(end);
          if (labels == null) {
            labels = new ArrayList<>();
            labelsByEnd.put(end, labels);
          }
          labels.add(label);
          long duration = end - start;
          sb.append(label + ": duration = " + formatDuration(duration) + "\n");
          count = statsForLabel.get(StatType.count);
          if (count == null) count = 1L;
          if (count > 1) {
            sb.append(label + ": " + count + " occurrences\n");
            // Averaging the duration above doesn't make sense since the occurrences may have been sporadic.
            // The "other duration stats" below could be averaged but aren't just to keep output simple.
            // But, maybe a total, min, max, avg column justified would be nice.
          }
        }

        // Write all other duration stats for the label.  (Note that the stats are assumed to all be nanoseconds!)
        for (StatType stat : statsForLabel.keySet()) {
          if (!timeAndCountStats.contains(stat)) {
            // wall clock will be the same as duration if only one occurrence, so don't repeat the info
            if (count > 1 || stat != StatType.wallClockTime) {
              sb.append(label + ": " + stat + " = " + formatDuration(statsForLabel.get(stat)) + "\n");
            }
          }
        }
      }
    }

    // Write remaining end times now that we're done looping through start times.
    endTimesCopy = new TreeSet<>(labelsByEnd.keySet());
    for (Long end : endTimesCopy) {  // nanoseconds
      for (String label: labelsByEnd.get(end)) {
        sb.append(label + ": "+ StatType.end + " = " + formatTimestamp(end) + "\n");
      }
      labelsByEnd.remove(end);
    }
    return sb.toString();
  }

  /**
   * Get the string with lines of stats from summarizeStats() and log each line with a little decoration.
   */
  public static void logStats() {
    logger.info(timestampNow() + " %% REPORTING TIMER STATS %%");
    String stats = summarizeStats();
    String[] lines = stats.split("\n");
    List.of(lines).forEach(x -> logger.info(" %% " + x ));

    String csvRows = csvStats();
    lines = csvRows.split("\n");
    List.of(lines).forEach(x -> logger.info(" %% " + x ));
  }

  public static String csvStats() {
    StringBuilder sb = new StringBuilder();
    // print header row
    final List<String> headers = new ArrayList<>();
    for (String label : stats.keySet()) {
      final ConcurrentSkipListMap<StatType, Long> innerMap = getInnerMap(label);
      for (StatType stat : innerMap.keySet()) {
        headers.add(stat + " " + label);
      }
    }
    String headerString = String.join(",", headers);
    sb.append(headerString + "\n");

    // print data row
    final List<String> data = new ArrayList<>();
    for (String label : stats.keySet()) {
      final ConcurrentSkipListMap<StatType, Long> innerMap = getInnerMap(label);
      for (StatType stat : innerMap.keySet()) {
        data.add("" + innerMap.get(stat));
      }
    }
    String dataString = String.join(",", data);
    sb.append(dataString + "\n");

    String twoRows = sb.toString();
    return twoRows;
  }

  // It would be nice to use one of the two Timestamp classes below.  They are maybe
  // identical: gov.nasa.jpl.aerie.merlin.server.models.Timestamp and
  // gov.nasa.jpl.aerie.scheduler.server.models.Timestamp.
  // TODO -- Consider moving the redundant Timestamp code to a more general package where it can be shared.
  /**
   * ISO timestamp format
   */
  public static final DateTimeFormatter format =
      new DateTimeFormatterBuilder()
          .appendPattern("uuuu-DDD'T'HH:mm:ss")
          .appendFraction(ChronoField.MICRO_OF_SECOND, 0, 6, true)
          .toFormatter();

  /**
   * Format nanoseconds into a date-timestamp.
   *
   * @param nanoseconds since the Java epoch, Jan 1, 1970
   * @return formatted string
   */
  protected static String formatTimestamp(long nanoseconds) {
    System.nanoTime();
    return formatTimestamp(Instant.ofEpochSecond(0L, nanoseconds));
  }

  /**
   * Format Instant into a date-timestamp.
   *
   * @param instant
   * @return formatted string
   */
  protected static String formatTimestamp(Instant instant) {
    return format.format(instant.atZone(ZoneOffset.UTC));
  }

  /**
   * Format the current system time into a date-timestamp
   *
   * @return formatted timestamp String
   */
  protected static String timestampNow() {
    return formatTimestamp(Instant.now());
  }

  /**
   * Get the number of nanoseconds from the Java epoch for this Instant.
   * A 64-bit long is sufficient until year 2262.
   *
   * @param i the Instant representing a date-time
   * @return nanoseconds as a long
   */
  protected static long instantToNanos(Instant i) {
    return i.getEpochSecond() * 1_000_000_000L + (long)i.getNano();   // 64-bit long is good until year 2262
  }


  // NON-STATIC MEMBERS

  protected String label;  // The name of the thing for which the stats are recorded, like "writing to the DB"
  //protected long initialWallClockTime; // nanoseconds
  protected Instant initialInstant;
  protected long accumulatedWallClockTime = 0;  // nanoseconds
  protected long initialCpuTime;  // nanoseconds
  protected long accumulatedCpuTime = 0;  // nanoseconds


  /**
   * Start a timer with a label and optionally log the start event.
   *
   * @param label a name for a category in which stats are collected and summed
   * @param t the Thread from which stats are collected
   * @param writeToLog if true, logs the start of the timer if the first occurrence of this label since the last reset
   */
  public Timer(String label, Thread t, boolean writeToLog) {
    this.label = label;

    // Only record the start time stat the first time for the label to mark the start of all occurrences.
    ConcurrentSkipListMap<StatType, Long> statsForLabel = stats.get(label);
    if (statsForLabel == null || !statsForLabel.containsKey(StatType.start)) {
      initialInstant = Instant.now();
      long initialWallClockTime = instantToNanos(initialInstant);
      putStat(label, StatType.start, initialWallClockTime);
      if (writeToLog) {
        logger.info(formatTimestamp(initialWallClockTime) + " -- " + label + " -- " + StatType.start);
      }
    }

    initialCpuTime = threadMXBean.getCurrentThreadCpuTime();
    // We call Instant.now() again below to get a more accurate value to compute elapsed wall clock time
    initialInstant = Instant.now();   // Some say that System.nanoTime() is more accurate.
  }

  /**
   * Start a timer with a label and optionally log the start event.
   *
   * @param label a name for a category in which stats are collected and summed
   * @param writeToLog if true, logs the start of the timer if the first occurrence of this label since the last reset
   */
  public Timer(String label, boolean writeToLog) {
    this(label, Thread.currentThread(), writeToLog);
  }

  /**
   * Start a timer with a label.
   *
   * @param label a name for a category in which stats are collected and summed
   */
  public Timer(String label) {
    this(label, false);  // default - don't log start time
  }

  /**
   * Stop the timer, get stats, combine with static stats (for multiple Timers), and optionally log the end time.
   *
   * @param writeToLog if true, logs the end of the timer
   */
  public void stop(boolean writeToLog) {
    Instant end = Instant.now();
    accumulatedCpuTime = threadMXBean.getCurrentThreadCpuTime() - initialCpuTime;

    long endWallClockTime = instantToNanos(end);
    long initialWallClockTime = instantToNanos(initialInstant);

    // We adjust the time difference by subtracting off the overhead of getting the system time.
    accumulatedWallClockTime = endWallClockTime - initialWallClockTime - avgTimeOfSystemCall;

    addStat(label, StatType.wallClockTime, accumulatedWallClockTime);
    addStat(label, StatType.cpuTime, accumulatedCpuTime);
    addStat(label, StatType.count, 1);
    putStat(label, StatType.end, endWallClockTime);

    if (writeToLog) {
      logger.info(formatTimestamp(end) + " -- " + label + " -- " + StatType.end);
    }
  }

  /**
   * Stop the timer, get stats, and combine with static stats (for multiple Timers).
   */
  public void stop() {
    stop(false);  // don't log end time
  }

}
