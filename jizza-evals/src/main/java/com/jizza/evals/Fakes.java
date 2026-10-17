package com.jizza.evals;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * In-memory stand-ins for the repositories the runtime reads, so a scenario runs the real agent
 * code with no database. A method without a handler answers "nothing": an empty list, an empty
 * optional, zero, false.
 */
final class Fakes {

  private Fakes() {}

  @SuppressWarnings("unchecked")
  static <T> T of(Class<T> type, Map<String, Function<Object[], Object>> handlers) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
        (proxy, method, args) -> {
          if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
              case "equals" -> proxy == args[0];
              case "hashCode" -> System.identityHashCode(proxy);
              default -> type.getSimpleName() + " (eval fake)";
            };
          }
          Function<Object[], Object> handler = handlers.get(method.getName());
          if (handler != null) return handler.apply(args == null ? new Object[0] : args);
          if (method.isDefault()) {
            return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
          }
          return nothing(method.getReturnType());
        });
  }

  private static Object nothing(Class<?> type) {
    if (type == Optional.class) return Optional.empty();
    if (List.class.isAssignableFrom(type) || type == Collection.class || type == Iterable.class) {
      return List.of();
    }
    if (Set.class.isAssignableFrom(type)) return Set.of();
    if (type == boolean.class) return false;
    if (type == long.class) return 0L;
    if (type == int.class) return 0;
    if (type == double.class) return 0d;
    if (type == BigDecimal.class) return BigDecimal.ZERO;
    return null;
  }
}
