package dev.poolpilot.spring;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.*;
import com.fasterxml.jackson.databind.type.LogicalType;
import dev.poolpilot.core.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/** Reads a bounded JSON document from an HTTP endpoint, including Nacos configuration URLs. */
public final class HttpPoolConfigSource implements PoolConfigSource {
  public enum Format {
    DOCUMENT,
    NACOS_V2,
    NACOS_V3
  }

  private final String id;
  private final URI uri;
  private final String bearerToken;
  private final Duration timeout;
  private final HttpClient client;
  private final ObjectMapper mapper;
  private final Format format;
  private final String accessToken;
  private final AtomicReference<CompletableFuture<?>> pending = new AtomicReference<>();
  private volatile boolean closed;

  public HttpPoolConfigSource(String id, URI uri, String bearerToken, Duration timeout) {
    this(id, uri, bearerToken, timeout, Format.DOCUMENT, null);
  }

  public HttpPoolConfigSource(
      String id, URI uri, String bearerToken, Duration timeout, Format format, String accessToken) {
    if (id == null
        || !id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
        || uri.getUserInfo() != null
        || uri.getHost() == null
        || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || timeout.toMillis() < 100
        || timeout.toMillis() > 30000)
      throw new IllegalArgumentException("Invalid HTTP configuration source");
    if (bearerToken != null && !bearerToken.matches("[\\x21-\\x7E]{1,4096}"))
      throw new IllegalArgumentException("Invalid source access token");
    if (accessToken != null && !accessToken.matches("[\\x21-\\x7E]{1,4096}"))
      throw new IllegalArgumentException("Invalid Nacos access token");
    this.format = java.util.Objects.requireNonNull(format);
    this.accessToken = accessToken;
    this.id = id;
    this.uri = uri;
    this.bearerToken = bearerToken;
    this.timeout = timeout;
    client =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    mapper = new ObjectMapper();
    mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    mapper.enable(
        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
        DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    mapper
        .coercionConfigFor(LogicalType.Integer)
        .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
    mapper
        .coercionConfigFor(LogicalType.Boolean)
        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public PoolConfigDocument fetch() throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder(uri).GET().timeout(timeout).header("Accept", "application/json");
    if (bearerToken != null) request.header("Authorization", "Bearer " + bearerToken);
    if (accessToken != null) request.header("accessToken", accessToken);
    if (closed) throw new IOException("Configuration source is closed");
    CompletableFuture<HttpResponse<byte[]>> response =
        client.sendAsync(request.build(), info -> new LimitedBody());
    if (!pending.compareAndSet(null, response)) {
      response.cancel(true);
      throw new IOException("Configuration request already in progress");
    }
    try {
      if (closed) {
        response.cancel(true);
        throw new IOException("Configuration source is closed");
      }
      var result = response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (result.statusCode() != 200)
        throw new IOException("Configuration endpoint returned " + result.statusCode());
      if (format == Format.DOCUMENT)
        return mapper.readValue(result.body(), PoolConfigDocument.class);
      JsonNode envelope = mapper.readTree(result.body());
      JsonNode code = envelope.get("code");
      if (code == null || !code.isIntegralNumber() || code.asLong() != 0)
        throw new IOException("Nacos configuration request failed");
      JsonNode content =
          format == Format.NACOS_V2 ? envelope.get("data") : envelope.path("data").get("content");
      if (content == null || !content.isTextual())
        throw new IOException("Nacos response has no configuration content");
      return mapper.readValue(content.textValue(), PoolConfigDocument.class);
    } catch (ExecutionException | TimeoutException | CancellationException ex) {
      throw new IOException("Configuration request failed", ex);
    } finally {
      pending.compareAndSet(response, null);
      response.cancel(true);
    }
  }

  @Override
  public void close() {
    closed = true;
    var request = pending.get();
    if (request != null) request.cancel(true);
  }

  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    public CompletionStage<byte[]> getBody() {
      return body;
    }

    public void onSubscribe(Flow.Subscription value) {
      subscription = value;
      value.request(1);
    }

    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > 1048576 - bytes.size()) {
          subscription.cancel();
          body.completeExceptionally(new IOException("Configuration document exceeds 1 MiB"));
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    public void onError(Throwable error) {
      body.completeExceptionally(error);
    }

    public void onComplete() {
      body.complete(bytes.toByteArray());
    }
  }
}
