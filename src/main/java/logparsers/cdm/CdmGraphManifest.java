package logparsers.cdm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CdmGraphManifest {
    public static final String INPUT_DIR = "cdm.input_dir";
    public static final String FILE_GLOB = "cdm.file_glob";
    public static final String START_NANOS = "cdm.start_nanos";
    public static final String END_NANOS = "cdm.end_nanos";
    public static final String MAX_EVENTS = "cdm.max_events";

    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private final Path manifestPath;
    private final Path inputDir;
    private final String fileGlob;
    private final long startNanos;
    private final long endNanos;
    private final long maxEvents;

    private CdmGraphManifest(Path manifestPath, Path inputDir, String fileGlob,
                             long startNanos, long endNanos, long maxEvents) {
        this.manifestPath = manifestPath;
        this.inputDir = inputDir;
        this.fileGlob = fileGlob;
        this.startNanos = startNanos;
        this.endNanos = endNanos;
        this.maxEvents = maxEvents;
    }

    public static CdmGraphManifest load(Path manifestPath) throws IOException {
        Objects.requireNonNull(manifestPath, "manifestPath");
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(manifestPath)) {
            properties.load(in);
        }

        Path inputDir = resolveInputDir(manifestPath, require(properties, INPUT_DIR));
        String fileGlob = require(properties, FILE_GLOB);
        long startNanos = parseLong(properties, START_NANOS);
        long endNanos = parseLong(properties, END_NANOS);
        long maxEvents = parseLong(properties, MAX_EVENTS);

        if (endNanos < startNanos) {
            throw new IOException(END_NANOS + " must be greater than or equal to " + START_NANOS);
        }
        if (maxEvents < 0) {
            throw new IOException(MAX_EVENTS + " must be 0 or a positive number");
        }

        return new CdmGraphManifest(manifestPath.toAbsolutePath().normalize(), inputDir, fileGlob,
                startNanos, endNanos, maxEvents);
    }

    public List<Path> resolveInputFiles() throws IOException {
        if (!Files.isDirectory(inputDir)) {
            throw new IOException("CDM input directory does not exist: " + inputDir);
        }

        PathMatcher matcher = inputDir.getFileSystem().getPathMatcher("glob:" + fileGlob);
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(inputDir)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file) && matcher.matches(file.getFileName())) {
                    files.add(file);
                }
            }
        }

        files.sort(CdmGraphManifest::compareNaturallyByFileName);
        if (files.isEmpty()) {
            throw new IOException("No CDM Avro files matched " + fileGlob + " in " + inputDir);
        }
        return files;
    }

    public Path getManifestPath() {
        return manifestPath;
    }

    public Path getInputDir() {
        return inputDir;
    }

    public String getFileGlob() {
        return fileGlob;
    }

    public long getStartNanos() {
        return startNanos;
    }

    public long getEndNanos() {
        return endNanos;
    }

    public long getMaxEvents() {
        return maxEvents;
    }

    private static String require(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("Missing required CDM manifest property: " + key);
        }
        return value.trim();
    }

    private static long parseLong(Properties properties, String key) throws IOException {
        String value = require(properties, key);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid long value for " + key + ": " + value, e);
        }
    }

    private static Path resolveInputDir(Path manifestPath, String rawInputDir) {
        Path configured = Paths.get(rawInputDir);
        if (configured.isAbsolute()) {
            return configured.normalize();
        }

        Path cwdRelative = configured.toAbsolutePath().normalize();
        if (Files.exists(cwdRelative)) {
            return cwdRelative;
        }

        Path parent = manifestPath.toAbsolutePath().getParent();
        if (parent == null) {
            return cwdRelative;
        }
        return parent.resolve(configured).normalize();
    }

    private static int compareNaturallyByFileName(Path left, Path right) {
        return compareNaturally(left.getFileName().toString(), right.getFileName().toString());
    }

    private static int compareNaturally(String left, String right) {
        Matcher leftMatcher = NUMBER_PATTERN.matcher(left);
        Matcher rightMatcher = NUMBER_PATTERN.matcher(right);
        int leftIndex = 0;
        int rightIndex = 0;

        while (true) {
            boolean leftFound = leftMatcher.find();
            boolean rightFound = rightMatcher.find();
            if (!leftFound || !rightFound) {
                break;
            }

            int textCompare = left.substring(leftIndex, leftMatcher.start())
                    .compareToIgnoreCase(right.substring(rightIndex, rightMatcher.start()));
            if (textCompare != 0) {
                return textCompare;
            }

            long leftNumber = Long.parseLong(leftMatcher.group(1));
            long rightNumber = Long.parseLong(rightMatcher.group(1));
            int numberCompare = Long.compare(leftNumber, rightNumber);
            if (numberCompare != 0) {
                return numberCompare;
            }

            leftIndex = leftMatcher.end();
            rightIndex = rightMatcher.end();
        }

        int tailCompare = left.substring(leftIndex).compareToIgnoreCase(right.substring(rightIndex));
        if (tailCompare != 0) {
            return tailCompare;
        }
        return left.compareTo(right);
    }
}
