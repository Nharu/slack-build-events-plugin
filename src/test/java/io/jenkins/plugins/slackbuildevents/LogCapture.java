package io.jenkins.plugins.slackbuildevents;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Captures {@link LogRecord}s from a named logger for the life of a try-with-resources block, so a
 * test can assert on the WARNING signals emitted by the dispatch path (the whole point of #15 is that
 * these become visible at the default level).
 *
 * <p>The handler is attached to a process-global {@link Logger}, which is safe only because this suite
 * runs sequentially (the POM configures no test parallelism or forking). Enabling parallel tests would
 * require per-test isolation here.
 */
final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final Handler handler;
    final List<LogRecord> records = new CopyOnWriteArrayList<>();

    LogCapture(Class<?> loggerOwner) {
        this.logger = Logger.getLogger(loggerOwner.getName());
        this.previousLevel = logger.getLevel();
        this.handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(Level.ALL);
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    /** Count of WARNING-or-higher records whose message contains {@code needle}. */
    long warningsContaining(String needle) {
        return records.stream()
                .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                .filter(r -> String.valueOf(r.getMessage()).contains(needle))
                .count();
    }

    /** Total count of WARNING-or-higher records. */
    long warningCount() {
        return records.stream()
                .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                .count();
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
        logger.setLevel(previousLevel);
    }
}
