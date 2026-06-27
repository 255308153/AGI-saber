package com.agi.assistant.domain.sandbox;

/**
 * 沙箱执行器统一接口（对应 Go sandbox.Executor）。
 *
 * <p>领域接口；具体实现位于 {@code infrastructure.sandbox.*}（Docker/Local/Mock）。</p>
 */
public interface Executor {
    ExecResult exec(ExecRequest req);

    String backend();

    boolean available();
}
