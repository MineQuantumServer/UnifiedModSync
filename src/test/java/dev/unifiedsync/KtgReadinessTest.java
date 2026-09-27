package dev.unifiedsync;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class KtgReadinessTest {
  public interface Go4Player {}

  public static final class Player implements Go4Player {}

  public static final class PlayerStatus {}

  public static final class Work {
    boolean ready;

    public boolean isLoaded(PlayerStatus status) {
      throw new AssertionError("Wrong overload");
    }

    public boolean isLoaded(Object other) {
      throw new AssertionError("Wrong overload");
    }

    public boolean isLoaded(Go4Player player) {
      return ready;
    }
  }

  public static final class IncompatibleWork {
    public boolean isLoaded(PlayerStatus status) {
      return true;
    }
  }

  public static final class InvalidReturnWork {
    public String isLoaded(Go4Player player) {
      return "true";
    }
  }

  @Test
  void selectsPlayerContractAndPreservesWaitingState() throws Exception {
    Work work = new Work();
    Player player = new Player();
    assertFalse(KtgReadiness.isLoaded(work, player, Go4Player.class));
    work.ready = true;
    assertTrue(KtgReadiness.isLoaded(work, player, Go4Player.class));
  }

  @Test
  void missingExpectedOverloadFailsClosed() {
    assertThrows(
        NoSuchMethodException.class,
        () -> KtgReadiness.isLoaded(new IncompatibleWork(), new Player(), Go4Player.class));
  }

  @Test
  void incompatibleAdapterFailsClosed() {
    assertThrows(
        IllegalStateException.class,
        () -> KtgReadiness.isLoaded(new Work(), new PlayerStatus(), Go4Player.class));
  }

  @Test
  void invalidReturnTypeFailsClosed() {
    assertThrows(
        NoSuchMethodException.class,
        () -> KtgReadiness.isLoaded(new InvalidReturnWork(), new Player(), Go4Player.class));
  }
}
