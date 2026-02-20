package pagerank;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ArgParser {
    String argString;
    Map<String,String> arguments;

    public ArgParser(String[] args){
        argString = String.join(" ",args);
    }

    public Map<String, String> parseArgs(){
        arguments = new HashMap<>();
        Pattern pPath = Pattern.compile("-p ([^ ]+?)(?: |$)");
        Pattern pDetection = Pattern.compile("-d ([^ ]+?)(?: |$)");
        Pattern pHigh = Pattern.compile("-h (.+?)(?:(?: -)|$)");
        Pattern pNeutral = Pattern.compile("-n (.+?)(?:(?: -)|$)");
        Pattern pLow = Pattern.compile("-l (.+?)(?:(?: -)|$)");
        Pattern pRes = Pattern.compile("-r ([^ ]+?)(?: |$)");
        Pattern pSuffix = Pattern.compile("-s ([^ ]+?)(?: |$)");
        Pattern pThresh = Pattern.compile("-t ([^ ]+?)(?: |$)");
        Pattern pOrigin = Pattern.compile("-o");

        Matcher mPath = pPath.matcher(argString);
        Matcher mDetection = pDetection.matcher(argString);
        Matcher mHigh = pHigh.matcher(argString);
        Matcher mNeutral = pNeutral.matcher(argString);
        Matcher mLow = pLow.matcher(argString);
        Matcher mRes = pRes.matcher(argString);
        Matcher mSuffix = pSuffix.matcher(argString);
        Matcher mThresh = pThresh.matcher(argString);
        Matcher mOrigin = pOrigin.matcher(argString);

        if(!mPath.find() || !mDetection.find() || !mThresh.find()){
            prompt();
            System.exit(1);
        }

        String path = mPath.group(1);
        String detection = mDetection.group(1);
        String high = mHigh.find()?mHigh.group(1):null;
        String neutral = mNeutral.find()?mNeutral.group(1):null;
        String low = mLow.find()?mLow.group(1):null;
        String res = mRes.find()&&!mRes.group(1).equals(".")?mRes.group(1):System.getProperty("user.dir");
        String suffix = mSuffix.find()?mSuffix.group(1):"";
        String thresh = mThresh.group(1);

        if(!new File(path).exists() || !new File(res).exists() || !new File(res).isDirectory()){
            System.out.println("No such file or directory!");
            System.exit(2);
        }

        arguments.put("path",path);
        arguments.put("detection",detection);
        arguments.put("high",high);
        arguments.put("neutral",neutral);
        arguments.put("low",low);
        arguments.put("res",res);
        arguments.put("suffix",suffix);
        arguments.put("thresh",thresh);
        if(mOrigin.find()) arguments.put("origin","");

        //test();

    return arguments;

    }

    private void test(){
        System.out.println(argString);
        for(String s:arguments.keySet())
            System.out.println(s+": "+arguments.get(s));
    }

    private void prompt(){
        System.out.println("Usage:\n" +
                "ProcessOneLog\n" +
                "-p <path to log file>\n" +
                "-d <POI>\n" +
                "-h [highRP,...]\n" +
                "-n [neutralRP,...]\n" +
                "-l [lowRP,...]\n" +
                "-r [output dir]\n" +
                "-s [suffix]\n" +
                "-t <threshold>\n" +
                "-o [track origin only?]");
    }

}
