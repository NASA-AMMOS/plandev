package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.jpl.aerie.scheduler.solver.stn.TaskNetwork;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class STNTest {

  @Test
  public void testInduceDur(){
    var stn = new TaskNetwork(2,10);
    stn.addAct("Act");
    stn.addStartInterval("Act", 2,5);
    stn.addEndInterval("Act", 6,8);
    var success = stn.propagate();
    assertTrue(success);
    var d = stn.getAllData("Act");
    var expD1 = new TaskNetwork.TNActData(Pair.of(2.,5.), Pair.of(6.,8.), Pair.of(1.,6.));
    assertEquals(d, expD1);
  }

  /**
   * Two tasks with equal start, end and duration domains are constrained by a precedence constraint thus
   * restricting their start and end domain
   */
  @Test
  public void testActConstraints(){
    var stn = new TaskNetwork(0,10);
    stn.addAct("Act1");
    stn.addStartInterval("Act1", 1,10);
    stn.addEndInterval("Act1", 1,10);
    stn.addDurationInterval("Act1", 3,3);
    stn.addAct("Act2");
    stn.addStartInterval("Act2", 1,10);
    stn.addEndInterval("Act2", 1,10);
    stn.addDurationInterval("Act2", 3,3);
    stn.startsAfterEnd("Act1", "Act2");
    var success = stn.propagate();
    assertTrue(success);
    var d1 = stn.getAllData("Act1");
    var d2 = stn.getAllData("Act2");
    var expD1 = new TaskNetwork.TNActData(Pair.of(1.,4.), Pair.of(4.,7.), Pair.of(3.,3.));
    var expD2 = new TaskNetwork.TNActData(Pair.of(4.,7.), Pair.of(7.,10.), Pair.of(3.,3.));
    assert (d1.equals(expD1));
    assertEquals(d2, expD2);
  }

  @Test
  public void testInduceEnd(){
    var stn = new TaskNetwork(2,10);
    stn.addAct("Act");
    stn.addStartInterval("Act", 2,5);
    stn.addDurationInterval("Act", 6, 8);
    var success = stn.propagate();
    assertTrue(success);
    var d = stn.getAllData("Act");
    var expD1 = new TaskNetwork.TNActData(Pair.of(2.,4.), Pair.of(8.,10.), Pair.of(6.,8.));
    assertEquals(d, expD1);
  }

  @Test
  public void actWithNoConstraints(){
    var stn = new TaskNetwork(2,10);
    stn.addAct("Act");
    var success = stn.propagate();
    assertTrue(success);
    var d = stn.getAllData("Act");
    var expD1 = new TaskNetwork.TNActData(Pair.of(2.,10.), Pair.of(2.,10.), Pair.of(0.,8.));
    assert (d.equals(expD1));
  }

  @Test
  public void testEnveloppe(){
    var stn = new TaskNetwork(2,10);
    stn.addAct("Act");
    stn.addEndInterval("Act", 2,4);
    stn.addDurationInterval("Act", 1, 2);
    stn.addEnveloppe("Act", "win", 3,7);
    var success = stn.propagate();
    assertTrue(success);
    var d = stn.getAllData("Act");
    var expD1 = new TaskNetwork.TNActData(Pair.of(3.,3.), Pair.of(4.,4.), Pair.of(1.,1.));
    assertEquals(expD1, d);
  }
  @Test
  public void testEnveloppe2(){
    var stn = new TaskNetwork(2,10);
    stn.addAct("Act");
    stn.addStartInterval("Act", 2,15);
    stn.addDurationInterval("Act", 1, 20);
    stn.addEnveloppe("Act", "win", 3,7);
    var success = stn.propagate();
    assertTrue(success);
    var d = stn.getAllData("Act");
    var expD1 = new TaskNetwork.TNActData(Pair.of(3.,6.), Pair.of(4.,7.), Pair.of(1.,4.));
    assertEquals(expD1, d);
  }
}
