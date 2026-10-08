package dev.poolpilot.spring;

@FunctionalInterface
public interface PoolAlertListener {
  void onAlert(PoolAlert alert);
}
