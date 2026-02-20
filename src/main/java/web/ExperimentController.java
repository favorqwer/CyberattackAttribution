package web;

import guru.nidi.graphviz.engine.Graphviz;
import guru.nidi.graphviz.engine.GraphvizV8Engine;
import org.springframework.web.bind.annotation.*;
import pagerank.ExperimentRunnerCmd;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ExperimentController {

    @PostMapping("/run-analysis")
    public Map<String, Object> runAnalysis(@RequestBody AnalyzeRequest request) {
        Map<String, Object> response = new HashMap<>();

        if (request.getLogPath() == null || request.getResPath() == null || request.getLogNames() == null) {
            response.put("status", "error");
            response.put("message", "参数缺失");
            return response;
        }

        // ========== 新增：静默初始化 Graphviz V8 引擎（与 main 方法中一致）==========
        // 关闭 GraalVM 解释模式警告
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");

        // 备份原始输出流
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        try {
            // 创建一个“黑洞”输出流，吞掉所有输出
            PrintStream nullStream = new PrintStream(new OutputStream() {
                @Override
                public void write(int b) {
                    // 什么都不做
                }
            });

            // 重定向 stdout 和 stderr 到黑洞，防止 Graphviz 初始化时的 INFO 日志和警告污染控制台
            System.setOut(nullStream);
            System.setErr(nullStream);

            // 核心：强制初始化 Graphviz V8 引擎（此时所有日志都被吞掉）
            Graphviz.useEngine(new GraphvizV8Engine());
        } catch (Throwable t) {
            // 静默忽略任何初始化异常
        } finally {
            // 立即恢复原始输出流，确保后续业务输出正常
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        // ============================================================================

        new Thread(() -> {
            try {
                String[] logs = request.getLogNames().split(";");
                ExperimentRunnerCmd er = new ExperimentRunnerCmd(request.getLogPath(), request.getResPath(), logs);
                er.run2();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
        response.put("status", "accepted");
        response.put("message", "分析已在后台启动，完成后查看 " + new File(request.getResPath()).getAbsolutePath().replace("\\", "/"));
        return response;
    }
}