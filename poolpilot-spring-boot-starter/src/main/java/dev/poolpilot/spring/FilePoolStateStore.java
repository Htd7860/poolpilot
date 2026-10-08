package dev.poolpilot.spring;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.poolpilot.core.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;

/** JSON state storage with an exclusive file lock and atomic replacement. */
public final class FilePoolStateStore implements PoolStateStore {
  private static final int MAX_BYTES = 1_048_576;
  private final Path path;
  private final ObjectMapper mapper;
  private FileChannel lockChannel;
  private FileLock lock;
  private boolean closed;

  public FilePoolStateStore(Path path) {
    this.path = path.toAbsolutePath().normalize();
    mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
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
    try {
      if (this.path.getFileName() == null) throw new IOException("State path must be a file");
      Files.createDirectories(this.path.getParent());
      Path lockPath = this.path.resolveSibling(this.path.getFileName() + ".lock");
      lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      lock = lockChannel.tryLock();
      if (lock == null) throw new IOException("State path is already in use");
    } catch (IOException | RuntimeException ex) {
      closeOnFailure(ex);
      throw new StateStoreException("Cannot acquire PoolPilot state file lock", ex);
    }
  }

  @Override
  public synchronized RegistryState load() {
    requireOpen();
    try (var input = Files.newInputStream(path)) {
      byte[] bytes = input.readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES) throw new IOException("State document exceeds 1 MiB");
      JsonNode document = mapper.readTree(bytes);
      JsonNode pools = document.get("pools");
      if (pools != null && pools.isObject())
        for (JsonNode pool : pools) {
          if (pool.isObject() && !pool.has("sourceRevisions"))
            ((com.fasterxml.jackson.databind.node.ObjectNode) pool).putObject("sourceRevisions");
        }
      return mapper.treeToValue(document, RegistryState.class);
    } catch (NoSuchFileException missing) {
      return RegistryState.empty();
    } catch (IOException | RuntimeException ex) {
      throw new StateStoreException("Cannot read PoolPilot state; refusing to discard history", ex);
    }
  }

  @Override
  public synchronized void save(RegistryState state) {
    requireOpen();
    Path temporary = null;
    try {
      byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state);
      if (bytes.length > MAX_BYTES) throw new IOException("State document exceeds 1 MiB");
      temporary = Files.createTempFile(path.getParent(), ".poolpilot-state-", ".tmp");
      try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) output.write(buffer);
        output.force(true);
      }
      // Atomic replacement preserves the old document if the move fails.
      Files.move(
          temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException | RuntimeException ex) {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException cleanup) {
          ex.addSuppressed(cleanup);
        }
      }
      throw new StateStoreException("Cannot persist PoolPilot change", ex);
    }
  }

  private void requireOpen() {
    if (closed) throw new StateStoreException("PoolPilot state store is closed", null);
  }

  private void closeOnFailure(Throwable failure) {
    try {
      close();
    } catch (StateStoreException cleanup) {
      failure.addSuppressed(cleanup);
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    try {
      if (lock != null) lock.release();
    } catch (IOException ex) {
      throw new StateStoreException("Cannot release PoolPilot state lock", ex);
    } finally {
      if (lockChannel != null) {
        try {
          lockChannel.close();
        } catch (IOException ex) {
          throw new StateStoreException("Cannot close PoolPilot state lock", ex);
        }
      }
    }
  }
}
