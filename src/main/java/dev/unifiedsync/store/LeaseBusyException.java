package dev.unifiedsync.store;

public final class LeaseBusyException extends Exception {
  public LeaseBusyException(String message) {
    super(message);
  }
}
