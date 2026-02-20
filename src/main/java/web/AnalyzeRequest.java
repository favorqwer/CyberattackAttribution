package web;

public class AnalyzeRequest {
    private String logPath;
    private String resPath;
    private String logNames;

    // Getters and Setters
    public String getLogPath() {
        return logPath;
    }

    public void setLogPath(String logPath) {
        this.logPath = logPath;
    }

    public String getResPath() {
        return resPath;
    }

    public void setResPath(String resPath) {
        this.resPath = resPath;
    }

    public String getLogNames() {
        return logNames;
    }

    public void setLogNames(String logNames) {
        this.logNames = logNames;
    }
}
