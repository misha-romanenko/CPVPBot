package net.cpvpbot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Localhost-only JSON-lines control server. The Python driver connects, sends one
 * command per line, and receives one JSON response per line. Commands are executed
 * on the Minecraft client thread.
 */
public class ControlServer {

    public static final int PORT = 4777;

    private static final ExecutorService CONN_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cpvpbot-conn");
        t.setDaemon(true);
        return t;
    });

    public static void start() {
        Thread t = new Thread(ControlServer::serve, "cpvpbot-control");
        t.setDaemon(true);
        t.start();
    }

    private static void serve() {
        try (ServerSocket ss = new ServerSocket()) {
            ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 8);
            CpvpBotMod.log("CTRL", "control server listening on 127.0.0.1:" + PORT);
            while (true) {
                Socket s = ss.accept();
                s.setTcpNoDelay(true);
                CONN_POOL.submit(() -> handle(s));
            }
        } catch (IOException e) {
            CpvpBotMod.log("CTRL", "control server failed: " + e);
        }
    }

    private static void handle(Socket socket) {
        try (Socket sock = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), false)) {
            CpvpBotMod.log("CTRL", "driver connected: " + sock.getRemoteSocketAddress());
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                JsonObject resp;
                try {
                    JsonObject cmd = JsonParser.parseString(line).getAsJsonObject();
                    resp = runOnClientThread(cmd);
                } catch (Exception e) {
                    resp = new JsonObject();
                    resp.addProperty("ok", false);
                    resp.addProperty("error", String.valueOf(e));
                }
                out.println(resp.toString());
                out.flush();
            }
            CpvpBotMod.log("CTRL", "driver disconnected");
        } catch (IOException ignored) {
        }
    }

    private static JsonObject runOnClientThread(JsonObject cmd) throws Exception {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                future.complete(Actions.dispatch(cmd));
            } catch (Throwable t) {
                JsonObject o = new JsonObject();
                o.addProperty("ok", false);
                o.addProperty("error", String.valueOf(t));
                future.complete(o);
            }
        });
        return future.get(20, TimeUnit.SECONDS);
    }
}
