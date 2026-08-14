package gov.nasa.jpl.aerie.json;

public abstract class Breadcrumb {
  private Breadcrumb() {}

  public abstract <Result> Result visit(BreadcrumbVisitor<Result> visitor);

  public interface BreadcrumbVisitor<Result> {
    Result onString(String s);
    Result onInteger(Integer i);
  }

  public static Breadcrumb ofString(String breadcrumb) {
    return new Breadcrumb() {
      @Override
      public <Result> Result visit(final BreadcrumbVisitor<Result> visitor) {
        return visitor.onString(breadcrumb);
      }

      @Override
      public String toString() {
        return breadcrumb;
      }
    };
  }

  public static Breadcrumb ofInteger(int breadcrumb) {
    return new Breadcrumb() {
      @Override
      public <Result> Result visit(final BreadcrumbVisitor<Result> visitor) {
        return visitor.onInteger(breadcrumb);
      }

      @Override
      public String toString() {
        return Integer.toString(breadcrumb);
      }
    };
  }
}
