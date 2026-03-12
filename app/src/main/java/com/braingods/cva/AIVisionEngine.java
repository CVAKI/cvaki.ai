package com.braingods.cva;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * AIVisionEngine
 *
 * Takes a Bitmap screenshot + user intent, sends to Claude Vision (or Gemini),
 * and returns a structured list of ScreenActions to execute.
 *
 * Action types:
 *   CLICK         — tap pixel (x, y)
 *   TYPE          — inject text at current focus
 *   TELEPORT_TYPE — click (x,y) then type text
 *   SCROLL        — swipe from (x1,y1) to (x2,y2)
 *   WAIT          — pause for delayMs
 *   DONE          — task complete, speak message
 *
 * FIXES vs previous version:
 *   - Gemini now calls /v1beta/ (system_instruction + responseMimeType require beta)
 *   - ThinkingCallback lets callers stream "still thinking…" dots live
 */
public class AIVisionEngine {

    private static final String TAG = "AIVision";

    // ── Action model ──────────────────────────────────────────────────────────

    public enum ActionType { CLICK, TYPE, TELEPORT_TYPE, SCROLL, WAIT, DONE, ERROR }

    public static class ScreenAction {
        public ActionType type;
        public float      x, y;           // CLICK / TELEPORT_TYPE target
        public float      x2, y2;         // SCROLL end point
        public String     text;           // TYPE / TELEPORT_TYPE / DONE message
        public int        delayMs = 300;  // WAIT duration
        public String     note;           // human-readable explanation

        @Override public String toString() {
            return "ScreenAction{" + type
                    + (type == ActionType.CLICK        ? " @(" + x + "," + y + ")" : "")
                    + (type == ActionType.TELEPORT_TYPE? " @(" + x + "," + y + ") text=\"" + text + "\"" : "")
                    + (type == ActionType.TYPE         ? " text=\"" + text + "\"" : "")
                    + (type == ActionType.DONE         ? " msg=\"" + text + "\"" : "")
                    + (note != null                    ? " [" + note + "]" : "")
                    + "}";
        }
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────

    public interface VisionCallback {
        void onActions(List<ScreenAction> actions);
        void onError(String message);
    }

    /**
     * Optional: called periodically while the AI is still processing so the UI
     * can show a live "thinking…" indicator.  Fire with null to signal done.
     */
    public interface ThinkingCallback {
        /** @param dot incremental "." chars (1-4) or null = finished */
        void onThinkingTick(String dot);
    }

    // ── Config ────────────────────────────────────────────────────────────────

    private final String apiKey;
    private final String provider; // "anthropic" | "gemini" | "openrouter" | "groq"

    /** AI image width used when building prompts (scaled before encoding) */
    private static final int AI_IMG_WIDTH = 768;

    public AIVisionEngine(String apiKey, String provider) {
        this.apiKey   = apiKey;
        this.provider = provider;
    }

    // ── System prompt ─────────────────────────────────────────────────────────

    private static final String SYSTEM_PROMPT =
            "You are CVA Screen Agent — a precise UI automation assistant.\n\n" +
                    "You receive a screenshot of an Android screen and a task description.\n" +
                    "Your job is to analyze the screen and output ONLY a JSON array of actions\n" +
                    "that, when executed in order, will complete the task.\n\n" +
                    "ACTION SCHEMA:\n" +
                    "  {\"type\":\"CLICK\",        \"x\":<int>, \"y\":<int>,                  \"note\":\"<why>\"}\n" +
                    "  {\"type\":\"TYPE\",          \"text\":\"<string>\",                       \"note\":\"<why>\"}\n" +
                    "  {\"type\":\"TELEPORT_TYPE\", \"x\":<int>, \"y\":<int>, \"text\":\"<string>\",\"note\":\"<why>\"}\n" +
                    "  {\"type\":\"SCROLL\",        \"x\":<int>, \"y\":<int>, \"x2\":<int>, \"y2\":<int>, \"note\":\"<why>\"}\n" +
                    "  {\"type\":\"WAIT\",          \"delayMs\":<int>,                           \"note\":\"<why>\"}\n" +
                    "  {\"type\":\"DONE\",          \"message\":\"<result summary>\"}            \n\n" +
                    "RULES:\n" +
                    "- Coordinates are in SCREEN PIXELS matching the image you were given.\n" +
                    "- For MCQ / radio buttons: identify the correct option, output CLICK on that radio circle.\n" +
                    "- For text fields: use TELEPORT_TYPE (click field first, then type).\n" +
                    "- End EVERY response with a DONE action.\n" +
                    "- Output ONLY the JSON array. No explanation, no markdown, no code fences.\n" +
                    "- If the screen appears BLACK or EMPTY, still output a valid JSON array:\n" +
                    "  [{\"type\":\"DONE\",\"message\":\"Screen appears blank — no actions taken\"}]\n" +
                    "- NEVER respond with plain text. ALWAYS respond with a JSON array, even on errors.\n";

    // ── Main entry point ──────────────────────────────────────────────────────

    /**
     * Analyze screenshot and return actions.  Run on a background thread.
     *
     * @param screenshot  Full-resolution bitmap (scaled internally)
     * @param task        User intent, e.g. "Answer the MCQ question on screen"
     * @param thinkingCb  Optional – called every ~2 s while waiting for the AI
     * @param cb          Result callback – fires on the calling thread
     */
    public void analyze(Bitmap screenshot, String task,
                        ThinkingCallback thinkingCb, VisionCallback cb) {
        // Scale to AI_IMG_WIDTH for reasonable token usage
        Bitmap scaled = scaleBitmap(screenshot, AI_IMG_WIDTH);
        int imgW = scaled.getWidth();
        int imgH = scaled.getHeight();

        String base64 = bitmapToBase64(scaled);
        scaled.recycle();

        if (base64 == null) { cb.onError("Failed to encode screenshot"); return; }

        String userPrompt = "TASK: " + task + "\n\n" +
                "Image size: " + imgW + "×" + imgH + " px. " +
                "Return actions with coordinates matching this image size.";

        // ── Thinking ticker thread ────────────────────────────────────────────
        Thread ticker = null;
        if (thinkingCb != null) {
            ticker = new Thread(() -> {
                int dot = 0;
                String[] dots = {".", "..", "...", "...."};
                while (!Thread.currentThread().isInterrupted()) {
                    thinkingCb.onThinkingTick(dots[dot % 4]);
                    dot++;
                    try { Thread.sleep(1800); } catch (InterruptedException e) { break; }
                }
                // signal done
                thinkingCb.onThinkingTick(null);
            });
            ticker.setDaemon(true);
            ticker.start();
        }

        try {
            String rawJson;
            if ("gemini".equalsIgnoreCase(provider)) {
                rawJson = callGemini(base64, userPrompt, imgW, imgH);
            } else if ("openrouter".equalsIgnoreCase(provider)) {
                rawJson = callOpenRouterVision(base64, userPrompt);
            } else if ("groq".equalsIgnoreCase(provider)) {
                // Groq has no vision API
                cb.onError("Groq does not support vision. Use Anthropic, Gemini, or OpenRouter. " +
                        "Go to Settings, change provider, and Save.");
                return;
            } else {
                // "anthropic" or unknown → Claude
                rawJson = callClaude(base64, userPrompt);
            }

            List<ScreenAction> actions = parseActions(rawJson, screenshot.getWidth(),
                    screenshot.getHeight(), imgW, imgH);
            cb.onActions(actions);

        } catch (Exception e) {
            Log.e(TAG, "Vision API error", e);
            cb.onError(e.getMessage());
        } finally {
            if (ticker != null) ticker.interrupt();
        }
    }

    /**
     * Convenience overload — no thinking callback (backwards-compatible).
     */
    public void analyze(Bitmap screenshot, String task, VisionCallback cb) {
        analyze(screenshot, task, null, cb);
    }

    // ── Claude Vision ─────────────────────────────────────────────────────────

    private String callClaude(String base64, String userPrompt) throws Exception {
        JSONObject req = new JSONObject();
        req.put("model", "claude-haiku-4-5-20251001");
        req.put("max_tokens", 1024);
        req.put("system", SYSTEM_PROMPT);

        JSONArray content = new JSONArray();

        // Image block
        JSONObject imgSrc = new JSONObject();
        imgSrc.put("type", "base64");
        imgSrc.put("media_type", "image/jpeg");
        imgSrc.put("data", base64);
        JSONObject imgBlock = new JSONObject();
        imgBlock.put("type", "image");
        imgBlock.put("source", imgSrc);
        content.put(imgBlock);

        // Text block
        JSONObject textBlock = new JSONObject();
        textBlock.put("type", "text");
        textBlock.put("text", userPrompt);
        content.put(textBlock);

        JSONArray messages = new JSONArray();
        JSONObject msg = new JSONObject();
        msg.put("role", "user");
        msg.put("content", content);
        messages.put(msg);
        req.put("messages", messages);

        HttpURLConnection conn = openConn("https://api.anthropic.com/v1/messages");
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        writeBody(conn, req.toString());

        String body = readBody(conn);
        JSONObject resp = new JSONObject(body);
        return resp.getJSONArray("content").getJSONObject(0).getString("text");
    }

    // ── Gemini Vision ─────────────────────────────────────────────────────────
    // Uses /v1beta/ for system_instruction + responseMimeType support.
    // Auto-falls-back through model list on 429 (quota) or 404 (not found).

    // Models tried in order — stops at first success.
    // Free-tier keys often only have access to 1.5-flash or 1.5-flash-8b.
    private static final String[] GEMINI_MODELS = {
            "gemini-2.0-flash",        // best: GA, vision, fast
            "gemini-1.5-flash",        // solid fallback, widely available
            "gemini-1.5-flash-8b",     // lightest free-tier model
    };

    private String callGemini(String base64, String userPrompt,
                              int imgW, int imgH) throws Exception {
        Exception lastErr = null;
        for (String model : GEMINI_MODELS) {
            try {
                Log.d(TAG, "Gemini trying model: " + model);
                return callGeminiModel(model, base64, userPrompt);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                // Only retry on 429 (quota) or 404 (model not found on this key)
                if (msg.contains("429") || msg.contains("404")) {
                    Log.w(TAG, "Gemini model " + model + " unavailable (" +
                            (msg.contains("429") ? "quota" : "not found") + "), trying next…");
                    lastErr = e;
                } else {
                    throw e; // hard error (400 bad request, 403 invalid key, etc.) — stop immediately
                }
            }
        }
        // All models exhausted — give the user a clear, actionable message
        throw new Exception(
                "Gemini quota exceeded on all models.\n" +
                        "Your free-tier limit is 0 — you need to either:\n" +
                        "  1. Enable billing at https://ai.google.dev\n" +
                        "  2. Wait for your daily quota to reset\n" +
                        "  3. Switch to OpenRouter (free vision) in Settings");
    }

    private String callGeminiModel(String model, String base64,
                                   String userPrompt) throws Exception {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + apiKey;

        JSONObject req = new JSONObject();

        // System instruction (v1beta only)
        JSONObject sysInst = new JSONObject();
        sysInst.put("parts", new JSONArray().put(
                new JSONObject().put("text", SYSTEM_PROMPT)));
        req.put("system_instruction", sysInst);

        // User content
        JSONArray parts = new JSONArray();

        JSONObject imgPart = new JSONObject();
        JSONObject inlineData = new JSONObject();
        inlineData.put("mime_type", "image/jpeg");
        inlineData.put("data", base64);
        imgPart.put("inline_data", inlineData);
        parts.put(imgPart);

        parts.put(new JSONObject().put("text", userPrompt));

        JSONObject userContent = new JSONObject();
        userContent.put("role", "user");
        userContent.put("parts", parts);

        req.put("contents", new JSONArray().put(userContent));

        // Generation config (responseMimeType supported in v1beta)
        JSONObject genCfg = new JSONObject();
        genCfg.put("maxOutputTokens", 1024);
        genCfg.put("responseMimeType", "application/json");
        req.put("generationConfig", genCfg);

        HttpURLConnection conn = openConn(url);
        writeBody(conn, req.toString());

        String body = readBody(conn);
        JSONObject resp = new JSONObject(body);

        Log.i(TAG, "Gemini success with model: " + model);
        return resp.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text");
    }

    // ── OpenRouter Vision ─────────────────────────────────────────────────────

    private static final String[] OR_FREE_VISION_MODELS = {
            "google/gemini-2.0-flash-001",          // GA, vision, free tier
            "meta-llama/llama-3.2-11b-vision-instruct",  // Meta vision, free tier
            "google/gemini-2.0-flash-lite-001",       // lightest Gemini, free tier
    };

    private String callOpenRouterVision(String base64, String userPrompt) throws Exception {
        Exception lastErr = null;
        for (String model : OR_FREE_VISION_MODELS) {
            try {
                Log.d(TAG, "OpenRouter trying: " + model);
                return callOpenRouterModel(model, base64, userPrompt);
            } catch (Exception e) {
                Log.w(TAG, "OpenRouter model " + model + " failed: " + e.getMessage());
                lastErr = e;
            }
        }
        throw lastErr != null ? lastErr : new Exception("All OpenRouter vision models failed");
    }

    private String callOpenRouterModel(String model, String base64,
                                       String userPrompt) throws Exception {
        JSONObject req = new JSONObject();
        req.put("model", model);
        req.put("max_tokens", 1024);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "system")
                .put("content", SYSTEM_PROMPT));

        JSONArray userContent = new JSONArray();
        userContent.put(new JSONObject()
                .put("type", "image_url")
                .put("image_url", new JSONObject()
                        .put("url", "data:image/jpeg;base64," + base64)));
        userContent.put(new JSONObject()
                .put("type", "text")
                .put("text", userPrompt));
        messages.put(new JSONObject().put("role", "user").put("content", userContent));
        req.put("messages", messages);

        HttpURLConnection conn = openConn("https://openrouter.ai/api/v1/chat/completions");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("HTTP-Referer", "com.braingods.cva");
        writeBody(conn, req.toString());

        String body = readBody(conn);
        JSONObject resp = new JSONObject(body);
        return resp.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
    }

    // ── Parse JSON actions ────────────────────────────────────────────────────

    private List<ScreenAction> parseActions(String rawJson,
                                            int screenW, int screenH,
                                            int aiImgW,  int aiImgH) {
        List<ScreenAction> list = new ArrayList<>();
        float sx = (float) screenW / aiImgW;
        float sy = (float) screenH / aiImgH;

        try {
            String json = rawJson.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("(?s)^```[a-z]*\\n?", "")
                        .replaceAll("```$", "").trim();
            }
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                ScreenAction a = new ScreenAction();
                a.type = ActionType.valueOf(obj.getString("type").toUpperCase());
                a.note = obj.optString("note", "");

                switch (a.type) {
                    case CLICK:
                        a.x = obj.getInt("x") * sx;
                        a.y = obj.getInt("y") * sy;
                        break;
                    case TYPE:
                        a.text = obj.getString("text");
                        break;
                    case TELEPORT_TYPE:
                        a.x    = obj.getInt("x") * sx;
                        a.y    = obj.getInt("y") * sy;
                        a.text = obj.getString("text");
                        break;
                    case SCROLL:
                        a.x  = obj.getInt("x")  * sx;
                        a.y  = obj.getInt("y")  * sy;
                        a.x2 = obj.getInt("x2") * sx;
                        a.y2 = obj.getInt("y2") * sy;
                        break;
                    case WAIT:
                        a.delayMs = obj.optInt("delayMs", 500);
                        break;
                    case DONE:
                        a.text = obj.optString("message", "Done");
                        break;
                    case ERROR:
                        a.text = obj.optString("message", "Unknown error");
                        break;
                }
                list.add(a);
                Log.d(TAG, "Parsed: " + a);
            }
        } catch (Exception e) {
            Log.e(TAG, "parseActions error: " + e.getMessage() + "\nRaw: " + rawJson);
            // Try to detect if AI returned prose instead of JSON (model ignored instructions)
            String hint = rawJson != null && rawJson.length() > 10
                    ? " | AI said: " + rawJson.trim().substring(0, Math.min(120, rawJson.trim().length()))
                    : "";
            ScreenAction err = new ScreenAction();
            err.type = ActionType.ERROR;
            err.text = "AI returned text instead of JSON." + hint;
            list.add(err);
        }
        return list;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private Bitmap scaleBitmap(Bitmap src, int maxWidth) {
        if (src.getWidth() <= maxWidth) return src;
        float scale = (float) maxWidth / src.getWidth();
        int w = maxWidth;
        int h = Math.round(src.getHeight() * scale);
        return Bitmap.createScaledBitmap(src, w, h, true);
    }

    private String bitmapToBase64(Bitmap bmp) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "base64 error", e); return null;
        }
    }

    private HttpURLConnection openConn(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(90_000);   // 90 s — Gemini 2.5 thinking can be slow
        c.setRequestProperty("Content-Type", "application/json");
        return c;
    }

    private void writeBody(HttpURLConnection c, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        c.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
    }

    private String readBody(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        java.io.InputStream is = (code >= 200 && code < 300)
                ? c.getInputStream() : c.getErrorStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        if (code < 200 || code >= 300)
            throw new Exception("HTTP " + code + ": " +
                    sb.toString().substring(0, Math.min(400, sb.length())));
        return sb.toString();
    }
}