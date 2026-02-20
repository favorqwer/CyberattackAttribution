# 1. 设置代理（仅对当前会话有效）
$env:http_proxy = "http://127.0.0.1:7897"
$env:https_proxy = "http://127.0.0.1:7897"

# 3. 屏蔽 Node.js 的弃用警告（可选，让界面更干净）
$env:NODE_NO_WARNINGS = "1"

Write-Host "[系统] 正在启动 Gemini CLI..." -ForegroundColor Cyan

# 4. 执行 gemini
# 使用 & 符号调用外部命令
try {
    & gemini
}
catch {
    Write-Host "[错误] 无法启动 gemini，请检查是否已安装 nodejs。" -ForegroundColor Red
}

# 5. 防止窗口直接关闭
Read-Host -Prompt "按回车键退出..."