package logparsers;

import logparsers.exceptions.InvalidLogFormatException;
import pagerank.*;

import java.io.IOException;
import java.util.Map;

public interface SysdigOutputParser {
    public Map<String, PtoFEvent> getPfmap();

    public Map<String, PtoNEvent> getPnmap();

    public Map<String, PtoPEvent> getPpmap();

    public Map<String, NtoPEvent> getNpmap();

    public Map<String, FtoPEvent> getFpmap();

    public void getEntities() throws IOException;

    public void afterBuilding();
}
