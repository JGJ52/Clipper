package hu.jgj52.clipper.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CompletionStage;

public class ClipperClient implements ClientModInitializer {
    private static final KeyBinding CAPTURE = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.clipper.clip",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F12,
            //? if <=1.21.8 {
            /*"category.clipper"
            *///? } else {
            KeyBinding.Category.create(Identifier.of("category.clipper"))
            //? }
    ));

    private static boolean pressed = false;
    private static WebSocket obsSocket;
    private static boolean authenticated = false;
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    private static final hu.jgj52.clipper.client.ClipperConfig config = hu.jgj52.clipper.client.ClipperConfig.createAndLoad();

    private static final String OBS_URL = config.obs_url();
    private static final String OBS_PASSWORD = config.obs_password();
    private static final String ZIPLINE_URL = config.zipline_url();
    private static final String ZIPLINE_TOKEN = config.zipline_token();

    @Override
    public void onInitializeClient() {
        doTheThing();

        config.subscribeToObs_url(subscriber -> doTheThing());
        config.subscribeToObs_password(subscriber -> doTheThing());
        config.subscribeToObs_systemd(subscriber -> doTheThing());
        config.subscribeToObs_service(subscriber -> doTheThing());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (CAPTURE.isPressed() && !pressed) {
                pressed = true;
                if (authenticated && obsSocket != null) {
                    String message = """
                        {"op":6,"d":{"requestType":"SaveReplayBuffer","requestId":"minecraft_replay"}}
                        """;
                    obsSocket.sendText(message, true);
                    if (client.player != null) {
                        client.player.sendMessage(Text.literal("§eSaving clip..."), false);
                    }
                } else if (client.player != null) {
                    client.player.sendMessage(Text.literal("§cOBS not connected or authenticated."), false);
                }
            } else if (!CAPTURE.isPressed() && pressed) {
                pressed = false;
            }
        });
    }

    private void doTheThing() {
        if (config.obs_systemd()) {
            restartObs();
        }

        new Thread(() -> {
            try {
                Thread.sleep(3000);
                connectToObs();
            } catch (InterruptedException e) {
                System.err.println("Interrupted while waiting for OBS: " + e.getMessage());
            }
        }).start();
    }

    private void restartObs() {
        try {
            ProcessBuilder pb = new ProcessBuilder("systemctl", "--user", "restart", config.obs_service() + ".service");
            Process process = pb.start();
            process.waitFor();
        } catch (Exception e) {
            System.err.println("Failed to restart OBS: " + e.getMessage());
        }
    }

    private void connectToObs() {
        try {
            obsSocket = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(OBS_URL), new Listener() {

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            String msg = data.toString();

                            if (msg.contains("\"op\":0")) {
                                handleHelloMessage(webSocket, msg);
                            } else if (msg.contains("\"op\":2")) {
                                authenticated = true;
                                startReplayBuffer();
                            } else if (msg.contains("\"op\":5") && msg.contains("ReplayBufferSaved")) {
                                handleReplayBufferSavedEvent(msg);
                            }

                            webSocket.request(1);
                            return null;
                        }

                        @Override
                        public void onError(WebSocket webSocket, Throwable error) {
                            System.err.println("OBS connection error: " + error.getMessage());
                        }
                    }).join();
        } catch (Exception e) {
            System.err.println("Failed to connect to OBS WebSocket: " + e.getMessage());
        }
    }

    private void handleHelloMessage(WebSocket webSocket, String msg) {
        try {
            String challenge = extractJsonValue(msg, "challenge");
            String salt = extractJsonValue(msg, "salt");

            String auth = computeAuth(salt, challenge);

            String identify = """
            {
              "op":1,
              "d":{
                "rpcVersion":1,
                "authentication":"%s"
              }
            }
            """.formatted(auth);

            webSocket.sendText(identify, true);
        } catch (Exception e) {
            System.err.println("Failed to handle OBS hello/auth: " + e.getMessage());
        }
    }

    private void handleReplayBufferSavedEvent(String msg) {
        try {
            String savedPath = extractJsonValue(msg, "savedReplayPath");
            if (!savedPath.isEmpty()) {
                uploadToZipline(savedPath);
            } else {
                System.err.println("Could not extract savedReplayPath from event");
            }
        } catch (Exception e) {
            System.err.println("Failed to handle replay buffer saved event: " + e.getMessage());
        }
    }

    private void uploadToZipline(String filePath) {
        new Thread(() -> {
            Path videoPath;
            try {
                videoPath = Paths.get(filePath);
                if (!Files.exists(videoPath)) {
                    System.err.println("Replay file not found: " + filePath);
                    return;
                }

                String blurredFileName = videoPath.getFileName().toString().replaceAll("\\.[^.]+$", "_blurred.mp4");
                Path blurredPath = videoPath.getParent().resolve(blurredFileName);

                ProcessBuilder blur = new ProcessBuilder(
                        config.blur_path(),
                        "-i", videoPath.toString(),
                        "-o", blurredPath.toString(),
                        "-c", config.blur_config()
                );
                blur.redirectErrorStream(true);
                Process blurProcess = blur.start();
                int exitCode = blurProcess.waitFor();

                if (exitCode != 0) {
                    System.err.println("Blur tool failed with exit code: " + exitCode);
                    return;
                }

                if (!Files.exists(blurredPath)) {
                    System.err.println("Blurred file not found: " + blurredPath);
                    return;
                }

                String fileName = blurredPath.getFileName().toString();
                String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(ZIPLINE_URL + "/api/upload"))
                        .header("Authorization", ZIPLINE_TOKEN)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> {
                            try {
                                return buildMultipartStream(fileName, blurredPath, boundary);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    String clipUrl = extractJsonValue(response.body(), "url");

                    if (!clipUrl.isEmpty()) {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player != null) {
                            Text message = Text.literal("§aClip uploaded! §b§n" + clipUrl)
                                    .styled(style -> style.withClickEvent(
                                            //? if <=1.21.4 {
                                            /*new net.minecraft.text.ClickEvent(
                                                    net.minecraft.text.ClickEvent.Action.COPY_TO_CLIPBOARD,
                                                    clipUrl
                                            )
                                            *///?} else {
                                            new ClickEvent.CopyToClipboard(clipUrl)
                                            //?}
                                    ));
                            client.player.sendMessage(message, false);
                        }
                    }

                    Files.delete(videoPath);
                    Files.delete(blurredPath);
                } else {
                    System.err.println("Upload failed with status: " + response.statusCode());
                    System.err.println("Response: " + response.body());

                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.player != null) {
                        client.player.sendMessage(Text.literal("§cClip upload failed!"), false);
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to upload to Zipline: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    private java.io.InputStream buildMultipartStream(String fileName, Path filePath, String boundary) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);

        String header = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n" +
                "Content-Type: video/mp4\r\n\r\n";
        baos.write(header.getBytes(StandardCharsets.UTF_8));

        Files.copy(filePath, baos);

        String footer = "\r\n--" + boundary + "--\r\n";
        baos.write(footer.getBytes(StandardCharsets.UTF_8));

        return new java.io.ByteArrayInputStream(baos.toByteArray());
    }

    private String extractJsonValue(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":\"");
        if (idx == -1) return "";
        int start = idx + key.length() + 4;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private String computeAuth(String salt, String challenge) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        String secret = Base64.getEncoder().encodeToString(
                sha256.digest((ClipperClient.OBS_PASSWORD + salt).getBytes(StandardCharsets.UTF_8))
        );
        return Base64.getEncoder().encodeToString(
                sha256.digest((secret + challenge).getBytes(StandardCharsets.UTF_8))
        );
    }

    private void startReplayBuffer() {
        try {
            if (obsSocket != null) {
                String startReplay = """
                {"op":6,"d":{"requestType":"StartReplayBuffer","requestId":"start_replay"}}
                """;
                obsSocket.sendText(startReplay, true);
            }
        } catch (Exception e) {
            System.err.println("Failed to start replay buffer: " + e.getMessage());
        }
    }
}