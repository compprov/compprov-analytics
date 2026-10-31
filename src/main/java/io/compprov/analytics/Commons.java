package io.compprov.analytics;

import io.compprov.core.ComputationEnvironment;
import io.compprov.core.DefaultComputationEnvironment;
import tools.jackson.core.JacksonException;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class Commons {
    public static final DateTimeFormatter FOLDER_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss.SSS");
    public static final Logger LOGGER = Logger.getLogger("analytics-main");
    public static final ComputationEnvironment ENV = DefaultComputationEnvironment.create();

    static {
        LOGGER.setUseParentHandlers(false);
        final var handler = new ConsoleHandler();
        handler.setFormatter(new Formatter() {
            private final DateTimeFormatter timestampFormat = DateTimeFormatter.ISO_DATE_TIME;

            @Override
            public String format(LogRecord record) {
                final var timestamp = timestampFormat.format(ZonedDateTime.ofInstant(record.getInstant(), ZoneId.systemDefault()));
                final var sb = new StringBuilder()
                        .append(timestamp).append(' ')
                        .append(record.getSourceClassName()).append(' ')
                        .append(record.getSourceMethodName()).append(' ')
                        .append(record.getLevel()).append(": ")
                        .append(formatMessage(record))
                        .append(System.lineSeparator());
                if (record.getThrown() != null) {
                    final var sw = new StringWriter();
                    record.getThrown().printStackTrace(new PrintWriter(sw));
                    sb.append(sw).append(System.lineSeparator());
                }
                return sb.toString();
            }
        });
        LOGGER.addHandler(handler);
    }

    private Commons() {
    }

    public static String serialize(Object data) {
        try {
            return ENV.getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (JacksonException e) {
            throw new IllegalStateException("Unable to serialize object: " + data, e);
        }
    }

    public static void save(String data, String address) {
        try {
            Files.writeString(new File(address).toPath(), data,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write file: " + address, e);
        }
    }

    public static String read(String address) {
        try {
            return Files.readString(new File(address).toPath());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read file: " + address, e);
        }
    }
}
