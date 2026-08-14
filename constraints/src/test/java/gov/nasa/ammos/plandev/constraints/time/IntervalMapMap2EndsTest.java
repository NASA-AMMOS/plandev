package gov.nasa.jpl.aerie.constraints.time;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Exclusive;
import static gov.nasa.jpl.aerie.constraints.time.Interval.Inclusivity.Inclusive;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

public class IntervalMapMap2EndsTest {

  @Test
  public void map2nonoverlap() {
    IntervalMap<String> left = IntervalMap.<String>of()
        .set(Interval.between(Duration.of(2, SECONDS), Exclusive, Duration.of(4, SECONDS), Exclusive), "a")
        .set(Interval.between(Duration.of(8, SECONDS), Exclusive, Duration.of(9, SECONDS), Exclusive), "a");


    IntervalMap<String> right = IntervalMap.<String>of()
        .set(Interval.between(Duration.of(6, SECONDS), Exclusive, Duration.of(7, SECONDS), Exclusive), "b");

    IntervalMap<String> mapped = IntervalMap.map2(left,
                                                  right,
                                                  (a$, b$) -> {
                                                    if (a$.isPresent() && b$.isPresent()) {
                                                      return Optional.of(a$.get() + b$.get());
                                                    }
                                                    else if (a$.isPresent() && !b$.isPresent()) {
                                                      return Optional.of(a$.get() + "N");
                                                    }
                                                    else if (b$.isPresent() && !a$.isPresent()) {
                                                      return Optional.of("N" + b$.get());
                                                    }
                                                    else {
                                                      return Optional.of("NN");
                                                    }
                                                  });

    IntervalMap<String> expected = IntervalMap.<String>of()
        .set(Interval.between(Duration.MIN_VALUE, Inclusive, Duration.of(2, SECONDS), Inclusive), "NN")
        .set(Interval.between(Duration.of(2, SECONDS), Exclusive, Duration.of(4, SECONDS), Exclusive), "aN")
        .set(Interval.between(Duration.of(4, SECONDS), Inclusive, Duration.of(6, SECONDS), Inclusive), "NN")
        .set(Interval.between(Duration.of(6, SECONDS), Exclusive, Duration.of(7, SECONDS), Exclusive), "Nb")
        .set(Interval.between(Duration.of(7, SECONDS), Inclusive, Duration.of(8, SECONDS), Inclusive), "NN")
        .set(Interval.between(Duration.of(8, SECONDS), Exclusive, Duration.of(9, SECONDS), Exclusive), "aN")
        .set(Interval.between(Duration.of(9, SECONDS), Inclusive, Duration.MAX_VALUE, Inclusive), "NN");

    assertIterableEquals(expected, mapped);
  }

  @Test
  public void map2bothStartAt() {


    IntervalMap<String> left = IntervalMap.<String>of()
        .set(Interval.at(Duration.of(0, SECONDS)), "a")
        .set(Interval.between(1, 3, SECONDS), "b");

    IntervalMap<String> right = IntervalMap.<String>of()
        .set(Interval.at(Duration.of(0, SECONDS)), "b")
        .set(Interval.between(1, 2, SECONDS), "a");


    IntervalMap<String> mapped = IntervalMap.map2(left,
                                                  right,
                                                  (a$, b$) -> {
                                                    if (a$.isPresent() && b$.isPresent()) {
                                                      return Optional.of(a$.get() + b$.get());
                                                    }
                                                    else if (a$.isPresent() && !b$.isPresent()) {
                                                      return Optional.of(a$.get() + "N");
                                                    }
                                                    else if (b$.isPresent() && !a$.isPresent()) {
                                                      return Optional.of("N" + b$.get());
                                                    }
                                                    else {
                                                      return Optional.of("NN");
                                                    }
                                                  });

    IntervalMap<String> expected = IntervalMap.<String>of()
        .set(Interval.between(Duration.MIN_VALUE, Inclusive, Duration.of(0, SECONDS), Exclusive), "NN")
        .set(Interval.at(Duration.of(0, SECONDS)), "ab")
        .set(Interval.between(Duration.of(0, SECONDS), Exclusive, Duration.of(1, SECONDS), Exclusive), "NN")
        .set(Interval.between(Duration.of(1, SECONDS), Inclusive, Duration.of(2, SECONDS), Inclusive), "ba")
        .set(Interval.between(Duration.of(2, SECONDS), Exclusive, Duration.of(3, SECONDS), Inclusive), "bN")
        .set(Interval.between(Duration.of(3, SECONDS), Exclusive, Duration.of(4, SECONDS), Inclusive), "NN")
        .set(Interval.between(Duration.of(4, SECONDS), Inclusive, Duration.MAX_VALUE, Inclusive), "NN");

    assertIterableEquals(expected, mapped);
  }

  @Test
  public void map2bothEndAt() {

    IntervalMap<String> left = IntervalMap.<String>of()
        .set(Interval.between(1, 3, SECONDS), "b")
        .set(Interval.at(Duration.of(4, SECONDS)), "a");

    IntervalMap<String> right = IntervalMap.<String>of()
        .set(Interval.between(1, 2, SECONDS), "a")
        .set(Interval.at(Duration.of(4, SECONDS)), "b");


    IntervalMap<String> mapped = IntervalMap.map2(left,
                                                  right,
                                                  (a$, b$) -> {
                                                    if (a$.isPresent() && b$.isPresent()) {
                                                      return Optional.of(a$.get() + b$.get());
                                                    }
                                                    else if (a$.isPresent() && !b$.isPresent()) {
                                                      return Optional.of(a$.get() + "N");
                                                    }
                                                    else if (b$.isPresent() && !a$.isPresent()) {
                                                      return Optional.of("N" + b$.get());
                                                    }
                                                    else {
                                                      return Optional.of("NN");
                                                    }
                                                  });

    IntervalMap<String> expected = IntervalMap.<String>of()
        .set(Interval.between(Duration.MIN_VALUE, Inclusive, Duration.of(1, SECONDS), Exclusive), "NN")
        .set(Interval.between(Duration.of(1, SECONDS), Inclusive, Duration.of(2, SECONDS), Inclusive), "ba")
        .set(Interval.between(Duration.of(2, SECONDS), Exclusive, Duration.of(3, SECONDS), Inclusive), "bN")
        .set(Interval.between(Duration.of(3, SECONDS), Exclusive, Duration.of(4, SECONDS), Inclusive), "NN")
        .set(Interval.at(Duration.of(4, SECONDS)), "ab")
        .set(Interval.between(Duration.of(4, SECONDS), Exclusive, Duration.MAX_VALUE, Inclusive), "NN");

    assertIterableEquals(expected, mapped);
  }

  @Test
  public void map2oneStartAtOneEndAt() {


    IntervalMap<String> left = IntervalMap.<String>of()
        .set(Interval.between(1, 3, SECONDS), "b")
        .set(Interval.at(Duration.of(4, SECONDS)), "a");

    IntervalMap<String> right = IntervalMap.<String>of()
        .set(Interval.between(1, 2, SECONDS), "a")
        .set(Interval.at(Duration.of(0, SECONDS)), "b");


    IntervalMap<String> mapped = IntervalMap.map2(left,
                                                  right,
                                                  (a$, b$) -> {
                                                    if (a$.isPresent() && b$.isPresent()) {
                                                      return Optional.of(a$.get() + b$.get());
                                                    }
                                                    else if (a$.isPresent() && !b$.isPresent()) {
                                                      return Optional.of(a$.get() + "N");
                                                    }
                                                    else if (b$.isPresent() && !a$.isPresent()) {
                                                      return Optional.of("N" + b$.get());
                                                    }
                                                    else {
                                                      return Optional.of("NN");
                                                    }
                                                  });

    IntervalMap<String> expected = IntervalMap.<String>of()
        .set(Interval.between(Duration.MIN_VALUE, Inclusive, Duration.of(0, SECONDS), Exclusive), "NN")
        .set(Interval.at(Duration.of(0, SECONDS)), "Nb")
        .set(Interval.between(Duration.of(0, SECONDS), Exclusive, Duration.of(1, SECONDS), Exclusive), "NN")
        .set(Interval.between(Duration.of(1, SECONDS), Inclusive, Duration.of(2, SECONDS), Inclusive), "ba")
        .set(Interval.between(Duration.of(2, SECONDS), Exclusive, Duration.of(3, SECONDS), Inclusive), "bN")
        .set(Interval.between(Duration.of(3, SECONDS), Exclusive, Duration.of(4, SECONDS), Exclusive), "NN")
        .set(Interval.at(Duration.of(4, SECONDS)), "aN")
        .set(Interval.between(Duration.of(4, SECONDS), Exclusive, Duration.MAX_VALUE, Inclusive), "NN");

    assertIterableEquals(expected, mapped);
  }

  @Test
  public void map2bothStartInt() {


    IntervalMap<String> left = IntervalMap.<String>of()
        .set(Interval.between(0, 3, SECONDS), "b");

    IntervalMap<String> right = IntervalMap.<String>of()
        .set(Interval.between(0, 2, SECONDS), "a");


    IntervalMap<String> mapped = IntervalMap.map2(left,
                                                  right,
                                                  (a$, b$) -> {
                                                    if (a$.isPresent() && b$.isPresent()) {
                                                      return Optional.of(a$.get() + b$.get());
                                                    }
                                                    else if (a$.isPresent() && !b$.isPresent()) {
                                                      return Optional.of(a$.get() + "N");
                                                    }
                                                    else if (b$.isPresent() && !a$.isPresent()) {
                                                      return Optional.of("N" + b$.get());
                                                    }
                                                    else {
                                                      return Optional.of("NN");
                                                    }
                                                  });

    IntervalMap<String> expected = IntervalMap.<String>of()
        .set(Interval.between(Duration.MIN_VALUE, Inclusive, Duration.of(0, SECONDS), Exclusive), "NN")
        .set(Interval.between(Duration.of(0, SECONDS), Inclusive, Duration.of(2, SECONDS), Inclusive), "ba")
        .set(Interval.between(Duration.of(2, SECONDS), Exclusive, Duration.of(3, SECONDS), Inclusive), "bN")
        .set(Interval.between(Duration.of(3, SECONDS), Exclusive, Duration.of(4, SECONDS), Inclusive), "NN")
        .set(Interval.between(Duration.of(4, SECONDS), Inclusive, Duration.MAX_VALUE, Inclusive), "NN");

    assertIterableEquals(expected, mapped);
  }

  @Test
  public void map2bothEndInt() {


    IntervalMap<String> left = IntervalMap.<String>of()
        .set(Interval.between(2, 4, SECONDS), "b");

    IntervalMap<String> right = IntervalMap.<String>of()
        .set(Interval.between(1, 4, SECONDS), "a");


    IntervalMap<String> mapped = IntervalMap.map2(left,
                                                  right,
                                                  (a$, b$) -> {
                                                    if (a$.isPresent() && b$.isPresent()) {
                                                      return Optional.of(a$.get() + b$.get());
                                                    }
                                                    else if (a$.isPresent() && !b$.isPresent()) {
                                                      return Optional.of(a$.get() + "N");
                                                    }
                                                    else if (b$.isPresent() && !a$.isPresent()) {
                                                      return Optional.of("N" + b$.get());
                                                    }
                                                    else {
                                                      return Optional.of("NN");
                                                    }
                                                  });

    IntervalMap<String> expected = IntervalMap.<String>of()
        .set(Interval.between(Duration.MIN_VALUE, Inclusive, Duration.of(0, SECONDS), Exclusive), "NN")
        .set(Interval.between(Duration.of(0, SECONDS), Inclusive, Duration.of(1, SECONDS), Exclusive), "NN")
        .set(Interval.between(Duration.of(1, SECONDS), Inclusive, Duration.of(2, SECONDS), Exclusive), "Na")
        .set(Interval.between(Duration.of(2, SECONDS), Inclusive, Duration.of(4, SECONDS), Inclusive), "ba")
        .set(Interval.between(Duration.of(4, SECONDS), Exclusive, Duration.MAX_VALUE, Inclusive), "NN");

    assertIterableEquals(expected, mapped);
  }

  @Test
  public void map2oneStartIntOneEndInt() {


    IntervalMap<String> left = IntervalMap.<String>of()
        .set(Interval.between(0, 3, SECONDS), "b");

    IntervalMap<String> right = IntervalMap.<String>of()
        .set(Interval.between(2, 4, SECONDS), "a");


    IntervalMap<String> mapped = IntervalMap.map2(left,
                                                  right,
                                                  (a$, b$) -> {
                                                    if (a$.isPresent() && b$.isPresent()) {
                                                      return Optional.of(a$.get() + b$.get());
                                                    }
                                                    else if (a$.isPresent() && !b$.isPresent()) {
                                                      return Optional.of(a$.get() + "N");
                                                    }
                                                    else if (b$.isPresent() && !a$.isPresent()) {
                                                      return Optional.of("N" + b$.get());
                                                    }
                                                    else {
                                                      return Optional.of("NN");
                                                    }
                                                  });

    IntervalMap<String> expected = IntervalMap.<String>of()
        .set(Interval.between(Duration.MIN_VALUE, Inclusive, Duration.of(0, SECONDS), Exclusive), "NN")
        .set(Interval.between(Duration.of(0, SECONDS), Inclusive, Duration.of(2, SECONDS), Exclusive), "bN")
        .set(Interval.between(Duration.of(2, SECONDS), Inclusive, Duration.of(3, SECONDS), Inclusive), "ba")
        .set(Interval.between(Duration.of(3, SECONDS), Exclusive, Duration.of(4, SECONDS), Inclusive), "Na")
        .set(Interval.between(Duration.of(4, SECONDS), Exclusive, Duration.MAX_VALUE, Inclusive), "NN");

    assertIterableEquals(expected, mapped);
  }

  @Test
  public void map2oneEndIntOneNullEnd() {


    IntervalMap<String> left = IntervalMap.<String>of()
        .set(Interval.between(2, 4, SECONDS), "b");

    IntervalMap<String> right = IntervalMap.<String>of()
        .set(Interval.between(1, 3, SECONDS), "a");


    IntervalMap<String> mapped = IntervalMap.map2(left,
                                                  right,
                                                  (a$, b$) -> {
                                                    if (a$.isPresent() && b$.isPresent()) {
                                                      return Optional.of(a$.get() + b$.get());
                                                    }
                                                    else if (a$.isPresent() && !b$.isPresent()) {
                                                      return Optional.of(a$.get() + "N");
                                                    }
                                                    else if (b$.isPresent() && !a$.isPresent()) {
                                                      return Optional.of("N" + b$.get());
                                                    }
                                                    else {
                                                      return Optional.of("NN");
                                                    }
                                                  });

    IntervalMap<String> expected = IntervalMap.<String>of()
        .set(Interval.between(Duration.MIN_VALUE, Inclusive, Duration.of(0, SECONDS), Exclusive), "NN")
        .set(Interval.between(Duration.of(0, SECONDS), Inclusive, Duration.of(1, SECONDS), Exclusive), "NN")
        .set(Interval.between(Duration.of(1, SECONDS), Inclusive, Duration.of(2, SECONDS), Exclusive), "Na")
        .set(Interval.between(Duration.of(2, SECONDS), Inclusive, Duration.of(3, SECONDS), Inclusive), "ba")
        .set(Interval.between(Duration.of(3, SECONDS), Exclusive, Duration.of(4, SECONDS), Inclusive), "bN")
        .set(Interval.between(Duration.of(4, SECONDS), Exclusive, Duration.MAX_VALUE, Inclusive), "NN");

    assertIterableEquals(expected, mapped);
  }

  @Test
  public void map2oneStartIntOneNullStart() {


    IntervalMap<String> left = IntervalMap.<String>of()
        .set(Interval.between(0, 2, SECONDS), "b");

    IntervalMap<String> right = IntervalMap.<String>of()
        .set(Interval.between(1, 3, SECONDS), "a");


    IntervalMap<String> mapped = IntervalMap.map2(left,
                                                  right,
                                                  (a$, b$) -> {
                                                    if (a$.isPresent() && b$.isPresent()) {
                                                      return Optional.of(a$.get() + b$.get());
                                                    }
                                                    else if (a$.isPresent() && !b$.isPresent()) {
                                                      return Optional.of(a$.get() + "N");
                                                    }
                                                    else if (b$.isPresent() && !a$.isPresent()) {
                                                      return Optional.of("N" + b$.get());
                                                    }
                                                    else {
                                                      return Optional.of("NN");
                                                    }
                                                  });

    IntervalMap<String> expected = IntervalMap.<String>of()
        .set(Interval.between(Duration.MIN_VALUE, Inclusive, Duration.of(0, SECONDS), Exclusive), "NN")
        .set(Interval.between(Duration.of(0, SECONDS), Inclusive, Duration.of(1, SECONDS), Exclusive), "bN")
        .set(Interval.between(Duration.of(1, SECONDS), Inclusive, Duration.of(2, SECONDS), Inclusive), "ba")
        .set(Interval.between(Duration.of(2, SECONDS), Exclusive, Duration.of(3, SECONDS), Inclusive), "Na")
        .set(Interval.between(Duration.of(3, SECONDS), Exclusive, Duration.of(4, SECONDS), Inclusive), "NN")
        .set(Interval.between(Duration.of(4, SECONDS), Exclusive, Duration.MAX_VALUE, Inclusive), "NN");

    assertIterableEquals(expected, mapped);
  }

  @Test
  public void map2oneEndIntOneEndAt() {


    IntervalMap<String> left = IntervalMap.<String>of()
        .set(Interval.between(2, 4, SECONDS), "b");

    IntervalMap<String> right = IntervalMap.<String>of()
        .set(Interval.between(1, 3, SECONDS), "a")
        .set(Interval.at(Duration.of(4, SECONDS)), "b");


    IntervalMap<String> mapped = IntervalMap.map2(left,
                                                  right,
                                                  (a$, b$) -> {
                                                    if (a$.isPresent() && b$.isPresent()) {
                                                      return Optional.of(a$.get() + b$.get());
                                                    }
                                                    else if (a$.isPresent() && !b$.isPresent()) {
                                                      return Optional.of(a$.get() + "N");
                                                    }
                                                    else if (b$.isPresent() && !a$.isPresent()) {
                                                      return Optional.of("N" + b$.get());
                                                    }
                                                    else {
                                                      return Optional.of("NN");
                                                    }
                                                  });

    IntervalMap<String> expected = IntervalMap.<String>of()
        .set(Interval.between(Duration.MIN_VALUE, Inclusive, Duration.of(0, SECONDS), Exclusive), "NN")
        .set(Interval.between(Duration.of(0, SECONDS), Inclusive, Duration.of(1, SECONDS), Exclusive), "NN")
        .set(Interval.between(Duration.of(1, SECONDS), Inclusive, Duration.of(2, SECONDS), Exclusive), "Na")
        .set(Interval.between(Duration.of(2, SECONDS), Inclusive, Duration.of(3, SECONDS), Inclusive), "ba")
        .set(Interval.between(Duration.of(3, SECONDS), Exclusive, Duration.of(4, SECONDS), Exclusive), "bN")
        .set(Interval.at(Duration.of(4, SECONDS)), "bb")
        .set(Interval.between(Duration.of(4, SECONDS), Exclusive, Duration.MAX_VALUE, Inclusive), "NN");

    assertIterableEquals(expected, mapped);
  }

  @Test
  public void map2oneStartIntStartAt() {


    IntervalMap<String> left = IntervalMap.<String>of()
        .set(Interval.between(0, 2, SECONDS), "b");

    IntervalMap<String> right = IntervalMap.<String>of()
        .set(Interval.at(Duration.of(0, SECONDS)), "b")
        .set(Interval.between(1, 3, SECONDS), "a");


    IntervalMap<String> mapped = IntervalMap.map2(left,
                                                  right,
                                                  (a$, b$) -> {
                                                    if (a$.isPresent() && b$.isPresent()) {
                                                      return Optional.of(a$.get() + b$.get());
                                                    }
                                                    else if (a$.isPresent() && !b$.isPresent()) {
                                                      return Optional.of(a$.get() + "N");
                                                    }
                                                    else if (b$.isPresent() && !a$.isPresent()) {
                                                      return Optional.of("N" + b$.get());
                                                    }
                                                    else {
                                                      return Optional.of("NN");
                                                    }
                                                  });

    IntervalMap<String> expected = IntervalMap.<String>of()
        .set(Interval.between(Duration.MIN_VALUE, Inclusive, Duration.of(0, SECONDS), Exclusive), "NN")
        .set(Interval.at(Duration.of(0, SECONDS)), "bb")
        .set(Interval.between(Duration.of(0, SECONDS), Exclusive, Duration.of(1, SECONDS), Exclusive), "bN")
        .set(Interval.between(Duration.of(1, SECONDS), Inclusive, Duration.of(2, SECONDS), Inclusive), "ba")
        .set(Interval.between(Duration.of(2, SECONDS), Exclusive, Duration.of(3, SECONDS), Inclusive), "Na")
        .set(Interval.between(Duration.of(3, SECONDS), Exclusive, Duration.of(4, SECONDS), Inclusive), "NN")
        .set(Interval.between(Duration.of(4, SECONDS), Inclusive, Duration.MAX_VALUE, Inclusive), "NN");

    assertIterableEquals(expected, mapped);
  }

  @Test
  public void map2alternateStartEndAtInt() {


    IntervalMap<String> left = IntervalMap.<String>of()
        .set(Interval.between(0, 2, SECONDS), "b")
        .set(Interval.at(Duration.of(4, SECONDS)), "a");

    IntervalMap<String> right = IntervalMap.<String>of()
        .set(Interval.at(Duration.of(0, SECONDS)), "b")
        .set(Interval.between(1, 4, SECONDS), "a");


    IntervalMap<String> mapped = IntervalMap.map2(left,
                                                  right,
                                                  (a$, b$) -> {
                                                    if (a$.isPresent() && b$.isPresent()) {
                                                      return Optional.of(a$.get() + b$.get());
                                                    }
                                                    else if (a$.isPresent() && !b$.isPresent()) {
                                                      return Optional.of(a$.get() + "N");
                                                    }
                                                    else if (b$.isPresent() && !a$.isPresent()) {
                                                      return Optional.of("N" + b$.get());
                                                    }
                                                    else {
                                                      return Optional.of("NN");
                                                    }
                                                  });

    IntervalMap<String> expected = IntervalMap.<String>of()
        .set(Interval.between(Duration.MIN_VALUE, Inclusive, Duration.of(0, SECONDS), Exclusive), "NN")
        .set(Interval.at(Duration.of(0, SECONDS)), "bb")
        .set(Interval.between(Duration.of(0, SECONDS), Exclusive, Duration.of(1, SECONDS), Exclusive), "bN")
        .set(Interval.between(Duration.of(1, SECONDS), Inclusive, Duration.of(2, SECONDS), Inclusive), "ba")
        .set(Interval.between(Duration.of(2, SECONDS), Exclusive, Duration.of(4, SECONDS), Exclusive), "Na")
        .set(Interval.at(Duration.of(4, SECONDS)), "aa")
        .set(Interval.between(Duration.of(4, SECONDS), Exclusive, Duration.MAX_VALUE, Inclusive), "NN");

    assertIterableEquals(expected, mapped);
  }

  @Test
  public void map2nullStartEnd() {


    IntervalMap<String> left = IntervalMap.<String>of()
        .set(Interval.between(Duration.of(0, SECONDS), Exclusive, Duration.of(3, SECONDS), Exclusive), "a");

    IntervalMap<String> right = IntervalMap.<String>of()
        .set(Interval.between(Duration.of(2, SECONDS), Exclusive, Duration.of(4, SECONDS), Exclusive), "b");


    IntervalMap<String> mapped = IntervalMap.map2(left,
                                                  right,
                                                  (a$, b$) -> {
                                                    if (a$.isPresent() && b$.isPresent()) {
                                                      return Optional.of(a$.get() + b$.get());
                                                    }
                                                    else if (a$.isPresent() && !b$.isPresent()) {
                                                      return Optional.of(a$.get() + "N");
                                                    }
                                                    else if (b$.isPresent() && !a$.isPresent()) {
                                                      return Optional.of("N" + b$.get());
                                                    }
                                                    else {
                                                      return Optional.of("NN");
                                                    }
                                                  });

    IntervalMap<String> expected = IntervalMap.<String>of()
        .set(Interval.between(Duration.MIN_VALUE, Inclusive, Duration.of(0, SECONDS), Exclusive), "NN")
        .set(Interval.at(Duration.of(0, SECONDS)), "NN")
        .set(Interval.between(Duration.of(0, SECONDS), Exclusive, Duration.of(2, SECONDS), Inclusive), "aN")
        .set(Interval.between(Duration.of(2, SECONDS), Exclusive, Duration.of(3, SECONDS), Exclusive), "ab")
        .set(Interval.between(Duration.of(3, SECONDS), Inclusive, Duration.of(4, SECONDS), Exclusive), "Nb")
        .set(Interval.between(Duration.of(4, SECONDS), Inclusive, Duration.MAX_VALUE, Inclusive), "NN");

    assertIterableEquals(expected, mapped);
  }

  // behold the oss-o-matic 3000
  @Test
  public void map2allvaluesMassive() {
    //if we have 2 values (note, this implementation of intervalmap is agnostic of the number of possible values, we are
    // just doing this so its easy to enumerate and test everything), we have following cases (left, right):
    // (N, N), (a, N), (a, a), (a, b), (b, N), (b, a), (b, b), (N, a), (N, b)

    //also worth testing intervals that overlap to make sure they get split right
    //as a result, our intervals will be (a is "a", b is "b", - is NULL, @ is a in a Interval.at):

    //           NN B   AANBB N
    //           NA A   ABBBN A
    //left:   ...---bb--aa-bbb---bb--@-b--bb--@--@-...
    //right:  ...-aaaaaaabbb-aa----b-a-@----@--@---...
    //    t:  0123456789012345678901234567890123456789
    //                                            (cutoff here, at 35)


    IntervalMap<String> left = IntervalMap.<String>of()
        .set(Interval.between(6, 7, SECONDS), "b")
        .set(Interval.between(10, 11, SECONDS), "a")
        .set(Interval.between(13, 15, SECONDS), "b")
        .set(Interval.between(19, 20, SECONDS), "b")
        .set(Interval.at(Duration.of(23, SECONDS)), "a")
        .set(Interval.between(25, 26, SECONDS), "b")
        .set(Interval.between(28, 29, SECONDS), "b")
        .set(Interval.at(Duration.of(32, SECONDS)), "a")
        .set(Interval.at(Duration.of(35, SECONDS)), "a");

    IntervalMap<String> right = IntervalMap.<String>of()
        .set(Interval.between(4,10, SECONDS), "a")
        .set(Interval.between(11,13, SECONDS), "b")
        .set(Interval.between(15,16, SECONDS), "a")
        .set(Interval.at(Duration.of(21, SECONDS)), "b")
        .set(Interval.at(Duration.of(23, SECONDS)), "a")
        .set(Interval.at(Duration.of(25, SECONDS)), "b")
        .set(Interval.at(Duration.of(29, SECONDS)), "a")
        .set(Interval.at(Duration.of(33, SECONDS)), "b");


    IntervalMap<String> mapped = IntervalMap.map2(left,
                                                  right,
                                                  (a$, b$) -> {
                                                    if (a$.isPresent() && b$.isPresent()) {
                                                      return Optional.of(a$.get() + b$.get());
                                                    }
                                                    else if (a$.isPresent() && !b$.isPresent()) {
                                                      return Optional.of(a$.get() + "N");
                                                    }
                                                    else if (b$.isPresent() && !a$.isPresent()) {
                                                      return Optional.of("N" + b$.get());
                                                    }
                                                    else {
                                                      return Optional.of("NN");
                                                    }
                                                  });

    IntervalMap<String> expected = IntervalMap.<String>of()
        .set(Interval.between(Duration.MIN_VALUE, Inclusive, Duration.of(4, SECONDS), Exclusive), "NN")
        .set(Interval.between(Duration.of(4, SECONDS), Inclusive, Duration.of(6, SECONDS), Exclusive), "Na")
        .set(Interval.between(Duration.of(6, SECONDS), Inclusive, Duration.of(7, SECONDS), Inclusive), "ba")
        .set(Interval.between(Duration.of(7, SECONDS), Exclusive, Duration.of(10, SECONDS), Exclusive), "Na")
        .set(Interval.at(Duration.of(10, SECONDS)), "aa")
        .set(Interval.between(Duration.of(10, SECONDS), Exclusive, Duration.of(11, SECONDS), Exclusive), "aN")
        .set(Interval.at(Duration.of(11, SECONDS)), "ab")
        .set(Interval.between(Duration.of(11, SECONDS), Exclusive, Duration.of(13, SECONDS), Exclusive), "Nb")
        .set(Interval.at(Duration.of(13, SECONDS)), "bb")
        .set(Interval.between(Duration.of(13, SECONDS), Exclusive, Duration.of(15, SECONDS), Exclusive), "bN")
        .set(Interval.at(Duration.of(15, SECONDS)), "ba")
        .set(Interval.between(Duration.of(15, SECONDS), Exclusive, Duration.of(16, SECONDS), Inclusive), "Na")
        .set(Interval.between(Duration.of(16, SECONDS), Exclusive, Duration.of(19, SECONDS), Exclusive), "NN")
        .set(Interval.between(Duration.of(19, SECONDS), Inclusive, Duration.of(20, SECONDS), Inclusive), "bN")
        .set(Interval.between(Duration.of(20, SECONDS), Exclusive, Duration.of(21, SECONDS), Exclusive), "NN")
        .set(Interval.at(Duration.of(21, SECONDS)), "Nb")
        .set(Interval.between(Duration.of(21, SECONDS), Exclusive, Duration.of(23, SECONDS), Exclusive), "NN")
        .set(Interval.at(Duration.of(23, SECONDS)), "aa")
        .set(Interval.between(Duration.of(23, SECONDS), Exclusive, Duration.of(25, SECONDS), Exclusive), "NN")
        .set(Interval.at(Duration.of(25, SECONDS)), "bb")
        .set(Interval.between(Duration.of(25, SECONDS), Exclusive, Duration.of(26, SECONDS), Inclusive), "bN")
        .set(Interval.between(Duration.of(26, SECONDS), Exclusive, Duration.of(28, SECONDS), Exclusive), "NN")
        .set(Interval.between(Duration.of(28, SECONDS), Inclusive, Duration.of(29, SECONDS), Exclusive), "bN")
        .set(Interval.at(Duration.of(29, SECONDS)), "ba")
        .set(Interval.between(Duration.of(29, SECONDS), Exclusive, Duration.of(32, SECONDS), Exclusive), "NN")
        .set(Interval.at(Duration.of(32, SECONDS)), "aN")
        .set(Interval.between(Duration.of(32, SECONDS), Exclusive, Duration.of(33, SECONDS), Exclusive), "NN")
        .set(Interval.at(Duration.of(33, SECONDS)), "Nb")
        .set(Interval.between(Duration.of(33, SECONDS), Exclusive, Duration.of(35, SECONDS), Exclusive), "NN")
        .set(Interval.at(Duration.of(35, SECONDS)), "aN")
        .set(Interval.between(Duration.of(35, SECONDS), Exclusive, Duration.MAX_VALUE, Inclusive), "NN");

    assertIterableEquals(expected, mapped);
  }
}
