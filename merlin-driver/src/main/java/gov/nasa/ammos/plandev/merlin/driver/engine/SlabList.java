package gov.nasa.jpl.aerie.merlin.driver.engine;

import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * An append-only list comprising a chain of fixed-size slabs.
 *
 * The fixed-size slabs allow for better cache locality when traversing the list forward,
 * and the chain of links allows for cheap extension when a slab reaches capacity.
 */
public final class SlabList<T> implements Iterable<T> {
  /** ~4 KiB of elements (or at least, references thereof). */
  private static final int SLAB_SIZE = 1024;

  private final Slab<T> head = new Slab<>();

  /*derived*/
  private Slab<T> tail = this.head;
  /*derived*/
  private int size = 0;
  private boolean frozen = false;

  public void append(final T element) {
    if (this.frozen) {
      throw new IllegalStateException("Cannot append to frozen SlabList");
    }
    this.tail.elements().add(element);
    this.size += 1;

    if (this.size % SLAB_SIZE == 0) {
      this.tail.next().setValue(new Slab<>());
      this.tail = this.tail.next().getValue();
    }
  }

  public int size() {
    return this.size;
  }

  @Override
  public boolean equals(final Object o) {
    if (!(o instanceof SlabList<?> other)) return false;

    return Objects.equals(this.head, other.head);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.head);
  }

  @Override
  public String toString() {
    return SlabList.class.getSimpleName() + "[" + this.head + ']';
  }

  /**
   * Returns an iterator that is stable through appends.
   *
   * If hasNext() returns false and then additional elements are added to the list,
   * the iterator can be reused to continue from where it left off.
   */
  @Override
  public SlabIterator iterator() {
    return new SlabIterator();
  }

  public final class SlabIterator implements Iterator<T> {
    private Slab<T> slab = SlabList.this.head;
    private int index = 0;

    private SlabIterator() {}

    @Override
    public boolean hasNext() {
      if (this.index < this.slab.elements().size()) return true;

      final var nextSlab = this.slab.next().getValue();
      if (nextSlab == null || nextSlab.elements().isEmpty()) return false;

      this.index -= this.slab.elements().size();
      this.slab = nextSlab;

      return true;
    }

    @Override
    public T next() {
      if (!hasNext()) throw new NoSuchElementException();

      return this.slab.elements().get(this.index++);
    }
  }

  record Slab<T>(ArrayList<T> elements, Mutable<Slab<T>> next) {
    public Slab() {
      this(new ArrayList<>(SLAB_SIZE), new MutableObject<>(null));
    }
  }

  public SlabList<T> duplicate() {
    final SlabList<T> slabList = new SlabList<>();
    for (T t : this) {
      slabList.append(t);
    }
    return slabList;
  }

  public void freeze() {
    this.frozen = true;
  }
}
