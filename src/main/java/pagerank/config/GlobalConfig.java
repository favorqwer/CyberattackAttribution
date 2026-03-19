package pagerank.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public final class GlobalConfig {
    public static final String DEFAULT_CONFIG_FILE = "depimpact.properties";

    private static final String[] DEFAULT_LOCAL_IP = {"127.0.0.1"};
    private static final String[] DEFAULT_MID_RP = new String[0];
    private static final String[] DEFAULT_PTOP_SYSTEM_CALL = {"execve"};
    private static final String[] DEFAULT_PTOF_SYSTEM_CALL = {"write", "writev"};
    private static final String[] DEFAULT_FTOP_SYSTEM_CALL = {"read", "readv"};
    private static final String[] DEFAULT_PTON_SYSTEM_CALL = {"sendto", "write", "writev", "sendmsg"};
    private static final String[] DEFAULT_NTOP_SYSTEM_CALL = {"read", "recvmsg", "recvfrom", "readv"};

    private static final GlobalConfig INSTANCE = new GlobalConfig();

    private final File configFile;
    private final String[] localIP;
    private final String[] midRP;
    private final String[] ptopSystemCall;
    private final String[] ptofSystemCall;
    private final String[] ftopSystemCall;
    private final String[] ptonSystemCall;
    private final String[] ntopSystemCall;
    private final String weightMode;
    private final String cprMode;
    private final double cprTimeWindow;
    private final boolean llmEnabled;
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final double temperature;
    private final int maxTokens;

    private GlobalConfig() {
        this.configFile = resolveConfigFile();
        Properties props = new Properties();
        if (configFile.exists() && configFile.isFile()) {
            try (InputStream input = new FileInputStream(configFile)) {
                props.load(input);
                System.out.println("Loaded global config: " + configFile.getAbsolutePath());
            } catch (Exception e) {
                System.err.println("Warning: Failed to load global config from " + configFile.getAbsolutePath()
                        + ". Using built-in defaults.");
            }
        } else {
            System.err.println("Warning: Global config file not found: " + configFile.getAbsolutePath()
                    + ". Using built-in defaults.");
        }

        this.localIP = getArray(props, "local_ip", DEFAULT_LOCAL_IP);
        this.midRP = getArray(props, "default_mid_rp", DEFAULT_MID_RP);
        this.ptopSystemCall = getArray(props, "syscall.ptop", DEFAULT_PTOP_SYSTEM_CALL);
        this.ptofSystemCall = getArray(props, "syscall.ptof", DEFAULT_PTOF_SYSTEM_CALL);
        this.ftopSystemCall = getArray(props, "syscall.ftop", DEFAULT_FTOP_SYSTEM_CALL);
        this.ptonSystemCall = getArray(props, "syscall.pton", DEFAULT_PTON_SYSTEM_CALL);
        this.ntopSystemCall = getArray(props, "syscall.ntop", DEFAULT_NTOP_SYSTEM_CALL);
        this.weightMode = getString(props, "weight_mode", "clusterall");
        this.cprMode = getString(props, "cpr_mode", "window");
        this.cprTimeWindow = getDouble(props, "cpr_time_window", 10.0d);
        this.llmEnabled = Boolean.parseBoolean(getString(props, "llm_enabled", "false"));
        this.baseUrl = getString(props, "base_url", "");
        this.apiKey = getString(props, "api_key", "");
        this.modelName = getString(props, "model", "");
        this.temperature = getDouble(props, "temperature", 0.1d);
        this.maxTokens = getInt(props, "max_tokens", 20480);
    }

    public static GlobalConfig getInstance() {
        return INSTANCE;
    }

    public File getConfigFile() {
        return configFile;
    }

    public String[] getLocalIP() {
        return Arrays.copyOf(localIP, localIP.length);
    }

    public String[] getMidRP() {
        return Arrays.copyOf(midRP, midRP.length);
    }

    public String[] getPtopSystemCall() {
        return Arrays.copyOf(ptopSystemCall, ptopSystemCall.length);
    }

    public String[] getPtofSystemCall() {
        return Arrays.copyOf(ptofSystemCall, ptofSystemCall.length);
    }

    public String[] getFtopSystemCall() {
        return Arrays.copyOf(ftopSystemCall, ftopSystemCall.length);
    }

    public String[] getPtonSystemCall() {
        return Arrays.copyOf(ptonSystemCall, ptonSystemCall.length);
    }

    public String[] getNtopSystemCall() {
        return Arrays.copyOf(ntopSystemCall, ntopSystemCall.length);
    }

    public String getWeightMode() {
        return weightMode;
    }

    public String getCprMode() {
        return cprMode;
    }

    public double getCprTimeWindow() {
        return cprTimeWindow;
    }

    public boolean isLlmEnabled() {
        return llmEnabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public double getTemperature() {
        return temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    private static File resolveConfigFile() {
        String configuredPath = System.getProperty("depimpact.config");
        if (configuredPath != null && !configuredPath.trim().isEmpty()) {
            return new File(configuredPath.trim());
        }
        return new File(DEFAULT_CONFIG_FILE);
    }

    private static String getString(Properties props, String key, String defaultValue) {
        String value = props.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? defaultValue : trimmed;
    }

    private static int getInt(Properties props, String key, int defaultValue) {
        try {
            return Integer.parseInt(getString(props, key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double getDouble(Properties props, String key, double defaultValue) {
        try {
            return Double.parseDouble(getString(props, key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String[] getArray(Properties props, String key, String[] defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return Arrays.copyOf(defaultValue, defaultValue.length);
        }
        List<String> values = splitCsv(raw);
        if (values.isEmpty()) {
            return Arrays.copyOf(defaultValue, defaultValue.length);
        }
        Set<String> deduplicated = new LinkedHashSet<>(values);
        return deduplicated.toArray(new String[0]);
    }

    private static List<String> splitCsv(String raw) {
        List<String> values = new ArrayList<>();
        for (String item : raw.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }
}
