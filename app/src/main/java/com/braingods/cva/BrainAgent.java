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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * CVA Brain – Anthropic, Google Gemini, OpenRouter, Groq.
 *
 * NEW FEATURES:
 *  1. LIVE MODE  — before executing any shell command the agent opens the live
 *     terminal panel, types the command letter-by-letter so the user can watch,
 *     submits it, captures output, then closes the panel automatically.
 *  2. BLINKING LOGO — AgentCallback carries isRunning + scrollToEnd flags so
 *     the UI can blink the CVA logo badge while the agent is busy and stop it
 *     when the final answer arrives.
 *  3. AUTO-SCROLL — every step fires scrollToEnd=true so AgentChatView always
 *     jumps to the newest bubble.
 */
public class BrainAgent {

    private static final String TAG = "BrainAgent";

    public static final String PROVIDER_ANTHROPIC  = "anthropic";
    public static final String PROVIDER_GEMINI     = "gemini";
    public static final String PROVIDER_OPENROUTER = "openrouter";
    public static final String PROVIDER_GROQ       = "groq";

    private static final String URL_ANTHROPIC   = "https://api.anthropic.com/v1/messages";
    private static final String URL_GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String URL_GROQ        = "https://api.groq.com/openai/v1/chat/completions";
    private static final String URL_OPENROUTER  = "https://openrouter.ai/api/v1/chat/completions";

    private static final String MODEL_ANTHROPIC = "claude-haiku-4-5-20251001";

    private static final String[] GROQ_MODELS = {
            "llama-3.3-70b-versatile",
            "llama3-70b-8192",
            "llama3-8b-8192",
            "gemma2-9b-it",
            "mixtral-8x7b-32768",
    };

    private static final String[] GEMINI_MODELS = {
            "gemini-2.0-flash-lite",
            "gemini-1.5-flash-8b",
            "gemini-1.5-flash",
            "gemini-2.0-flash",
    };

    private static final String[] FREE_MODELS = {
            "openrouter/auto",
            "google/gemini-2.0-flash-exp:free",
            "meta-llama/llama-3.3-70b-instruct:free",
            "mistralai/mistral-7b-instruct:free",
            "google/gemma-3-12b-it:free",
            "deepseek/deepseek-r1:free",
    };

    private static final int MAX_TOKENS      = 1024;
    private static final int MAX_LOOPS       = 6;
    /** Milliseconds between each character typed in live mode */
    private static final int LIVE_CHAR_DELAY = 18;

    // ── System prompt ─────────────────────────────────────────────────────────
    private static final String SYSTEM_PROMPT =
            "You are CVA (Cognitive Virtual Agent), an advanced AI assistant embedded in an Android keyboard.\n\n" +
                    "AGENTIC MODE — You can execute shell commands on the device.\n" +
                    "When the user asks to do something that requires the device (camera, files, search, settings, network, etc):\n" +
                    "  1. Respond with a JSON block like this (and NOTHING else if you want to run a command):\n" +
                    "     {\"cmd\":\"am start -a android.media.action.IMAGE_CAPTURE\"}\n" +
                    "  2. You will receive the shell output back automatically.\n" +
                    "  3. Keep issuing commands until the task is fully done.\n" +
                    "  4. When the task is complete, respond with normal text (no JSON cmd block).\n\n" +
                    "COMMAND EXAMPLES:\n" +
                    "  Take photo:    {\"cmd\":\"am start -a android.media.action.IMAGE_CAPTURE\"}\n" +
                    "  Web search:    {\"cmd\":\"am start -a android.intent.action.VIEW -d \\\"https://www.google.com/search?q=YOUR+QUERY\\\"\"}\n" +
                    "  Open settings: {\"cmd\":\"am start -a android.settings.SETTINGS\"}\n" +
                    "  List files:    {\"cmd\":\"ls /sdcard/\"}\n" +
                    "  Get IP:        {\"cmd\":\"ip addr show\"}\n" +
                    "  Battery:       {\"cmd\":\"dumpsys battery\"}\n" +
                    "  WiFi info:     {\"cmd\":\"dumpsys wifi | grep mWifiInfo\"}\n" +
                    "  Print 1-1000:  {\"cmd\":\"for i in $(seq 1 1000); do echo $i; done\"}\n\n" +
                    "RULES:\n" +
                    "  - If a command fails, read the error and try a corrected command automatically.\n" +
                    "  - Never ask the user to run commands manually — you run them.\n" +
                    "  - For normal conversation reply as plain text.\n" +
                    "  - Be concise. When task is done, summarise what happened in 1-2 sentences.";

    // ── Fields ────────────────────────────────────────────────────────────────
    private String                 provider;
    private String                 apiKey;
    private final Context          context;
    private final List<JSONObject> history = new ArrayList<>();
    private String                 memoryContext = "";

    private TerminalManager        terminalManager;
    private AgentCallback          agentCallback;
    private LiveModeController     liveModeController;

    // ═════════════════════════════════════════════════════════════════════════
    // Public interfaces
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Extended agent callback used by AgentChatView.
     *
     * @param statusMsg   Text to show in the agent bubble.
     * @param isRunning   TRUE  → blink the CVA logo badge.
     *                    FALSE → stop blinking, finalise the answer bubble.
     * @param scrollToEnd TRUE  → auto-scroll chat to the newest message.
     */
    public interface AgentCallback {
        void onAgentStep(String statusMsg, boolean isRunning, boolean scrollToEnd);
    }

    /**
     * Controls the live-typing terminal panel in AgentChatView (or any custom
     * view).  Implement and pass via {@link #setLiveModeController}.
     */
    public interface LiveModeController {
        /** Show (true) or hide (false) the live terminal panel. */
        void setLiveMode(boolean enabled);
        /** Append one character to the live display (called per-char with a delay). */
        void typeLiveChar(char c);
        /** Execute what was typed — equivalent to pressing Enter. */
        void submitLiveInput();
        /**
         * Show the command result in the live panel.
         * @param output  The captured shell output (trimmed).
         * @param success TRUE → green output, FALSE → red output.
         */
        void showLiveResult(String output, boolean success);
    }

    // ── Setters ───────────────────────────────────────────────────────────────
    public void setTerminalManager(TerminalManager tm)      { this.terminalManager   = tm; }
    public void setAgentCallback(AgentCallback cb)          { this.agentCallback     = cb; }
    public void setLiveModeController(LiveModeController c) { this.liveModeController = c; }

    // ── Constructor ───────────────────────────────────────────────────────────
    public BrainAgent(String provider, String apiKey, Context context) {
        this.provider = provider != null ? provider : PROVIDER_ANTHROPIC;
        this.apiKey   = apiKey;
        this.context  = context;
        loadMemory();
    }

    public void setProvider(String p) {
        this.provider = p;
        String k = resolveKey();
        if (k != null && !k.isEmpty()) this.apiKey = k;
    }
    public void setApiKey(String key) { this.apiKey = key; }
    public String getProvider()       { return provider; }

    // ── Key resolution ────────────────────────────────────────────────────────
    private String resolveKey() {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences("cva_prefs", Context.MODE_PRIVATE);
        switch (provider) {
            case PROVIDER_GEMINI:     return prefs.getString("key_gemini",     "");
            case PROVIDER_OPENROUTER: return prefs.getString("key_openrouter", "");
            case PROVIDER_GROQ:       return prefs.getString("key_groq",       "");
            default:                  return prefs.getString("key_anthropic",  "");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Main chat entry point  ← MUST be called off the main thread
    // ═════════════════════════════════════════════════════════════════════════
    public String chat(String userMessage) {
        // Sync provider + key from prefs every call so changes in Settings take effect immediately
        android.content.SharedPreferences prefs =
                context.getSharedPreferences("cva_prefs", Context.MODE_PRIVATE);
        String saved = prefs.getString("provider", provider);
        if (saved != null && !saved.isEmpty()) this.provider = saved;
        String resolvedKey = resolveKey();
        if (resolvedKey != null && !resolvedKey.isEmpty()) this.apiKey = resolvedKey;

        if (apiKey == null || apiKey.isBlank()) {
            return "⚠ No API key set for provider: " + provider +
                    ". Open CVA Settings, enter your key and press SAVE.";
        }

        try {
            // ── Signal: agent is starting — show blinking logo ────────────────
            step("⚙ CVA thinking…", true);

            String reply = callAI(userMessage);
            if (reply == null) reply = "CVA: No response received. Try again.";

            // ── Agentic loop ──────────────────────────────────────────────────
            int loops = 0;
            while (loops < MAX_LOOPS) {
                String cmd = extractCommand(reply);
                if (cmd == null) break;   // plain-text reply → task done

                loops++;

                // ── STEP 1: open live terminal panel ──────────────────────────
                step("⌨ Typing command…", true);
                setLiveMode(true);

                // ── STEP 2: type command letter-by-letter ─────────────────────
                step("⌨ " + cmd, true);
                typeLive(cmd);

                // ── STEP 3: press Enter ───────────────────────────────────────
                step("▶ Executing…", true);
                submitLive();

                // ── STEP 4: capture output via TerminalManager ────────────────
                String output = runShellCommand(cmd);

                // Detect success vs error (exit marker or known error keywords)
                boolean success = !output.toLowerCase().contains("error")
                        && !output.toLowerCase().contains("not found")
                        && !output.toLowerCase().contains("permission denied")
                        && !output.toLowerCase().contains("exception")
                        && !output.startsWith("[Error");

                // Show coloured result in live panel
                showLiveResult(output, success);
                sleep(600);  // let user read the result before panel closes

                // Update thinking bubble with coloured status
                if (success) {
                    step("✓ Done: " + truncate(output, 120), true);
                } else {
                    step("✗ Error: " + truncate(output, 120), true);
                }

                // ── STEP 5: close live terminal panel ─────────────────────────
                setLiveMode(false);

                // ── STEP 6: feed output back to AI ────────────────────────────
                step("⚙ CVA analysing output…", true);
                String feedback = "[TERMINAL OUTPUT for cmd: " + cmd + "]\n" + output +
                        "\n\nContinue the task or summarise if done.";
                reply = callAI(feedback);
                if (reply == null) reply = "CVA: No response. Stopping.";
            }

            if (loops >= MAX_LOOPS) reply += "\n\n[CVA: reached max steps — stopping.]";

            // ── Final answer — stop blinking logo, scroll to bottom ───────────
            if (agentCallback != null) agentCallback.onAgentStep(reply, false, true);
            return reply;

        } catch (Exception e) {
            Log.e(TAG, "chat() failed", e);
            String err = "Error: " + e.getMessage();
            if (agentCallback != null) agentCallback.onAgentStep(err, false, true);
            return err;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Live-mode helpers
    // ═════════════════════════════════════════════════════════════════════════

    private void setLiveMode(boolean on) {
        if (liveModeController != null) liveModeController.setLiveMode(on);
        sleep(on ? 250 : 180);
    }

    private void showLiveResult(String output, boolean success) {
        if (liveModeController != null) liveModeController.showLiveResult(output, success);
    }

    /** Types every character of {@code cmd} with a small delay between each. */
    private void typeLive(String cmd) {
        if (liveModeController == null) return;
        for (char c : cmd.toCharArray()) {
            liveModeController.typeLiveChar(c);
            sleep(LIVE_CHAR_DELAY);
        }
        sleep(350);   // brief pause so user can read the full command before it runs
    }

    private void submitLive() {
        if (liveModeController != null) liveModeController.submitLiveInput();
        sleep(200);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ── Shorthand step notification ───────────────────────────────────────────
    private void step(String msg, boolean running) {
        if (agentCallback != null) agentCallback.onAgentStep(msg, running, true);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Command extraction  {"cmd":"..."}
    // ═════════════════════════════════════════════════════════════════════════
    private String extractCommand(String reply) {
        if (reply == null) return null;
        try {
            int s = reply.indexOf('{'), e = reply.lastIndexOf('}');
            if (s < 0 || e < 0 || e <= s) return null;
            JSONObject obj = new JSONObject(reply.substring(s, e + 1));
            if (obj.has("cmd")) return obj.getString("cmd").trim();
        } catch (Exception ignored) {}
        return null;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Shell execution (sentinel pattern — waits up to 15 s for output)
    // ═════════════════════════════════════════════════════════════════════════
    private String runShellCommand(String cmd) {
        if (terminalManager == null) return "[Terminal not available]";
        try {
            StringBuilder  out      = new StringBuilder();
            CountDownLatch latch    = new CountDownLatch(1);
            String         sentinel = "##CVA_DONE_" + System.currentTimeMillis() + "##";

            TerminalManager.OutputCallback cb = chunk -> {
                out.append(chunk);
                if (out.toString().contains(sentinel)) latch.countDown();
            };

            terminalManager.setAgentOutputCallback(cb);
            terminalManager.sendInput(cmd + " 2>&1; echo " + sentinel + "\n");
            latch.await(15, TimeUnit.SECONDS);
            terminalManager.setAgentOutputCallback(null);

            String result = out.toString();
            int si = result.indexOf(sentinel);
            if (si >= 0) result = result.substring(0, si);

            // Strip echoed command line (usually the first line)
            String[] lines = result.split("\n");
            StringBuilder clean = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i == 0 && lines[i].trim().equals(cmd.trim())) continue;
                clean.append(lines[i]).append("\n");
            }
            return clean.toString().trim();

        } catch (Exception e) {
            return "[Error running command: " + e.getMessage() + "]";
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // AI routing
    // ═════════════════════════════════════════════════════════════════════════
    private String callAI(String msg) throws Exception {
        switch (provider) {
            case PROVIDER_GEMINI:     return chatGemini(msg);
            case PROVIDER_OPENROUTER: return chatOpenRouter(msg);
            case PROVIDER_GROQ:       return chatGroq(msg);
            default:                  return chatAnthropic(msg);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Anthropic
    // ─────────────────────────────────────────────────────────────────────────
    private String chatAnthropic(String userMessage) throws Exception {
        addUser(userMessage);
        JSONObject body = new JSONObject();
        body.put("model", MODEL_ANTHROPIC);
        body.put("max_tokens", MAX_TOKENS);
        body.put("system", buildSystem());
        JSONArray msgs = new JSONArray();
        for (JSONObject m : window()) msgs.put(m);
        body.put("messages", msgs);

        JSONObject resp = post(URL_ANTHROPIC, body, conn -> {
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            conn.setRequestProperty("Content-Type", "application/json");
        });

        JSONArray content = resp.optJSONArray("content");
        if (content == null || content.length() == 0) return "CVA: Empty response from Anthropic.";
        String reply = content.getJSONObject(0).optString("text", "").trim();
        if (reply.isEmpty()) return "CVA: Blank text from Anthropic.";
        addAssistant(reply); maybeSaveMemory(reply);
        return reply;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gemini
    // ─────────────────────────────────────────────────────────────────────────
    private String chatGemini(String userMessage) throws Exception {
        addUser(userMessage);

        JSONArray contents = new JSONArray();
        // system turn
        JSONObject sysMsg = new JSONObject(); sysMsg.put("role", "user");
        JSONArray sp = new JSONArray(); sp.put(new JSONObject().put("text", buildSystem()));
        sysMsg.put("parts", sp); contents.put(sysMsg);
        JSONObject ack = new JSONObject(); ack.put("role", "model");
        JSONArray ap = new JSONArray(); ap.put(new JSONObject().put("text", "Understood. I am CVA."));
        ack.put("parts", ap); contents.put(ack);
        // history
        for (JSONObject m : window()) {
            String role = m.getString("role").equals("assistant") ? "model" : "user";
            JSONObject t = new JSONObject(); t.put("role", role);
            JSONArray p = new JSONArray(); p.put(new JSONObject().put("text", m.getString("content")));
            t.put("parts", p); contents.put(t);
        }
        JSONObject genCfg = new JSONObject(); genCfg.put("maxOutputTokens", MAX_TOKENS);
        JSONObject body = new JSONObject(); body.put("contents", contents); body.put("generationConfig", genCfg);

        Exception last = null;
        for (String model : GEMINI_MODELS) {
            try {
                String url = URL_GEMINI_BASE + model + ":generateContent?key=" + apiKey;
                JSONObject resp = post(url, body, c -> c.setRequestProperty("Content-Type", "application/json"));
                JSONObject cand = resp.getJSONArray("candidates").getJSONObject(0);
                if (!cand.has("content") || cand.isNull("content")) {
                    last = new Exception("Blocked: " + cand.optString("finishReason")); continue;
                }
                JSONArray parts = cand.getJSONObject("content").optJSONArray("parts");
                if (parts == null || parts.length() == 0) { last = new Exception("Empty parts"); continue; }
                String reply = parts.getJSONObject(0).optString("text", "").trim();
                if (reply.isEmpty()) { last = new Exception("Blank text"); continue; }
                addAssistant(reply); maybeSaveMemory(reply);
                Log.d(TAG, "Gemini success: " + model);
                return reply;
            } catch (Exception e) {
                last = e;
                if (e.getMessage() != null && (e.getMessage().contains("API_KEY_INVALID") || e.getMessage().contains("403"))) throw e;
            }
        }
        throw new Exception("All Gemini models exhausted. " + (last != null ? last.getMessage() : ""));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OpenRouter
    // ─────────────────────────────────────────────────────────────────────────
    private String chatOpenRouter(String userMessage) throws Exception {
        addUser(userMessage);
        JSONArray msgs = new JSONArray();
        JSONObject sys = new JSONObject(); sys.put("role", "system"); sys.put("content", buildSystem()); msgs.put(sys);
        for (JSONObject m : window()) msgs.put(m);

        Exception last = null;
        for (String model : FREE_MODELS) {
            try {
                JSONObject body = new JSONObject();
                body.put("model", model); body.put("max_tokens", MAX_TOKENS); body.put("messages", msgs);
                JSONObject resp = post(URL_OPENROUTER, body, conn -> {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("HTTP-Referer", "https://braingods.cva");
                    conn.setRequestProperty("X-Title", "CVA Keyboard");
                });
                String reply = resp.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content", "").trim();
                if (reply.isEmpty()) { last = new Exception("Empty content from " + model); continue; }
                addAssistant(reply); maybeSaveMemory(reply);
                Log.d(TAG, "OpenRouter success: " + model);
                return reply;
            } catch (Exception e) {
                last = e;
                if (e.getMessage() != null && e.getMessage().contains("HTTP 401")) throw e;
            }
        }
        throw new Exception("All free models unavailable.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Groq
    // ─────────────────────────────────────────────────────────────────────────
    private String chatGroq(String userMessage) throws Exception {
        addUser(userMessage);
        JSONArray msgs = new JSONArray();
        JSONObject sys = new JSONObject(); sys.put("role", "system"); sys.put("content", buildSystem()); msgs.put(sys);
        for (JSONObject m : window()) msgs.put(m);

        Exception last = null;
        for (String model : GROQ_MODELS) {
            try {
                JSONObject body = new JSONObject();
                body.put("model", model); body.put("max_tokens", MAX_TOKENS); body.put("messages", msgs);
                JSONObject resp = post(URL_GROQ, body, conn -> {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                    conn.setRequestProperty("Content-Type", "application/json");
                });
                String reply = resp.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content", "").trim();
                if (reply.isEmpty()) { last = new Exception("Empty content from " + model); continue; }
                addAssistant(reply); maybeSaveMemory(reply);
                Log.d(TAG, "Groq success: " + model);
                return reply;
            } catch (Exception e) {
                last = e;
                if (e.getMessage() != null && (e.getMessage().contains("HTTP 401") || e.getMessage().contains("invalid_api_key"))) throw e;
            }
        }
        throw new Exception("All Groq models unavailable. " + (last != null ? last.getMessage() : ""));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HTTP POST
    // ═════════════════════════════════════════════════════════════════════════
    interface ConnSetup { void setup(HttpURLConnection c) throws Exception; }

    private JSONObject post(String urlStr, JSONObject body, ConnSetup setup) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
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

    // ═════════════════════════════════════════════════════════════════════════
    // History helpers
    // ═════════════════════════════════════════════════════════════════════════
    private void addUser(String msg) throws Exception {
        JSONObject o = new JSONObject(); o.put("role", "user"); o.put("content", msg); history.add(o);
    }
    private void addAssistant(String msg) throws Exception {
        JSONObject o = new JSONObject(); o.put("role", "assistant"); o.put("content", msg); history.add(o);
    }
    private List<JSONObject> window() {
        return history.subList(Math.max(0, history.size() - 20), history.size());
    }
    private String buildSystem() {
        return memoryContext.isEmpty() ? SYSTEM_PROMPT : SYSTEM_PROMPT + "\n\nMemory:\n" + memoryContext;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Memory
    // ═════════════════════════════════════════════════════════════════════════
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
        } catch (Exception e) { Log.e(TAG, "saveMemory failed", e); }
    }
    public void clearHistory() { history.clear(); }
    public void clearMemory()  { memoryContext = ""; CvakiStorage.delete(context, "brain_memory"); }
}