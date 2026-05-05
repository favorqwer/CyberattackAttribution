package logparsers.cdm;

import java.util.Locale;
import java.util.Set;

public final class CdmEventMapper {
    public enum EntityKind {
        PROCESS,
        FILE,
        NETWORK,
        OBJECT
    }

    public enum FlowKind {
        OBJECT_TO_PROCESS,
        PROCESS_TO_OBJECT,
        PROCESS_TO_PROCESS,
        OBJECT_TO_OBJECT,
        SKIP
    }

    private static final Set<String> OBJECT_TO_PROCESS = Set.of(
            "READ",
            "RECVFROM",
            "RECVMSG",
            "ACCEPT",
            "CHECK_FILE_ATTRIBUTES",
            "LOADLIBRARY"
    );

    private static final Set<String> PROCESS_TO_OBJECT = Set.of(
            "WRITE",
            "SENDTO",
            "SENDMSG",
            "CONNECT",
            "BIND",
            "CREATE_OBJECT",
            "TRUNCATE",
            "MODIFY_FILE_ATTRIBUTES",
            "UNLINK"
    );

    private static final Set<String> PROCESS_TO_PROCESS = Set.of(
            "FORK",
            "CLONE",
            "CREATE_THREAD",
            "SIGNAL",
            "MODIFY_PROCESS"
    );

    private static final Set<String> OBJECT_TO_OBJECT = Set.of(
            "RENAME",
            "LINK",
            "FLOWS_TO",
            "TEE",
            "SPLICE",
            "SHM",
            "UPDATE"
    );

    private static final Set<String> SKIP = Set.of(
            "BOOT",
            "EXIT",
            "WAIT",
            "CLOSE",
            "OPEN",
            "LSEEK",
            "DUP",
            "FCNTL",
            "OTHER"
    );

    private CdmEventMapper() {
    }

    public static FlowKind flowKind(String cdmEventType) {
        String event = normalizeEvent(cdmEventType);
        if (event == null || SKIP.contains(event)) {
            return FlowKind.SKIP;
        }
        if ("EXECUTE".equals(event)) {
            return FlowKind.OBJECT_TO_PROCESS;
        }
        if (OBJECT_TO_PROCESS.contains(event)) {
            return FlowKind.OBJECT_TO_PROCESS;
        }
        if (PROCESS_TO_OBJECT.contains(event)) {
            return FlowKind.PROCESS_TO_OBJECT;
        }
        if (PROCESS_TO_PROCESS.contains(event)) {
            return FlowKind.PROCESS_TO_PROCESS;
        }
        if (OBJECT_TO_OBJECT.contains(event)) {
            return FlowKind.OBJECT_TO_OBJECT;
        }
        return FlowKind.SKIP;
    }

    public static boolean isExecute(String cdmEventType) {
        return "EXECUTE".equals(normalizeEvent(cdmEventType));
    }

    public static String edgeType(EntityKind sourceKind, EntityKind sinkKind) {
        if (sourceKind == EntityKind.PROCESS && sinkKind == EntityKind.FILE) {
            return "PtoF";
        }
        if (sourceKind == EntityKind.FILE && sinkKind == EntityKind.PROCESS) {
            return "FtoP";
        }
        if (sourceKind == EntityKind.PROCESS && sinkKind == EntityKind.NETWORK) {
            return "PtoN";
        }
        if (sourceKind == EntityKind.NETWORK && sinkKind == EntityKind.PROCESS) {
            return "NtoP";
        }
        if (sourceKind == EntityKind.PROCESS && sinkKind == EntityKind.PROCESS) {
            return "PtoP";
        }
        return "ObjectFlow";
    }

    public static String normalizeEvent(String cdmEventType) {
        if (cdmEventType == null) {
            return null;
        }
        String event = cdmEventType.trim().toUpperCase(Locale.ROOT);
        if (event.startsWith("EVENT_")) {
            event = event.substring("EVENT_".length());
        }
        return event;
    }
}
