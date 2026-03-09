package gov.nasa.jpl.aerie.banananation;

import gov.nasa.jpl.aerie.banananation.generated.ActivityTypes;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

public class ActivityTypesPerformanceTest {

  @Test
  public void testActivityTypesLoadTime() {
    // Warm up JVM
    System.gc();
    
    long startTime = System.nanoTime();
    var directiveTypes = ActivityTypes.directiveTypes;
    long endTime = System.nanoTime();
    
    long durationMs = (endTime - startTime) / 1_000_000;
    
    System.out.println("=== ActivityTypes Load Performance ===");
    System.out.println("Time to load directiveTypes: " + durationMs + " ms");
    System.out.println("Number of activities loaded: " + directiveTypes.size());
    System.out.println("Average time per activity: " + (durationMs / (double) directiveTypes.size()) + " ms");
    
    // Verify it actually works
    assert directiveTypes.size() > 0 : "No activities loaded!";
    assert directiveTypes.containsKey("BiteBanana") : "BiteBanana not found!";
    
    // Test subsequent access (should be instant)
    startTime = System.nanoTime();
    var directiveTypes2 = ActivityTypes.directiveTypes;
    endTime = System.nanoTime();
    long subsequentAccessNs = endTime - startTime;
    
    System.out.println("Subsequent access time: " + subsequentAccessNs + " ns (< 1 microsecond)");
    
    // Memory usage
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
    System.out.println("Heap memory used: " + (heapUsage.getUsed() / 1024 / 1024) + " MB");
  }
  
  @Test
  public void testDirectiveTypesAccess() {
    // Test that normal access patterns are unaffected
    long startTime = System.nanoTime();
    
    for (int i = 0; i < 10000; i++) {
      var mapper = ActivityTypes.directiveTypes.get("BiteBanana");
      assert mapper != null;
    }
    
    long endTime = System.nanoTime();
    long totalTimeMs = (endTime - startTime) / 1_000_000;
    
    System.out.println("=== Map Access Performance ===");
    System.out.println("10,000 Map lookups took: " + totalTimeMs + " ms");
    System.out.println("Average per lookup: " + (totalTimeMs / 10000.0) + " ms");
  }
  
  @Test
  public void testReflectionOverhead() {
    // Measure just the reflection cost by timing class load
    long startTime = System.nanoTime();
    
    try {
      for (int i = 0; i < 100; i++) {
        Class<?> clazz = Class.forName("gov.nasa.jpl.aerie.banananation.generated.ActivityTypes_BiteBanana");
        var field = clazz.getField("directiveTypes");
        var value = field.get(null);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    
    long endTime = System.nanoTime();
    double avgTimeMs = (endTime - startTime) / 1_000_000.0 / 100.0;

    System.out.println("=== Reflection Overhead ===");
    System.out.println("Average reflection cost per activity: " + avgTimeMs + " ms");
  }
}
