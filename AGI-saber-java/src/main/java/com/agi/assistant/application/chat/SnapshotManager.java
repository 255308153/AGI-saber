package com.agi.assistant.application.chat;

import com.agi.assistant.infrastructure.InfrastructureService;
import com.agi.assistant.model.Snapshot;
import com.agi.assistant.model.TaskState;
import com.agi.assistant.model.TaskStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 快照管理器（对应 Go application/chat/snapshot.go）。
 *
 * <p>持有内存级 snapshots 列表，并把 TaskState 序列化到 PG。</p>
 */
public class SnapshotManager {

    private static final ObjectMapper M = new ObjectMapper().registerModule(new JavaTimeModule());

    private final InfrastructureService infra;
    private final List<Snapshot> snapshots = Collections.synchronizedList(new ArrayList<>());

    public SnapshotManager(InfrastructureService infra) {
        this.infra = infra;
    }

    /** 拍一份快照（深拷贝 TaskState 后写入内存与 PG）。 */
    public void save(TaskState currentTask) {
        if (currentTask == null) return;
        try {
            String json = M.writeValueAsString(currentTask);
            TaskState copy = M.readValue(json, TaskState.class);
            snapshots.add(new Snapshot(copy,
                    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));
            infra.saveSnapshot(currentTask.getTaskId(), json);
        } catch (Exception ignored) {}
    }

    /** 清空内存快照（用于新任务开始时）。 */
    public void clear() { snapshots.clear(); }

    /** 副本（避免外部修改）。 */
    public List<Snapshot> snapshots() { return new ArrayList<>(snapshots); }

    /** 中断时构造摘要消息：已完成 X/Y 步 ... */
    public static String buildInterruptMessage(TaskState task) {
        int done = 0;
        List<String> doneDesc = new ArrayList<>();
        List<String> pendingDesc = new ArrayList<>();
        for (TaskStep s : task.getSteps()) {
            if (TaskStep.DONE.equals(s.getStatus())) {
                done++;
                String r = s.getResult() != null && s.getResult().length() > 30
                        ? s.getResult().substring(0, 30) + "..." : s.getResult();
                doneDesc.add(s.getId() + "." + s.getToolName() + "→" + r);
            } else {
                pendingDesc.add(s.getId() + "." + s.getToolName());
            }
        }
        StringBuilder msg = new StringBuilder("已完成 " + done + "/" + task.getSteps().size() + " 步");
        if (!doneDesc.isEmpty()) msg.append("：").append(String.join("；", doneDesc));
        if (!pendingDesc.isEmpty()) msg.append("；未执行：").append(String.join("、", pendingDesc));
        return msg.toString();
    }
}
