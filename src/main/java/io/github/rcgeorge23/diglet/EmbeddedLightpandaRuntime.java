package io.github.rcgeorge23.diglet;

import io.lightpanda.spike.EmbeddedLightpanda;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Process-wide owner for Lightpanda's thread-affine, terminal native runtime. */
final class EmbeddedLightpandaRuntime {
    static final String LIBRARY_PROPERTY = "lightpanda.library";
    static final String LIBRARY_ENVIRONMENT_VARIABLE = "LIGHTPANDA_LIBRARY";

    private static final class Holder {
        private static final EmbeddedLightpandaRuntime INSTANCE = new EmbeddedLightpandaRuntime();
    }

    private final ExecutorService ownerExecutor;
    private final Map<Long, EmbeddedLightpanda.Session> sessions = new HashMap<>();
    private volatile Thread ownerThread;
    private EmbeddedLightpanda browser;
    private long nextSessionId;
    private boolean shutdownStarted;

    static EmbeddedLightpandaRuntime instance() {
        return Holder.INSTANCE;
    }

    private EmbeddedLightpandaRuntime() {
        ownerExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(() -> {
                ownerThread = Thread.currentThread();
                task.run();
            }, "diglet-embedded-lightpanda");
            thread.setDaemon(true);
            return thread;
        });
        java.lang.Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "diglet-lightpanda-shutdown"));
    }

    EmbeddedLightpandaPage openPage() {
        long sessionId;
        try {
            sessionId = onOwner(() -> {
                ensureBrowser();
                long id = ++nextSessionId;
                sessions.put(id, browser.newSession());
                return id;
            });
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Could not create an embedded Lightpanda session", e);
        }
        return new EmbeddedLightpandaPage(this, sessionId);
    }

    String call(long sessionId, String tool, String argumentsJson) throws IOException, InterruptedException {
        return onOwner(() -> session(sessionId).call(tool, argumentsJson));
    }

    long pump(long sessionId) throws IOException, InterruptedException {
        return onOwner(() -> session(sessionId).pump());
    }

    void closeSession(long sessionId) {
        try {
            onOwner(() -> {
                EmbeddedLightpanda.Session session = sessions.remove(sessionId);
                if (session != null) {
                    session.close();
                }
                return null;
            });
        } catch (IOException e) {
            throw new IllegalStateException("Could not close embedded Lightpanda session", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing embedded Lightpanda session", e);
        }
    }

    private EmbeddedLightpanda.Session session(long sessionId) {
        EmbeddedLightpanda.Session session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("Embedded Lightpanda session is closed");
        }
        return session;
    }

    private void ensureBrowser() {
        if (shutdownStarted) {
            throw new IllegalStateException("Embedded Lightpanda has been shut down and cannot be restarted");
        }
        if (browser == null) {
            browser = EmbeddedLightpanda.load(libraryPath());
        }
    }

    private static Path libraryPath() {
        String configured = System.getProperty(LIBRARY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(LIBRARY_ENVIRONMENT_VARIABLE);
        }
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Set -D" + LIBRARY_PROPERTY + " or " + LIBRARY_ENVIRONMENT_VARIABLE
                    + " to the path of liblightpanda before using Browser.EMBEDDED_LIGHTPANDA");
        }

        Path path = Path.of(configured).toAbsolutePath();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Lightpanda native library does not exist: " + path);
        }
        return path;
    }

    private <T> T onOwner(Callable<T> operation) throws IOException, InterruptedException {
        if (Thread.currentThread() == ownerThread) {
            try {
                return operation.call();
            } catch (IOException | InterruptedException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Embedded Lightpanda operation failed", e);
            }
        }

        Future<T> result = ownerExecutor.submit(operation);
        try {
            return result.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Embedded Lightpanda operation failed", cause);
        }
    }

    private void shutdown() {
        try {
            onOwner(() -> {
                shutdownStarted = true;
                if (browser != null) {
                    sessions.clear();
                    browser.close();
                    browser = null;
                }
                return null;
            });
        } catch (IOException | InterruptedException | RuntimeException ignored) {
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            ownerExecutor.shutdown();
        }
    }
}
