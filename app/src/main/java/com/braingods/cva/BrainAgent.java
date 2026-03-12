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
 * ARCHITECTURE — Blind-man + Guide-dog:
 *   CVA Brain is the "blind man": intelligent but cannot see the screen.
 *   It tries to accomplish tasks via shell commands (terminal).
 *
 *   When terminal commands fail {@value BRAIN_MAX_FAILS} times in a row,
 *   the Brain escalates to the "guide dog" (SmartOverlayService / Parasite)
 *   via the {@link ParasiteEscalation} callback.
 *
 * NEW APIs:
 *   - {@link #chatOnce(String)}        Single AI call without the agentic loop.
 *                                      Used by TaskAllocationActivity for task breakdown.
 *   - {@link #setParasiteEscalation}   Register the escalation handler.
 *   - {@link ParasiteEscalation}       Callback fired after BRAIN_MAX_FAILS failures.
 */
public class BrainAgent {

    private static final String TAG = "BrainAgent";

    // ── Provider constants ────────────────────────────────────────────────────
    public static final String PROVIDER_ANTHROPIC  = "anthropic";
    public static final String PROVIDER_GEMINI     = "gemini";
    public static final String PROVIDER_OPENROUTER = "openrouter";
    public static final String PROVIDER_GROQ       = "groq";

    // ── Endpoint URLs ─────────────────────────────────────────────────────────
    private static final String URL_ANTHROPIC   = "https://api.anthropic.com/v1/messages";
    private static final String URL_GEMINI_BASE = "https://generativelanguage.googleapis.com/v1/models/";
    private static final String URL_GROQ        = "https://api.groq.com/openai/v1/chat/completions";
    private static final String URL_OPENROUTER  = "https://openrouter.ai/api/v1/chat/completions";

    // ── Models ────────────────────────────────────────────────────────────────
    private static final String MODEL_ANTHROPIC = "claude-haiku-4-5-20251001";

    private static final String[] GROQ_MODELS = {
            "llama-3.3-70b-versatile",
            "llama3-70b-8192",
            "llama3-8b-8192",
            "gemma2-9b-it",
            "llama-3.1-8b-instant",
    };

    private static final String[] GEMINI_MODELS = {
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
    };

    private static final String[] FREE_MODELS = {
            "openrouter/auto",
            "google/gemini-2.0-flash-exp:free",
            "meta-llama/llama-3.3-70b-instruct:free",
            "mistralai/mistral-7b-instruct:free",
            "google/gemma-3-12b-it:free",
            "deepseek/deepseek-r1:free",
    };

    // ── Tuning constants ──────────────────────────────────────────────────────
    private static final int MAX_TOKENS      = 1024;
    private static final int MAX_LOOPS       = 6;
    private static final int LIVE_CHAR_DELAY = 18;

    /**
     * How many consecutive terminal failures before the Brain gives up and
     * asks the Parasite (vision agent) to take over.
     */
    private static final int BRAIN_MAX_FAILS = 3;

    // ── Marker returned to callers when escalation fires ──────────────────────
    public static final String ESCALATED_MARKER = "[ESCALATED_TO_PARASITE]";

    // ── System prompt ─────────────────────────────────────────────────────────
    private static final String SYSTEM_PROMPT =
            "You are CVA (Cognitive Virtual Agent), an advanced AI assistant embedded in an Android keyboard.\n\n" +
                    "AGENTIC MODE — You execute shell commands on this rooted Android device.\n" +
                    "When the user asks you to open an app, take a photo, search, adjust settings, or do ANYTHING on the device:\n" +
                    "  1. Respond with a JSON block like this (and NOTHING ELSE — no explanation):\n" +
                    "     {\"cmd\":\"am start -a android.media.action.IMAGE_CAPTURE\"}\n" +
                    "  2. You will receive the shell output back automatically.\n" +
                    "  3. Keep issuing commands until the task is fully done.\n" +
                    "  4. Only reply with plain text AFTER the task is complete.\n\n" +
                    "COMMAND EXAMPLES:\n" +
                    "  Camera (front): {\"cmd\":\"am start -a android.media.action.STILL_IMAGE_CAMERA\"}\n" +
                    "  Camera (intent):{\"cmd\":\"am start -a android.media.action.IMAGE_CAPTURE\"}\n" +
                    "  Camera (pkg):   {\"cmd\":\"am start -n com.android.camera2/com.android.camera.CameraActivity\"}\n" +
                    "  Web search:     {\"cmd\":\"am start -a android.intent.action.VIEW -d \\\"https://www.google.com/search?q=YOUR+QUERY\\\"\"}\n" +
                    "  Open settings:  {\"cmd\":\"am start -a android.settings.SETTINGS\"}\n" +
                    "  List files:     {\"cmd\":\"ls /sdcard/\"}\n" +
                    "  Battery:        {\"cmd\":\"dumpsys battery\"}\n" +
                    "  WiFi info:      {\"cmd\":\"dumpsys wifi | grep mWifiInfo\"}\n\n" +
                    "CRITICAL RULES — NEVER BREAK THESE:\n" +
                    "  ❌ NEVER say 'I don't have permissions', 'I can't', 'I'm unable to', or give up.\n" +
                    "  ❌ NEVER reply with plain text when a device action is needed.\n" +
                    "  ✅ If a command fails, ALWAYS try at least 3 different commands before giving up.\n" +
                    "  ✅ Try different package names, different intents, different approaches.\n" +
                    "  ✅ If ALL terminal approaches fail after trying multiple commands, output EXACTLY:\n" +
                    "     {\"needs_vision\":true,\"reason\":\"<what you tried>\"}\n" +
                    "     This hands off to the visual AI which can tap the screen directly.\n" +
                    "  ✅ For normal conversation (no device action needed) reply as plain text.\n" +
                    "  ✅ Be concise. When task is done, summarise what happened in 1-2 sentences.";

    // ── Fields ────────────────────────────────────────────────────────────────
    private String                 provider;
    private String                 apiKey;
    private final Context          context;
    private final List<JSONObject> history = new ArrayList<>();
    private String                 memoryContext = "";

    private TerminalManager        terminalManager;
    private AgentCallback          agentCallback;
    private LiveModeController     liveModeController;
    private ParasiteEscalation     parasiteEscalation;

    /** Consecutive terminal-command failures in the current chat() call. */
    private int consecutiveFailures = 0;

    private volatile boolean liveEnabled = false;

    public void setLiveEnabled(boolean enabled) {
        this.liveEnabled = enabled;
        if (!enabled && liveModeController != null) liveModeController.setLiveMode(false);
    }
    public boolean isLiveEnabled() { return liveEnabled; }

    // ═════════════════════════════════════════════════════════════════════════
    // Public interfaces
    // ═════════════════════════════════════════════════════════════════════════

    public interface AgentCallback {
        void onAgentStep(String statusMsg, boolean isRunning, boolean scrollToEnd);
    }

    public interface LiveModeController {
        void setLiveMode(boolean enabled);
        void typeLiveChar(char c);
        void submitLiveInput();
        void showLiveResult(String output, boolean success);
    }

    /**
     * Fired when consecutive terminal-command failures exceed {@value BRAIN_MAX_FAILS}.
     *
     * <p>Implementors (typically {@link TaskAllocationActivity} or
     * {@link SmartOverlayService}) should launch a visual scan of the screen
     * to complete the task in place of the terminal approach.
     *
     * @param task      The original user request still needing completion.
     * @param lastError The most recent shell error output.
     * @param failCount How many consecutive failures occurred.
     */
    public interface ParasiteEscalation {
        void onEscalate(String task, String lastError, int failCount);
    }

    // ── Setters ───────────────────────────────────────────────────────────────
    public void setTerminalManager(TerminalManager tm)         { this.terminalManager    = tm; }
    public void setAgentCallback(AgentCallback cb)             { this.agentCallback      = cb; }
    public void setLiveModeController(LiveModeController c)    { this.liveModeController = c; }
    public void setParasiteEscalation(ParasiteEscalation pe)   { this.parasiteEscalation = pe; }

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
    // chatOnce() — single AI call, NO agentic loop.
    // Used by TaskAllocationActivity for task breakdown, planning, and analysis.
    // MUST be called off the main thread.
    // ═════════════════════════════════════════════════════════════════════════
    public String chatOnce(String userMessage) {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences("cva_prefs", Context.MODE_PRIVATE);
        String saved = prefs.getString("provider", provider);
        if (saved != null && !saved.isEmpty()) this.provider = saved;
        String resolvedKey = resolveKey();
        if (resolvedKey != null && !resolvedKey.isEmpty()) this.apiKey = resolvedKey;

        if (apiKey == null || apiKey.isBlank()) {
            return "[{\"subtask\":\"" + userMessage + "\"}]"; // minimal fallback
        }
        try {
            return callAI(userMessage);
        } catch (Exception e) {
            Log.e(TAG, "chatOnce failed", e);
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Main agentic chat entry point — MUST be called off the main thread
    // ═════════════════════════════════════════════════════════════════════════
    public String chat(String userMessage) {
        // Sync provider + key from prefs every call so settings changes take effect
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

        consecutiveFailures = 0; // reset per chat() call

        // ── No terminal → skip straight to parasite ───────────────────────────
        // There is zero point running the agentic loop if we have no shell.
        // Every command would return "[Error: Terminal not available]", waste
        // 3+ AI API calls, and hallucinate success. Escalate immediately.
        if (terminalManager == null && parasiteEscalation != null) {
            Log.d(TAG, "chat(): no terminal — escalating to parasite immediately");
            step("⚡ No terminal — handing off to visual agent…", true);
            parasiteEscalation.onEscalate(userMessage, "No terminal available", 0);
            if (agentCallback != null)
                agentCallback.onAgentStep(ESCALATED_MARKER, false, true);
            return ESCALATED_MARKER;
        }

        try {
            step("⚙ CVA thinking…", true);

            String reply = callAI(userMessage);
            if (reply == null) reply = "CVA: No response received. Try again.";

            // ── Agentic loop ──────────────────────────────────────────────────
            int loops = 0;
            while (loops < MAX_LOOPS) {
                String cmd = extractCommand(reply);

                // ── Check for visual hand-off signal ─────────────────────────
                // If the AI returned {"needs_vision":true,...} it is asking us
                // to escalate to the parasite immediately.
                if (cmd == null && isNeedsVision(reply)) {
                    String reason = extractNeedsVisionReason(reply);
                    step("🔀 Brain requests visual hand-off: " + reason, true);
                    Log.d(TAG, "needs_vision detected, escalating to parasite. Reason: " + reason);
                    if (parasiteEscalation != null) {
                        parasiteEscalation.onEscalate(userMessage, reason, -1);
                    }
                    if (agentCallback != null)
                        agentCallback.onAgentStep(ESCALATED_MARKER, false, true);
                    return ESCALATED_MARKER;
                }

                // ── Check for a give-up / refusal plain-text reply ────────────
                // The AI said "I can't / I don't have permission / I'm unable to"
                // instead of trying a command. Treat this as a hard failure and
                // escalate to the parasite immediately.
                if (cmd == null && isRefusalReply(reply)) {
                    consecutiveFailures++;
                    step("⚠ Brain gave up verbally (" + consecutiveFailures + "/" + BRAIN_MAX_FAILS
                            + "): " + truncate(reply, 80), true);
                    Log.w(TAG, "Brain refusal detected (loops=" + loops + "): " + reply);

                    if (consecutiveFailures >= BRAIN_MAX_FAILS || loops == 0) {
                        // Escalate on first refusal if it's the very first reply
                        // (the AI gave up without even trying once).
                        if (parasiteEscalation != null) {
                            step("🔀 Escalating to parasite (verbal refusal)…", true);
                            parasiteEscalation.onEscalate(userMessage, reply, consecutiveFailures);
                        }
                        if (agentCallback != null)
                            agentCallback.onAgentStep(ESCALATED_MARKER, false, true);
                        return ESCALATED_MARKER;
                    }

                    // Try to prompt the AI again more forcefully
                    String retry = "[SYSTEM: You MUST try a terminal command. Do NOT say you can't. " +
                            "Output {\"cmd\":\"...\"} with a different approach. " +
                            "If you have tried terminal and it truly fails, output {\"needs_vision\":true}.]\n" +
                            "Retry the task: " + userMessage;
                    reply = callAI(retry);
                    if (reply == null) reply = "";
                    loops++;
                    continue;
                }

                if (cmd == null) break; // genuine task-complete plain-text reply

                loops++;

                // ── Show in live terminal panel ───────────────────────────────
                step("⌨ Typing command…", true);
                if (liveEnabled) setLiveMode(true);
                step("⌨ " + cmd, true);
                if (liveEnabled) typeLive(cmd);
                step("▶ Executing…", true);
                if (liveEnabled) submitLive();

                // ── Run the command ───────────────────────────────────────────
                String output = runShellCommand(cmd);

                boolean success = !output.toLowerCase().contains("error")
                        && !output.toLowerCase().contains("not found")
                        && !output.toLowerCase().contains("permission denied")
                        && !output.toLowerCase().contains("exception")
                        && !output.startsWith("[Error");

                if (liveEnabled) {
                    showLiveResult(output, success);
                    sleep(600);
                }

                if (success) {
                    consecutiveFailures = 0;
                    step("✓ Done: " + truncate(output, 120), true);
                } else {
                    consecutiveFailures++;
                    step("✗ Error (" + consecutiveFailures + "/" + BRAIN_MAX_FAILS + "): "
                            + truncate(output, 100), true);

                    // ── ESCALATE TO PARASITE after BRAIN_MAX_FAILS failures ───
                    if (consecutiveFailures >= BRAIN_MAX_FAILS && parasiteEscalation != null) {
                        if (liveEnabled) setLiveMode(false);
                        step("🔀 Escalating to parasite after " + consecutiveFailures + " failures…", true);
                        parasiteEscalation.onEscalate(userMessage, output, consecutiveFailures);
                        if (agentCallback != null)
                            agentCallback.onAgentStep(ESCALATED_MARKER, false, true);
                        return ESCALATED_MARKER;
                    }
                }

                if (liveEnabled) setLiveMode(false);

                // Feed output back to AI
                step("⚙ CVA analysing output…", true);
                String feedback = "[TERMINAL OUTPUT for cmd: " + cmd + "]\n" + output +
                        "\n\nContinue the task or summarise if done.";
                reply = callAI(feedback);
                if (reply == null) reply = "CVA: No response. Stopping.";
            }

            if (loops >= MAX_LOOPS) reply += "\n\n[CVA: reached max steps — stopping.]";

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

    private void typeLive(String cmd) {
        if (liveModeController == null) return;
        for (char c : cmd.toCharArray()) {
            liveModeController.typeLiveChar(c);
            sleep(LIVE_CHAR_DELAY);
        }
        sleep(350);
    }

    private void submitLive() {
        if (liveModeController != null) liveModeController.submitLiveInput();
        sleep(200);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

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

    /**
     * Returns true when the AI replied with a plain-text give-up message
     * instead of a {"cmd":...} block.
     *
     * These are the phrases we saw in the log:
     *   "I don't have the necessary permissions"
     *   "I can't proceed"
     *   "I'm unable to"
     *   "I cannot"
     *   "I don't have access"
     *   "not possible for me"
     *   "I am not able"
     */
    private static boolean isRefusalReply(String reply) {
        if (reply == null || reply.contains("{\"cmd\"") || reply.contains("\"cmd\":")) return false;
        String lower = reply.toLowerCase();
        return lower.contains("i don't have the necessary")
                || lower.contains("i don't have permission")
                || lower.contains("i don't have access")
                || lower.contains("i can't proceed")
                || lower.contains("i can't open")
                || lower.contains("i can't access")
                || lower.contains("i'm unable to")
                || lower.contains("i am unable to")
                || lower.contains("i cannot open")
                || lower.contains("i cannot access")
                || lower.contains("i cannot directly")
                || lower.contains("i am not able")
                || lower.contains("not possible for me")
                || lower.contains("unable to directly")
                || lower.contains("don't have the ability")
                || lower.contains("i lack the ability")
                || lower.contains("requires physical")
                || lower.contains("as a text-based")
                || lower.contains("as an ai");
    }

    /**
     * Returns true when the AI returned {"needs_vision":true,...}.
     * This is the explicit hand-off signal we added to the system prompt.
     */
    private static boolean isNeedsVision(String reply) {
        if (reply == null) return false;
        try {
            int s = reply.indexOf('{'), e = reply.lastIndexOf('}');
            if (s < 0 || e < 0 || e <= s) return false;
            JSONObject obj = new JSONObject(reply.substring(s, e + 1));
            return obj.optBoolean("needs_vision", false);
        } catch (Exception ignored) {}
        return false;
    }

    private static String extractNeedsVisionReason(String reply) {
        if (reply == null) return "terminal exhausted";
        try {
            int s = reply.indexOf('{'), e = reply.lastIndexOf('}');
            if (s >= 0 && e > s) {
                JSONObject obj = new JSONObject(reply.substring(s, e + 1));
                return obj.optString("reason", "terminal exhausted");
            }
        } catch (Exception ignored) {}
        return "terminal exhausted";
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Shell execution (sentinel pattern — waits up to 15 s for output)
    // ═════════════════════════════════════════════════════════════════════════
    private String runShellCommand(String cmd) {
        if (terminalManager == null) return "[Error: Terminal not available]";
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
        JSONObject sysMsg = new JSONObject(); sysMsg.put("role", "user");
        JSONArray sp = new JSONArray(); sp.put(new JSONObject().put("text", buildSystem()));
        sysMsg.put("parts", sp); contents.put(sysMsg);
        JSONObject ack = new JSONObject(); ack.put("role", "model");
        JSONArray ap = new JSONArray(); ap.put(new JSONObject().put("text", "Understood. I am CVA."));
        ack.put("parts", ap); contents.put(ack);
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
                if (e.getMessage() != null && (e.getMessage().contains("API_KEY_INVALID") ||
                        e.getMessage().contains("403"))) throw e;
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
                if (e.getMessage() != null && (e.getMessage().contains("HTTP 401") ||
                        e.getMessage().contains("invalid_api_key"))) throw e;
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
        try { memoryContext = CvakiStorage.loadMain(context, "brain_memory"); }
        catch (Exception e) { memoryContext = ""; }
        if (memoryContext == null) memoryContext = "";
    }

    private void maybeSaveMemory(String reply) {
        if (!reply.contains("[MEMORY]")) return;
        try {
            String updated = memoryContext + "\n" + reply.replace("[MEMORY]", "").trim();
            CvakiStorage.saveMain(context, "brain_memory", updated);
            memoryContext = updated;
        } catch (Exception e) { Log.e(TAG, "saveMemory failed", e); }
    }

    public void clearHistory() {
        history.clear();
        consecutiveFailures = 0;
    }

    public void clearMemory() {
        memoryContext = "";
        CvakiStorage.deleteMain(context, "brain_memory");
    }
}