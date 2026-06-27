package com.agi.assistant.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tavily Search API 客户端（对应 Go internal/infrastructure/tool/tavily.go）。
 *
 * <p>调用失败或未配置 API key 时调用方应降级到 LLM 知识库直接回答。</p>
 */
public final class TavilyClient {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient();

    private TavilyClient() {}

    public static String search(String query, String apiKey, String apiUrl) throws Exception {
        if (apiUrl == null || apiUrl.isEmpty()) apiUrl = "https://api.tavily.com/search";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("api_key", apiKey);
        body.put("query", query);
        body.put("search_depth", "basic");
        body.put("max_results", 5);
        String json = mapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(apiUrl)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Tavily 返回错误状态 " + response.code());
            }
            String respBody = response.body() != null ? response.body().string() : "";
            JsonNode root = mapper.readTree(respBody);
            String answer = root.has("answer") ? root.get("answer").asText("") : "";
            JsonNode results = root.get("results");

            if (!answer.isEmpty()) {
                StringBuilder sb = new StringBuilder(answer);
                if (results != null && results.isArray() && !results.isEmpty()) {
                    sb.append("\n\n**来源：**\n");
                    int n = Math.min(3, results.size());
                    for (int i = 0; i < n; i++) {
                        JsonNode r = results.get(i);
                        sb.append("- [").append(r.path("title").asText("")).append("](")
                                .append(r.path("url").asText("")).append(")\n");
                    }
                }
                return sb.toString();
            }
            if (results == null || !results.isArray() || results.isEmpty()) {
                throw new RuntimeException("Tavily 返回空结果");
            }
            StringBuilder sb = new StringBuilder();
            int n = Math.min(3, results.size());
            for (int i = 0; i < n; i++) {
                JsonNode r = results.get(i);
                sb.append("**").append(r.path("title").asText("")).append("**\n");
                sb.append(r.path("content").asText("")).append("\n");
                sb.append(r.path("url").asText("")).append("\n\n");
            }
            return sb.toString().trim();
        }
    }
}
