package com.lunarboreal.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lunarboreal.api.web.HttpException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Server-side, bounded requests for provider discovery, balance and semantic memory. */
final class AiGateway {
    private static final int MAX_METADATA_BYTES = 262_144;
    private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(8);

    private AiGateway() {}

    static List<String> models(HttpClient client, AiChatService.Bot bot) throws IOException, InterruptedException {
        JsonObject data = fetch(client, uri(bot.baseUrl(), "/models"), bot.apiKey(), null);
        JsonArray entries = data.getAsJsonArray("data");
        if (entries == null) throw new IOException("模型列表格式无效");
        List<String> models = new ArrayList<>();
        for (JsonElement item : entries) {
            if (!item.isJsonObject() || !item.getAsJsonObject().has("id")) continue;
            JsonElement id = item.getAsJsonObject().get("id");
            if (id.isJsonPrimitive() && id.getAsString().length() <= 128 && !id.getAsString().isBlank())
                models.add(id.getAsString());
            if (models.size() >= 200) break;
        }
        return models.stream().distinct().sorted().toList();
    }

    static Map<String, Object> balance(HttpClient client, AiChatService.Bot bot) throws IOException, InterruptedException {
        URI uri;
        if ("deepseek".equals(bot.providerType())) {
            URI base = AiChatService.endpoint(bot.baseUrl());
            if (!"api.deepseek.com".equalsIgnoreCase(base.getHost()) || !"https".equalsIgnoreCase(base.getScheme()))
                throw HttpException.badRequest("DeepSeek 余额仅支持官方 api.deepseek.com 地址");
            uri = URI.create("https://api.deepseek.com/user/balance");
        } else if ("sub2api".equals(bot.providerType())) {
            uri = uri(bot.baseUrl(), "/usage");
        } else {
            throw HttpException.badRequest("该供应商不提供已适配的余额接口");
        }
        JsonObject response = fetch(client, uri, bot.apiKey(), null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providerType", bot.providerType());
        result.put("fetchedAt", System.currentTimeMillis());
        if ("deepseek".equals(bot.providerType())) {
            if (!response.has("is_available") || !response.has("balance_infos")) throw new IOException("余额响应格式无效");
            result.put("available", response.get("is_available").getAsBoolean());
            List<Map<String, String>> balances = new ArrayList<>();
            for (JsonElement item : response.getAsJsonArray("balance_infos")) {
                JsonObject entry = item.getAsJsonObject();
                balances.add(Map.of("currency", entry.get("currency").getAsString(),
                        "remaining", entry.get("total_balance").getAsString()));
                if (balances.size() >= 4) break;
            }
            result.put("balances", balances);
        } else {
            if (!response.has("mode") || !response.has("isValid")) throw new IOException("余额响应格式无效");
            result.put("mode", response.get("mode").getAsString());
            result.put("available", response.get("isValid").getAsBoolean());
            if (response.has("remaining") && !response.get("remaining").isJsonNull())
                result.put("remaining", response.get("remaining").getAsDouble());
            if (response.has("unit") && !response.get("unit").isJsonNull())
                result.put("currency", response.get("unit").getAsString());
            if (response.has("planName") && !response.get("planName").isJsonNull())
                result.put("planName", response.get("planName").getAsString());
        }
        return result;
    }

    static double[] embedding(HttpClient client, String baseUrl, String key, String model, String text)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("input", text);
        JsonObject response = fetch(client, uri(baseUrl, "/embeddings"), key, body);
        try {
            JsonArray values = response.getAsJsonArray("data").get(0).getAsJsonObject().getAsJsonArray("embedding");
            if (values.size() < 8 || values.size() > 3072) throw new IOException("嵌入维度无效");
            double[] vector = new double[values.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = values.get(i).getAsDouble();
                if (!Double.isFinite(vector[i])) throw new IOException("嵌入数据无效");
            }
            return vector;
        } catch (RuntimeException ex) { throw new IOException("嵌入响应格式无效"); }
    }

    static String vectorJson(double[] vector) {
        JsonArray array = new JsonArray();
        for (double number : vector) array.add(number);
        return array.toString();
    }

    static String vectorJson(double[] vector, String space) {
        JsonObject result = new JsonObject();
        result.addProperty("space", space);
        result.add("values", JsonParser.parseString(vectorJson(vector)));
        return result.toString();
    }

    static String memorySpace(String baseUrl, String model) {
        try {
            byte[] value = java.security.MessageDigest.getInstance("SHA-256").digest(
                    (baseUrl.replaceAll("/+$", "") + "\n" + model).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(value);
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    static List<String> recall(List<ChatStore.AiMemory> entries, double[] query) {
        return recall(entries, query, null);
    }

    static List<String> recall(List<ChatStore.AiMemory> entries, double[] query, String space) {
        record Match(String text, double score) {}
        List<Match> matches = new ArrayList<>();
        for (ChatStore.AiMemory entry : entries) {
            try {
                if (!AiMemoryPolicy.retained(entry.text())) continue;
                JsonElement stored = JsonParser.parseString(entry.vector());
                JsonArray raw;
                if (space == null) raw = stored.getAsJsonArray();
                else {
                    JsonObject envelope = stored.getAsJsonObject();
                    if (!space.equals(envelope.get("space").getAsString())) continue;
                    raw = envelope.getAsJsonArray("values");
                }
                if (raw.size() != query.length || entry.text().length() > 800) continue;
                double dot = 0, left = 0, right = 0;
                for (int i = 0; i < query.length; i++) {
                    double value = raw.get(i).getAsDouble();
                    if (!Double.isFinite(value)) throw new IllegalArgumentException();
                    dot += query[i] * value;
                    left += query[i] * query[i];
                    right += value * value;
                }
                double score = dot / Math.sqrt(left * right);
                if (Double.isFinite(score) && score > 0.3) matches.add(new Match(entry.text(), score));
            } catch (RuntimeException ignored) { /* A corrupt entry cannot poison a reply. */ }
        }
        return matches.stream().sorted(Comparator.comparingDouble(Match::score).reversed())
                .limit(3).map(Match::text).toList();
    }

    private static URI uri(String baseUrl, String suffix) {
        AiChatService.endpoint(baseUrl);
        return URI.create(baseUrl.replaceAll("/+$", "") + suffix);
    }

    private static JsonObject fetch(HttpClient client, URI uri, String key, JsonObject body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(METADATA_TIMEOUT)
                .header("Authorization", "Bearer " + key).header("Accept", "application/json");
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        var pending = client.sendAsync(request.build(),
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_METADATA_BYTES));
        HttpResponse<byte[]> response;
        try { response = pending.get(METADATA_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException ex) { pending.cancel(true); throw new IOException("上游查询超时"); }
        catch (ExecutionException ex) { pending.cancel(true); throw new IOException("上游查询失败或响应超过限额"); }
        catch (InterruptedException ex) { pending.cancel(true); throw ex; }
        if (response.statusCode() != 200) throw new AiChatService.UpstreamStatusException(response.statusCode());
        try { return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException ex) { throw new IOException("上游响应格式无效"); }
    }
}
