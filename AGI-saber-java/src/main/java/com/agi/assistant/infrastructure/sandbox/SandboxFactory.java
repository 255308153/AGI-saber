package com.agi.assistant.infrastructure.sandbox;

import com.agi.assistant.config.AppConfig;
import com.agi.assistant.domain.sandbox.Executor;
import com.agi.assistant.domain.sandbox.Sandbox;
import com.agi.assistant.domain.sandbox.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 沙箱工厂（对应 Go infrastructure/sandbox/factory.go）。
 *
 * <p>从 backend 名称（docker/local/mock）+ 配置 构造领域 {@link Sandbox} 聚合，
 * Docker 不可用时自动降级到 mock。</p>
 */
public final class SandboxFactory {

    private static final Logger log = LoggerFactory.getLogger(SandboxFactory.class);

    private SandboxFactory() {}

    public static Sandbox build(String backend,
                                AppConfig.SandboxConfig sandboxCfg,
                                AppConfig.SecurityConfig secCfg) {
        Validator validator = new Validator(secCfg);
        Executor exec;
        switch (backend == null ? "" : backend) {
            case "docker" -> {
                DockerSandbox ds = new DockerSandbox(sandboxCfg);
                if (ds.available()) {
                    exec = ds;
                } else {
                    log.warn("Docker 不可用，沙箱降级到 mock 模式");
                    exec = new MockSandbox();
                }
            }
            case "local" -> exec = new LocalSandbox(sandboxCfg);
            case "mock" -> exec = new MockSandbox();
            default -> {
                log.warn("未知沙箱后端 {}，使用 mock", backend);
                exec = new MockSandbox();
            }
        }
        return new Sandbox(validator, exec);
    }
}
