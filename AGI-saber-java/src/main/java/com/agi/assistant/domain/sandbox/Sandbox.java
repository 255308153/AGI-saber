package com.agi.assistant.domain.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Sandbox 封装 Validator + Executor + 审计回调（对应 Go internal/sandbox/executor.go Sandbox）。
 *
 * <p>领域聚合；具体后端实现（Docker/Local/Mock）通过依赖注入由
 * {@code infrastructure.sandbox.SandboxFactory} 提供。</p>
 */
public class Sandbox {

    private static final Logger log = LoggerFactory.getLogger(Sandbox.class);

    private final Validator validator;
    private final Executor executor;
    private volatile Consumer<ExecResult> auditFn;

    public Sandbox(Validator validator, Executor executor) {
        this.validator = validator;
        this.executor = executor;
    }

    public void setAuditFn(Consumer<ExecResult> fn) { this.auditFn = fn; }

    public Validator validator() { return validator; }

    public String backend() { return executor.backend(); }

    /** Exec 主入口：先校验，再执行，最后审计。 */
    public ExecResult exec(ExecRequest req) {
        ValidationResult validation = validator.validate(req.getCommand());

        ExecResult result = new ExecResult();
        result.setCommand(req.getCommand());
        result.setValidation(validation);
        result.setBackend(executor.backend());

        // Block：直接拒绝
        if (validation.getLevel() == RiskLevel.BLOCK) {
            result.setExitCode(-1);
            result.setStderr("[拒绝执行] " + validation.getReason());
            audit(result);
            return result;
        }
        // Warn 但未确认
        if (validation.getLevel() == RiskLevel.WARN && !req.isConfirm()) {
            result.setExitCode(-2);
            result.setStderr("[需要确认] 该命令触发以下规则：" + validation.getViolations()
                    + "；请重新调用并设置 confirm=true");
            audit(result);
            return result;
        }

        ExecResult execResult = executor.exec(req);
        execResult.setCommand(req.getCommand());
        execResult.setValidation(validation);
        execResult.setBackend(executor.backend());

        audit(execResult);
        return execResult;
    }

    private void audit(ExecResult r) {
        Consumer<ExecResult> fn = this.auditFn;
        if (fn != null) {
            new Thread(() -> {
                try { fn.accept(r); } catch (Exception ignored) {}
            }, "sandbox-audit").start();
        }
    }
}
