package com.agi.assistant.application.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatRouterTest {

    @Test
    void chatModeForOrdinaryQuery() {
        assertEquals("chat", ChatRouter.decideMode("你好", false, false, null, false));
    }

    @Test
    void toolModeForSingleToolKeyword() {
        assertEquals("tool", ChatRouter.decideMode("现在几点", false, false, null, false));
    }

    @Test
    void reactModeForCompoundQuery() {
        assertEquals("react", ChatRouter.decideMode(
                "查一下北京天气并搜索路况", false, false, null, false));
    }

    @Test
    void ragModeWhenLoadedAndNoToolTrigger() {
        assertEquals("rag", ChatRouter.decideMode("介绍一下这本书的主旨", false, false, null, true));
    }

    @Test
    void explicitSelectedToolsForcesReact() {
        assertEquals("react", ChatRouter.decideMode(
                "你好", true, false, List.of("get_time"), false));
    }

    @Test
    void explicitUseRagWhenLoaded() {
        assertEquals("rag", ChatRouter.decideMode("test", true, true, null, true));
    }

    @Test
    void explicitWithoutAnythingFallsBackToChat() {
        assertEquals("chat", ChatRouter.decideMode("test", true, false, null, false));
    }

    @Test
    void needToolKeywordCoverage() {
        assertTrue(ChatRouter.needTool("查询订单"));
        assertTrue(ChatRouter.needTool("帮我搜索"));
        assertTrue(ChatRouter.needTool("Java 是什么"));
        assertFalse(ChatRouter.needTool("你好"));
    }
}
