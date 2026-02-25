package logparsers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class Utils {

    private static final String DELIMITER = ";;";
    private static final int DELIMITER_LENGTH = 2;

    public static Map<String, String> parseEntryWithoutIDNew(String entry) {
        Map<String, String> res = new HashMap<>(10);
        res.put("raw", entry);

        int from = 0;
        String[] firstEight = new String[8];
        for (int i = 0; i < 8; i++) {
            int delimiterIndex = entry.indexOf(DELIMITER, from);
            if (delimiterIndex < 0) {
                return res;
            }
            firstEight[i] = entry.substring(from, delimiterIndex);
            from = delimiterIndex + DELIMITER_LENGTH;
        }

        int argsEnd = entry.indexOf(DELIMITER, from);
        if (argsEnd < 0) {
            argsEnd = entry.length();
        }

        if (firstEight[7] == null) {
            return res;
        }

        res.put("timestamp", firstEight[0]);
        res.put("cpu", firstEight[1]);
        res.put("process", firstEight[2]);
        res.put("pid", firstEight[3]);
        res.put("direction", firstEight[4]);
        res.put("event", firstEight[5]);
        res.put("cwd", firstEight[6]);
        res.put("latency", firstEight[7]);
        res.put("args", entry.substring(from, argsEnd));

        return res;
    }

    public static Map<String, String> parseEntryWithoutID(String entry) {
        Map<String, String> res = new HashMap<>();
        String[] fields = entry.split(" ");
        res.put("raw", entry);
        res.put("timestamp", fields[0]);
        res.put("cpu", fields[1]);
        res.put("process", fields[2]);
        res.put("pid", fields[3].substring(1, fields[3].length() - 1));
        res.put("direction", fields[4]);
        res.put("event", fields[5]);
        res.put("cwd", fields[6].substring(4));
        res.put("latency", fields[fields.length - 1].substring(8));
        res.put("args", String.join(" ", Arrays.copyOfRange(fields, 7, fields.length - 2)));
        return res;
    }

    public static Map<String, String> parseEntry_without_exec(String entry) {
        Map<String, String> res = new HashMap<>();
        String[] fields = entry.split(" ");
        res.put("raw", entry);
        res.put("timestamp", fields[1]);
        res.put("cpu", fields[2]);
        res.put("process", fields[3]);
        res.put("pid", fields[4].substring(1, fields[4].length() - 1));
        res.put("direction", fields[5]);
        res.put("event", fields[6]);
        res.put("cwd", fields[7].substring(4));
        res.put("latency", fields[fields.length - 1].substring(8));
        res.put("args", String.join(" ", Arrays.copyOfRange(fields, 7, fields.length - 1)));
        return res;
    }

    public static Map<String, String> parseEntry(String entry) {
        Map<String, String> res = new HashMap<>();
        String[] fields = entry.split(" ");
        res.put("raw", entry);
        res.put("timestamp", fields[1]);
        res.put("cpu", fields[2]);
        res.put("process", fields[3]);
        res.put("pid", fields[4].substring(1, fields[4].length() - 1));
        res.put("direction", fields[5]);
        res.put("event", fields[6]);
        res.put("cwd", fields[7].substring(4));
        if (fields[fields.length - 1].startsWith("exepath")) {
            res.put("latency", fields[fields.length - 2].substring(8));
            res.put("args", String.join(" ", Arrays.copyOfRange(fields, 7, fields.length - 2)));
        } else {
            res.put("latency", fields[fields.length - 1].substring(8));
            res.put("args", String.join(" ", Arrays.copyOfRange(fields, 7, fields.length - 1)));
        }

        return res;
    }

    public static Map<String, String> parseEntryWithHost(String entry) {
        HashMap<String, String> res = new HashMap<String, String>();
        String[] fields = entry.split(" ");
        res.put("raw", entry);
        res.put("timestamp", fields[1]);
        res.put("host", fields[2]);
        res.put("cpu", fields[3]);
        res.put("process", fields[4]);
        res.put("pid", fields[5].substring(1, fields[5].length() - 1));
        res.put("direction", fields[6]);
        res.put("event", fields[7]);
        res.put("cwd", fields[8].substring(4));
        if (fields[fields.length - 1].startsWith("exepath")) {
            res.put("latency", fields[fields.length - 2].substring(8));
            res.put("args", String.join((CharSequence) " ", Arrays.copyOfRange(fields, 7, fields.length - 2)));
        } else {
            res.put("latency", fields[fields.length - 1].substring(8));
            res.put("args", String.join((CharSequence) " ", Arrays.copyOfRange(fields, 7, fields.length - 1)));
        }
        return res;
    }

    static long extractSize(String s) {
        int marker = s.indexOf("res=");
        if (marker < 0) {
            return -1L;
        }
        int start = marker + 4;
        if (start >= s.length() || s.charAt(start) == '-') {
            return -1L;
        }
        int end = start;
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        if (end == start) {
            return -1L;
        }
        return Long.parseLong(s.substring(start, end));
    }

    static Map<String, String> extractFileandSocket(String s) {
        String path = null;
        String srcIP = null;
        String srcPort = null;
        String destIP = null;
        String destPort = null;
        String socket = null;

        Map<String, String> res = new HashMap<>();

        int fdIndex = s.indexOf("fd=");
        if (fdIndex >= 0) {
            int fdEnd = s.indexOf(' ', fdIndex);
            if (fdEnd < 0) {
                fdEnd = s.length();
            }
            String fd = s.substring(fdIndex, fdEnd);
            int index = 0;
            while (index < fd.length()) {
                if (fd.charAt(index) == '>') {
                    if (fd.charAt(index - 1) == 'f') {
                        path = fd.substring(index + 1, fd.length() - 1);
                        if (path.contains("("))
                            path = path.substring(path.indexOf("(") + 1, path.length() - 1);
                        break;
                    }
                    if (fd.charAt(index - 1) == 't' || fd.charAt(index - 1) == 'u') {
                        if (fd.charAt(index - 2) == '6' || fd.charAt(index - 2) == '4') {
                            socket = fd.substring(index + 1, fd.length() - 1);
                            if (!socket.equals("")) {
                                String[] portsAndIp = getIPandPorts(socket); // 0:src ip 1: src port 2:dest ip 3:dest
                                                                             // port
                                srcIP = portsAndIp[0];
                                srcPort = portsAndIp[1];
                                destIP = portsAndIp[2];
                                destPort = portsAndIp[3];
                                break;
                            }
                        }
                    }
                    if (fd.charAt(index - 1) == 'r' && fd.charAt(index - 2) == '4') {
                        socket = fd.substring(index + 1, fd.length() - 1);
                        if (!socket.equals("")) {
                            String[] portsAndIp = getIPs(socket); // 0:src ip 1: src port 2:dest ip 3:dest port
                            srcIP = portsAndIp[0];
                            srcPort = "UNKNOWN";
                            destIP = portsAndIp[1];
                            destPort = "UNKNOWN";
                            break;
                        }
                    }
                }
                index++;
            }
        }

        if (path != null)
            res.put("path", path);
        if (srcIP != null)
            res.put("sip", srcIP);
        if (srcPort != null)
            res.put("sport", srcPort);
        if (destIP != null)
            res.put("dip", destIP);
        if (destPort != null)
            res.put("dport", destPort);
        if (socket != null && !socket.equals(""))
            res.put("socket", socket);
        return res;
    }

    static String extractProcessFile(String s) {
        String path = null;
        int filenameIndex = s.indexOf("filename=");
        if (filenameIndex >= 0) {
            int start = filenameIndex + 9;
            int end = s.indexOf(' ', start);
            if (end < 0) {
                end = s.length();
            }
            path = s.substring(start, end);
            if (path.contains("("))
                path = path.substring(path.indexOf("(") + 1, path.length() - 1);
        }
        return path;
    }

    private static String[] getIPandPorts(String str) {
        String[] res = new String[4];
        int arrow = str.indexOf("->");
        if (arrow < 0) {
            throw new ArrayIndexOutOfBoundsException("Can't parse socket!");
        }

        String src = str.substring(0, arrow);
        String dest = str.substring(arrow + 2);

        int srcColon = src.lastIndexOf(':');
        int destColon = dest.lastIndexOf(':');
        if (srcColon < 0 || destColon < 0) {
            throw new ArrayIndexOutOfBoundsException("Can't parse socket!");
        }

        res[0] = src.substring(0, srcColon);
        res[1] = src.substring(srcColon + 1);
        res[2] = dest.substring(0, destColon);
        res[3] = dest.substring(destColon + 1);
        if (res[3].equals("domamin")) {
            res[3] = "53";
        }
        return res;
    }

    private static String[] getIPs(String str) {
        int arrow = str.indexOf("->");
        if (arrow < 0) {
            return new String[]{str, ""};
        }
        return new String[]{str.substring(0, arrow), str.substring(arrow + 2)};
    }
}
