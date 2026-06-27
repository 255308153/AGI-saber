package com.agi.assistant.application.chat;

import com.agi.assistant.model.ToolCallResult;
import com.agi.assistant.service.memory.PreferenceMemory;

import java.util.List;
import java.util.Map;

/**
 * 偏好填参器（对应 Go application/chat 中 fillParamsFromPreference）。
 *
 * <p>把用户偏好里的「城市/时区/姓名/语言/国家」自动填到工具参数中
 * （仅在原值为空或缺失时填入）。</p>
 */
public final class PreferenceFiller {

    private static final Map<String, List<String>> PREF_TO_PARAM = Map.of(
            "城市", List.of("city", "location", "location_name"),
            "时区", List.of("timezone", "tz", "time_zone"),
            "姓名", List.of("name", "username", "user_name"),
            "语言", List.of("language", "lang"),
            "国家", List.of("country", "nation")
    );

    private PreferenceFiller() {}

    public static void fill(ToolCallResult tc, PreferenceMemory pref) {
        if (tc == null || pref.getData().isEmpty()) return;
        for (Map.Entry<String, List<String>> e : PREF_TO_PARAM.entrySet()) {
            String prefVal = pref.getData().get(e.getKey());
            if (prefVal == null || prefVal.isEmpty()) continue;
            for (String paramName : e.getValue()) {
                Object v = tc.getParams().get(paramName);
                if (v == null || v.toString().isEmpty()) {
                    tc.getParams().put(paramName, prefVal);
                }
            }
        }
    }
}
