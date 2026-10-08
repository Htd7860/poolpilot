package dev.poolpilot.spring;

import dev.poolpilot.core.TaskDecorator;
import java.util.Map;
import org.slf4j.MDC;

/** Capture at submission; restore the worker/caller's original context in finally. */
public final class MdcTaskDecorator implements TaskDecorator {
  @Override
  public Runnable decorate(Runnable task) {
    Map<String, String> captured = MDC.getCopyOfContextMap();
    return () -> {
      Map<String, String> previous = MDC.getCopyOfContextMap();
      try {
        apply(captured);
        task.run();
      } finally {
        apply(previous);
      }
    };
  }

  private static void apply(Map<String, String> context) {
    if (context == null) MDC.clear();
    else MDC.setContextMap(context);
  }
}
