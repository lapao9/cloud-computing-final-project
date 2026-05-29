package cn2026.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls the Lookup Cloud Function and returns the list of (IP, port)
 * pairs of the gRPC servers.
 *
 * The function is expected to return JSON of the form:
 *   { "servers": [ {"name":"...", "ip":"34.x.x.x", "port":8000}, ... ] }
 */
public class LookupClient {

    public record Server(String name, String ip, int port) {}

    private final String url;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    public LookupClient(String url) { this.url = url; }

    public List<Server> list() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("Lookup HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonObject root = gson.fromJson(resp.body(), JsonObject.class);
        JsonArray arr = root.getAsJsonArray("servers");
        List<Server> out = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            out.add(new Server(
                    o.has("name")? o.get("name").getAsString() : "?",
                    o.get("ip").getAsString(),
                    o.has("port")? o.get("port").getAsInt() : 8000));
        }
        return out;
    }
}
