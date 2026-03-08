package com.braingods.cva;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CVA Brain – supports Anthropic, Google Gemini, and OpenRouter (free Llama).
 * Provider + key are stored in SharedPreferences via MainActivity.
 */
public class BrainAgent {

    private static final String TAG = "BrainAgent";

    // ── Provider constants ────────────────────────────────────────────────────
    public static final String PROVIDER_ANTHROPIC   = "anthropic";
    public static final String PROVIDER_GEMINI      = "gemini";
    public static final String PROVIDER_OPENROUTER  = "openrouter";

    // Endpoints
    private static final String URL_ANTHROPIC  = "https://api.anthropic.com/v1/messages";
    private static final String URL_GEMINI     = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";
    private static final String URL_OPENROUTER = "https://openrouter.ai/api/v1/chat/completions";

    // Models
    private static final String MODEL_ANTHROPIC  = "claude-haiku-4-5-20251001";
    private static final String MODEL_OPENROUTER = "meta-llama/llama-3.3-70b-instruct:free";

    private static final int MAX_TOKENS = 1024;

    private static final String SYSTEM_PROMPT =
            "You are CVA (Cognitive Virtual Agent), an advanced personal AI assistant " +
                    "embedded in a keyboard app on the user's Android device. " +
                    "You have access to terminal output and device context. " +
                    "Be concise, practical, and decisive. " +
                    "When given terminal output to analyse, identify issues and suggest fixes directly.";

    // ── State ─────────────────────────────────────────────────────────────────
    private String  provider;
    private String  apiKey;
    private final Context context;
    private final List<JSONObject> history = new ArrayList<>();
    private String memoryContext = "";

    // ── Constructor ───────────────────────────────────────────────────────────

    public BrainAgent(String provider, String apiKey, Context context) {
        this.provider = provider != null ? provider : PROVIDER_ANTHROPIC;
        this.apiKey   = apiKey;
        this.context  = context;
        loadMemory();
    }

    public void setProvider(String provider) { this.provider = provider; }
    public void setApiKey(String key)        { this.apiKey   = key; }
    public String getProvider()              { return provider; }

    // ── Main chat entry point (call off main thread) ──────────────────────────

    public String chat(String userMessage) {
        if (apiKey == null || apiKey.isBlank()) {
            return "No API key set. Open CVA Settings and enter your key.";
        }
        try {
            switch (provider) {
                case PROVIDER_GEMINI:     return chatGemini(userMessage);
                case PROVIDER_OPENROUTER: return chatOpenRouter(userMessage);
                default:                  return chatAnthropic(userMessage);
            }
        } catch (Exception e) {
            Log.e(TAG, "chat() failed", e);
            return "Error: " + e.getMessage();
        }
    }

    // =========================================================================
    // Anthropic
    // =========================================================================

    private String chatAnthropic(String userMessage) throws Exception {
        addUserToHistory(userMessage);

        JSONObject body = new JSONObject();
        body.put("model", MODEL_ANTHROPIC);
        body.put("max_tokens", MAX_TOKENS);
        body.put("system", buildSystemPrompt());

        JSONArray msgs = new JSONArray();
        for (JSONObject m : historyWindow()) msgs.put(m);
        body.put("messages", msgs);

        JSONObject resp = post(URL_ANTHROPIC, body, conn -> {
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            conn.setRequestProperty("Content-Type", "application/json");
        });

        String reply = resp.getJSONArray("content").getJSONObject(0).getString("text");
        addAssistantToHistory(reply);
        maybeSaveMemory(reply);
        return reply;
    }

    // =========================================================================
    // Google Gemini
    // =========================================================================

    private String chatGemini(String userMessage) throws Exception {
        addUserToHistory(userMessage);

        JSONArray contents = new JSONArray();

        // System message as first user turn
        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "user");
        JSONArray sysParts = new JSONArray();
        sysParts.put(new JSONObject().put("text", buildSystemPrompt()));
        sysMsg.put("parts", sysParts);
        contents.put(sysMsg);

        // Model ack
        JSONObject sysAck = new JSONObject();
        sysAck.put("role", "model");
        JSONArray ackParts = new JSONArray();
        ackParts.put(new JSONObject().put("text", "Understood. I am CVA, ready to assist."));
        sysAck.put("parts", ackParts);
        contents.put(sysAck);

        // Conversation history
        for (JSONObject m : historyWindow()) {
            String role = m.getString("role").equals("assistant") ? "model" : "user";
            JSONObject turn = new JSONObject();
            turn.put("role", role);
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("text", m.getString("content")));
            turn.put("parts", parts);
            contents.put(turn);
        }

        JSONObject genCfg = new JSONObject();
        genCfg.put("maxOutputTokens", MAX_TOKENS);

        JSONObject body = new JSONObject();
        body.put("contents", contents);
        body.put("generationConfig", genCfg);

        String urlStr = URL_GEMINI + "?key=" + apiKey;
        JSONObject resp = post(urlStr, body, conn ->
                conn.setRequestProperty("Content-Type", "application/json"));

        String reply = resp
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text");

        addAssistantToHistory(reply);
        maybeSaveMemory(reply);
        return reply;
    }

    // =========================================================================
    // OpenRouter  (OpenAI-compatible — free Llama 3.3 70B)
    // =========================================================================

    private String chatOpenRouter(String userMessage) throws Exception {
        addUserToHistory(userMessage);

        JSONArray msgs = new JSONArray();

        // System message
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", buildSystemPrompt());
        msgs.put(sys);

        for (JSONObject m : historyWindow()) msgs.put(m);

        JSONObject body = new JSONObject();
        body.put("model", MODEL_OPENROUTER);
        body.put("max_tokens", MAX_TOKENS);
        body.put("messages", msgs);

        JSONObject resp = post(URL_OPENROUTER, body, conn -> {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("HTTP-Referer", "https://braingods.cva");
            conn.setRequestProperty("X-Title", "CVA Keyboard");
        });

        String reply = resp
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");

        addAssistantToHistory(reply);
        maybeSaveMemory(reply);
        return reply;
    }

    // =========================================================================
    // Shared HTTP POST
    // =========================================================================

    interface ConnSetup { void setup(HttpURLConnection c) throws Exception; }

    private JSONObject post(String urlStr, JSONObject body, ConnSetup setup) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        setup.setup(conn);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                code == 200 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }

        if (code != 200) throw new Exception("HTTP " + code + ": " + sb);
        return new JSONObject(sb.toString());
    }

    // =========================================================================
    // History helpers
    // =========================================================================

    private void addUserToHistory(String msg) throws Exception {
        JSONObject o = new JSONObject();
        o.put("role", "user");
        o.put("content", msg);
        history.add(o);
    }

    private void addAssistantToHistory(String msg) throws Exception {
        JSONObject o = new JSONObject();
        o.put("role", "assistant");
        o.put("content", msg);
        history.add(o);
    }

    private List<JSONObject> historyWindow() {
        return history.subList(Math.max(0, history.size() - 20), history.size());
    }

    private String buildSystemPrompt() {
        return memoryContext.isEmpty() ? SYSTEM_PROMPT
                : SYSTEM_PROMPT + "\n\nMemory:\n" + memoryContext;
    }

    // =========================================================================
    // Memory
    // =========================================================================

    private void loadMemory() {
        try { memoryContext = CvakiStorage.load(context, "brain_memory"); }
        catch (Exception e) { memoryContext = ""; }
    }

    private void maybeSaveMemory(String reply) {
        if (!reply.contains("[MEMORY]")) return;
        try {
            String updated = memoryContext + "\n" + reply.replace("[MEMORY]", "").trim();
            CvakiStorage.save(context, "brain_memory", updated);
            memoryContext = updated;
        } catch (Exception e) {
            Log.e(TAG, "saveMemory failed", e);
        }
    }

    public void clearHistory() { history.clear(); }

    public void clearMemory() {
        memoryContext = "";
        CvakiStorage.delete(context, "brain_memory");
    }
}