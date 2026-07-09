package gov.nasa.jpl.aerie.merlin.framework.junit;

import gov.nasa.jpl.aerie.merlin.driver.DirectiveTypeRegistry;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.MissionModelBuilder;
import gov.nasa.jpl.aerie.merlin.driver.SimulationDriver;
import gov.nasa.jpl.aerie.merlin.framework.InitializationContext;
import gov.nasa.jpl.aerie.merlin.framework.ModelActions;
import gov.nasa.jpl.aerie.merlin.framework.Registrar;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.DynamicTestInvocationContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.api.extension.TestInstancePreDestroyCallback;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public final class MerlinExtension
    implements BeforeAllCallback, ParameterResolver, InvocationInterceptor, TestInstancePreDestroyCallback
{
  private State getState(final ExtensionContext context) {
    return context
        .getStore(ExtensionContext.Namespace.create(context.getRequiredTestClass()))
        .getOrComputeIfAbsent("state", $ -> new State(new MissionModelBuilder()), State.class);
  }

  @Override
  public void beforeAll(final ExtensionContext context) {
    final var lifecycle = context.getTestInstanceLifecycle().orElse(TestInstance.Lifecycle.PER_METHOD);
    if (lifecycle != TestInstance.Lifecycle.PER_CLASS) {
      throw new IllegalTestLifetimeException(context.getRequiredTestClass());
    }
  }

  @Override
  public boolean supportsParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext)
  throws ParameterResolutionException
  {
    return parameterContext.getParameter().getType().equals(Registrar.class);
  }

  @Override
  public Object resolveParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext)
  throws ParameterResolutionException
  {
    final var state = this.getState(extensionContext);
    final var paramType = parameterContext.getParameter().getType();

    if (paramType.equals(Registrar.class)) {
      if (state.registrar == null) state.registrar = new Registrar(state.builder);
      return state.registrar;
    } else {
      throw new Error("Unsupported Merlin extension parameter type: " + paramType.getSimpleName());
    }
  }

  @Override
  public <T> T interceptTestClassConstructor(
      final Invocation<T> invocation,
      final ReflectiveInvocationContext<Constructor<T>> invocationContext,
      final ExtensionContext extensionContext) throws Throwable
  {
    return this.getState(extensionContext).constructModel(invocation);
  }

  @Override
  public void interceptTestTemplateMethod(
      final Invocation<Void> invocation,
      final ReflectiveInvocationContext<Method> invocationContext,
      final ExtensionContext extensionContext) throws Throwable
  {
    this.getState(extensionContext).simulate(invocation);
  }

  @Override
  public void interceptTestMethod(
      final Invocation<Void> invocation,
      final ReflectiveInvocationContext<Method> invocationContext,
      final ExtensionContext extensionContext
  ) throws Throwable
  {
    this.getState(extensionContext).simulate(invocation);
  }

  @Override
  public void interceptDynamicTest(final Invocation<Void> invocation, final DynamicTestInvocationContext invocationContext, final ExtensionContext extensionContext)
  throws Throwable
  {
    this.getState(extensionContext).simulate(invocation);
  }

  @Override
  public void preDestroyTestInstance(final ExtensionContext extensionContext) {
    final var state = this.getState(extensionContext);
    state.missionModel = null;
  }

  private static final class State {
    public MissionModelBuilder builder;
    public Registrar registrar;

    public MissionModel<Unit> missionModel = null;

    public State(final MissionModelBuilder builder) {
      this.builder = Objects.requireNonNull(builder);
      this.registrar = new Registrar(this.builder);
    }

    public <T> T constructModel(final Invocation<T> invocation) throws Throwable {
      final T value;
      try {
        value = InitializationContext.initializing(this.builder, () -> {
          try {
            return invocation.proceed();
          } catch (final RuntimeException ex) {
            throw ex;
          } catch (final Throwable ex) {
            throw new WrappedException(ex);
          }
        });
      } catch (final WrappedException ex) {
        throw ex.wrapped;
      }

      this.missionModel = this.builder.build(Unit.UNIT, new DirectiveTypeRegistry<>(Map.of()));

      // Clear the builder; it shouldn't be used from here on, and if it is, an error should be raised.
      this.builder = null;
      this.registrar = null;

      return value;
    }

    private void simulate(final Invocation<Void> invocation) throws Throwable {
      final var completed = new Object() { boolean value = false; };

      final var task = ModelActions
          .threaded(() -> {
            try {
              invocation.proceed();
            } catch (final Throwable ex) {
              throw new WrappedException(ex);
            } finally {
              completed.value = true;
            }
          });

      try {
        var driver = new SimulationDriver<Unit>(this.missionModel, Instant.now(), Duration.MAX_VALUE);
        driver.simulateTask(task);
      } catch (final WrappedException ex) {
        throw ex.wrapped;
      }

      if (!completed.value) {
        throw new AssertionError("test did not complete");
      }
    }
  }

  /** An exception for tunneling checked exceptions through an interface that expects no exceptions. */
  private static final class WrappedException extends Error {
    public final Throwable wrapped;

    public WrappedException(final Throwable ex) {
      super(null, ex, /* capture suppressed exceptions? */ false, /* capture stack trace? */ false);
      this.wrapped = ex;
    }
  }

  private static final class IllegalTestLifetimeException extends RuntimeException {
    public IllegalTestLifetimeException(final Class<?> offendingClass) {
      super("%s expects %s to be annotated with @TestInstance(Lifecycle.PER_CLASS)"
                .formatted(MerlinExtension.class.getSimpleName(), offendingClass));
    }
  }
}
