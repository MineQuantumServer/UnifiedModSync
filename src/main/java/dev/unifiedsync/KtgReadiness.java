package dev.unifiedsync;

import java.lang.reflect.Method;

/** Resolve the player overload explicitly; Work also exposes isLoaded(PlayerStatus). */
final class KtgReadiness {
  static boolean isLoaded(Object work, Object player, Class<?> playerContract) throws Exception {
    if (!playerContract.isInstance(player)) {
      throw new IllegalStateException(
          "KTG4 player adapter does not implement its Go4Player contract");
    }
    Method method = work.getClass().getMethod("isLoaded", playerContract);
    if (method.getReturnType() != boolean.class) {
      throw new NoSuchMethodException("Expected boolean KTG4 Work.isLoaded(Go4Player)");
    }
    return (boolean) method.invoke(work, player);
  }
}
