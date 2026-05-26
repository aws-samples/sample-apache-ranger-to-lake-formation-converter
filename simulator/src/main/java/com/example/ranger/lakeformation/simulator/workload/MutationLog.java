package com.example.ranger.lakeformation.simulator.workload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MutationLog {
    private static final Logger LOG = LoggerFactory.getLogger(MutationLog.class);

    private final Path logPath;
    private final ObjectMapper mapper;
    private final List<MutationOperation> entries = new ArrayList<>();

    public MutationLog(Path logPath) {
        this.logPath = logPath;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /** Append one operation to the in-memory list and flush to disk. */
    public synchronized void append(MutationOperation op) throws IOException {
        entries.add(op);
        String json = mapper.writeValueAsString(new LogEntry(op.timestamp(), op.getClass().getSimpleName(), op));
        Files.writeString(logPath, json + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Return an unmodifiable snapshot of all logged operations. */
    public synchronized List<MutationOperation> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** Return the path this log writes to. */
    public Path getLogPath() { return logPath; }

    /** Clear in-memory entries (does not truncate file). */
    public synchronized void clear() { entries.clear(); }

    record LogEntry(Instant timestamp, String type, Object payload) {}
}
