package dev.poolpilot.spring;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Strict integer binding for management requests and persisted settings. */
public final class StrictNumbers {
  private StrictNumbers() {}

  public static class IntegerValue extends JsonDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
        return (Integer) context.handleUnexpectedToken(Integer.class, parser);
      return parser.getIntValue();
    }
  }

  /** Missing primitive fields default to zero; boxed settings require non-null values. */
  public static final class PrimitiveIntegerValue extends IntegerValue {
    @Override
    public Integer getNullValue(DeserializationContext context) {
      return 0;
    }
  }

  public static final class LongValue extends JsonDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
      if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
        return (Long) context.handleUnexpectedToken(Long.class, parser);
      return parser.getLongValue();
    }
  }
}
