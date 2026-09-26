package com.phonescreen.client;

import com.mojang.blaze3d.platform.NativeImage;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class PhoneStreamManager {

    private static final String MOD_ID = "phonescreen";

    private static final Identifier PHONE_TEXTURE_ID =
            Identifier.fromNamespaceAndPath(MOD_ID, "phone_display");

    private static volatile String cachedAdbExecutable;

    /*
     * Protects the streaming lifecycle.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(
                        runnable,
                        "PhoneScreen-Scrcpy"
                );

                thread.setDaemon(true);
                return thread;
            });

    private volatile Process captureProcess;
    private volatile Socket streamSocket;
    private volatile int activeForwardPort = -1;
    private volatile String activeDeviceSerial;
    private final AtomicReference<CapturedFrame> pendingFrame = new AtomicReference<>();
    private final AtomicBoolean frameUploadQueued = new AtomicBoolean(false);
    private final AtomicBoolean nextButtonRequested = new AtomicBoolean(false);

    /*
     * These are only accessed from the Minecraft client thread
     * after a frame has been handed over.
     */
    private DynamicTexture dynamicTexture;

    private volatile boolean textureReady = false;

    private volatile int streamWidth = 0;
    private volatile int streamHeight = 0;
    private volatile int deviceWidth = 0;
    private volatile int deviceHeight = 0;
    private volatile int swipeRangePercent = 20;

    /*
     * Used to avoid constantly recreating the texture.
     */
    private int textureWidth = 0;
    private int textureHeight = 0;

    public PhoneStreamManager() {
    }

    // -----------------------------------------------------------------
    // START
    // -----------------------------------------------------------------

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        executor.submit(this::runStream);
    }

    /** Starts scrcpy's standalone H.264 server and decodes its low-latency video stream. */
    private void runStream() {
        while (running.get()) {
            try {
                runScrcpySession();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("[PhoneScreen] scrcpy stream failed (" + e.getClass().getSimpleName() + "): " + String.valueOf(e.getMessage()));
                    e.printStackTrace(System.err);
                    pause(1500);
                }
            } finally {
                closeStreamSession();
            }
        }
    }

    private void runScrcpySession() throws Exception {
        String adb = findAdbExecutable();
        Path serverPath = findScrcpyServer(adb);
        String version = findScrcpyVersion(adb);
        if (adb == null || serverPath == null || version == null) {
            throw new IOException("Could not locate matching scrcpy.exe, scrcpy-server, and adb.exe. Install the official Windows scrcpy package and keep these files together.");
        }

        String deviceSerial = findPreferredDevice(adb);
        if (deviceSerial == null) {
            throw new IOException("No authorized ADB device found. Connect and unlock the phone, then accept the USB debugging prompt.");
        }
        activeDeviceSerial = deviceSerial;
        System.out.println("[PhoneScreen] Using ADB device " + deviceSerial);
        int[] displaySize = getDeviceDisplaySize(adb, deviceSerial);
        if (displaySize != null) {
            deviceWidth = displaySize[0];
            deviceHeight = displaySize[1];
            System.out.println("[PhoneScreen] Android touch display: " + deviceWidth + "x" + deviceHeight);
        }

        final String remoteServer = "/data/local/tmp/phonescreen-scrcpy-server.jar";
        runCommand(adb, "-s", deviceSerial, "push", serverPath.toString(), remoteServer);

        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        runCommand(adb, "-s", deviceSerial, "forward", "tcp:" + port, "localabstract:scrcpy");
        activeForwardPort = port;

        Process server = new ProcessBuilder(
                adb, "-s", deviceSerial, "shell",
                "CLASSPATH=" + remoteServer,
                "app_process", "/", "com.genymobile.scrcpy.Server", version,
                "tunnel_forward=true", "audio=false", "control=false",
                "cleanup=true", "video_codec=h264",
                "max_size=1600", "max_fps=60", "video_bit_rate=12000000", "log_level=info"
        ).redirectErrorStream(true).start();
        captureProcess = server;
        Thread serverLogReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) System.err.println("[PhoneScreen] scrcpy server: " + line);
            } catch (IOException ignored) { }
        }, "PhoneScreen-ScrcpyServerLog");
        serverLogReader.setDaemon(true);
        serverLogReader.start();

        Socket socket = null;
        DataInputStream scrcpyStream = null;
        long connectDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (running.get() && server.isAlive() && System.nanoTime() < connectDeadline) {
            Socket candidate = new Socket();
            try {
                candidate.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
                candidate.setSoTimeout(500);
                DataInputStream candidateStream = new DataInputStream(candidate.getInputStream());
                // ADB may accept the local TCP connection before scrcpy has
                // bound its Android abstract socket. Only keep connections
                // that actually receive scrcpy's forward-tunnel dummy byte.
                candidateStream.readUnsignedByte();
                socket = candidate;
                scrcpyStream = candidateStream;
                break;
            } catch (IOException e) {
                try { candidate.close(); } catch (IOException ignored) { }
                Thread.sleep(100);
            }
        }
        if (socket == null) throw new IOException("Timed out waiting for scrcpy to accept the video connection.");
        streamSocket = socket;
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10_000);
        final DataInputStream connectedStream = scrcpyStream;
        // In forward mode scrcpy sends a dummy byte, then a 64-byte device name,
        // followed by the codec id and the initial video-size session packet.
        connectedStream.readFully(new byte[64]);
        int codecId = connectedStream.readInt();
        if (codecId != 0x68323634) throw new IOException("scrcpy did not start an H.264 video stream.");
        byte[] sessionHeader = new byte[12];
        connectedStream.readFully(sessionHeader);
        if ((sessionHeader[0] & 0x80) == 0) throw new IOException("scrcpy video size metadata is missing.");
        int initialWidth = java.nio.ByteBuffer.wrap(sessionHeader, 4, 4).getInt();
        int initialHeight = java.nio.ByteBuffer.wrap(sessionHeader, 8, 4).getInt();
        System.out.println("[PhoneScreen] Scrcpy video connected: " + initialWidth + "x" + initialHeight);
        socket.setSoTimeout(0);

        PipedInputStream decoderInput = new PipedInputStream(1 << 20);
        PipedOutputStream decoderPackets = new PipedOutputStream(decoderInput);
        Thread packetReader = new Thread(() -> copyScrcpyPackets(connectedStream, decoderPackets), "PhoneScreen-ScrcpyPackets");
        packetReader.setDaemon(true);
        packetReader.start();
        try (FFmpegFrameGrabber decoder = new FFmpegFrameGrabber(decoderInput, 0)) {
            decoder.setFormat("h264");
            decoder.setImageWidth(initialWidth);
            decoder.setImageHeight(initialHeight);
            // The stream already supplied its dimensions and codec metadata;
            // keep FFmpeg from waiting for a large probe window on a live pipe.
            decoder.setOption("probesize", "1024");
            decoder.setOption("analyzeduration", "0");
            decoder.start();
            System.out.println("[PhoneScreen] H.264 decoder started.");
            boolean firstFrame = true;
            while (running.get()) {
                Frame decoded = decoder.grabImage();
                if (decoded == null) break;
                if (nextButtonRequested.compareAndSet(true, false)) {
                    findAndPressNext(adb, deviceSerial, decoded);
                }
                NativeImage image = toNativeImage(decoded);
                if (image == null) continue;
                int width = image.getWidth();
                int height = image.getHeight();
                if (firstFrame) {
                    System.out.println("[PhoneScreen] First decoded phone frame: " + width + "x" + height);
                    firstFrame = false;
                }
                streamWidth = width;
                streamHeight = height;
                if (deviceWidth <= 0 || deviceHeight <= 0) {
                    deviceWidth = width;
                    deviceHeight = height;
                }
                queueFrame(image, width, height);
            }
        } finally {
            try { decoderPackets.close(); } catch (IOException ignored) { }
            try { decoderInput.close(); } catch (IOException ignored) { }
            packetReader.interrupt();
        }
    }

    /** Requests OCR on the next decoded frame, then taps the center of an exact Next word. */
    public void findAndPressNext() {
        if (!running.get()) {
            postClientMessage("PhoneScreen is not connected to a phone yet.");
            return;
        }
        nextButtonRequested.set(true);
        postClientMessage("Searching the phone screen for Next...");
    }

    private void findAndPressNext(String adb, String deviceSerial, Frame frame) {
        try {
            if (frame.image == null || frame.image.length == 0 || !(frame.image[0] instanceof ByteBuffer pixels)) {
                postClientMessage("Could not read a phone frame for OCR.");
                return;
            }
            NextButtonRecognizer.Point point = NextButtonRecognizer.findNext(
                    pixels, frame.imageWidth, frame.imageHeight, frame.imageStride, frame.imageChannels);
            if (point == null) {
                postClientMessage("Could not find a visible Next label on the phone.");
                return;
            }
            int tapX = Math.round(point.x() * (float) deviceWidth / frame.imageWidth);
            int tapY = Math.round(point.y() * (float) deviceHeight / frame.imageHeight);
            new ProcessBuilder(adb, "-s", deviceSerial, "shell", "input", "tap",
                    Integer.toString(tapX), Integer.toString(tapY))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().onExit();
            postClientMessage("Found Next and tapped it.");
        } catch (Exception e) {
            System.err.println("[PhoneScreen] Next-button OCR failed: " + e);
            e.printStackTrace(System.err);
            postClientMessage("Could not scan or tap Next: " + String.valueOf(e.getMessage()));
        }
    }

    private static void postClientMessage(String message) {
        Minecraft.getInstance().execute(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) client.gui.setOverlayMessage(Component.literal(message), false);
        });
    }

    private static int[] getDeviceDisplaySize(String adb, String serial) {
        try {
            String output = runCommand(adb, "-s", serial, "shell", "wm", "size");
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?:Physical|Override) size:\\s*(\\d+)x(\\d+)").matcher(output);
            int width = 0;
            int height = 0;
            while (matcher.find()) {
                width = Integer.parseInt(matcher.group(1));
                height = Integer.parseInt(matcher.group(2));
            }
            return width > 0 && height > 0 ? new int[]{width, height} : null;
        } catch (Exception e) {
            System.err.println("[PhoneScreen] Could not read touch display size: " + e.getMessage());
            return null;
        }
    }

    /** Strips scrcpy's packet/session headers and feeds only H.264 payload bytes to FFmpeg. */
    private static void copyScrcpyPackets(DataInputStream input, PipedOutputStream output) {
        byte[] header = new byte[12];
        int packetNumber = 0;
        try (output) {
            while (!Thread.currentThread().isInterrupted()) {
                input.readFully(header);
                if ((header[0] & 0x80) != 0) {
                    // Resolution changed (for example, after rotating the phone).
                    int width = java.nio.ByteBuffer.wrap(header, 4, 4).getInt();
                    int height = java.nio.ByteBuffer.wrap(header, 8, 4).getInt();
                    System.out.println("[PhoneScreen] Phone stream resolution changed: " + width + "x" + height);
                    continue;
                }
                int packetSize = java.nio.ByteBuffer.wrap(header, 8, 4).getInt();
                if (packetSize <= 0 || packetSize > 16 * 1024 * 1024) {
                    throw new IOException("Invalid scrcpy video packet size: " + packetSize);
                }
                byte[] packet = new byte[packetSize];
                input.readFully(packet);
                if (packetNumber < 3) {
                    long ptsFlags = java.nio.ByteBuffer.wrap(header, 0, 8).getLong();
                    System.out.println("[PhoneScreen] Received H.264 packet " + (packetNumber + 1)
                            + ": " + packetSize + " bytes, config=" + ((ptsFlags & (1L << 62)) != 0)
                            + ", keyframe=" + ((ptsFlags & (1L << 61)) != 0));
                }
                packetNumber++;
                output.write(packet);
                output.flush();
            }
        } catch (IOException e) {
            // Closing the stream ends the decoder when scrcpy disconnects.
            if (!Thread.currentThread().isInterrupted()) {
                System.err.println("[PhoneScreen] Video packet reader stopped: " + e);
            }
        }
    }

    private void closeStreamSession() {
        Socket socket = streamSocket;
        streamSocket = null;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        destroyCaptureProcess();
        int port = activeForwardPort;
        activeForwardPort = -1;
        String deviceSerial = activeDeviceSerial;
        activeDeviceSerial = null;
        String adb = findAdbExecutable();
        if (port >= 0 && adb != null && deviceSerial != null) {
            try {
                new ProcessBuilder(adb, "-s", deviceSerial, "forward", "--remove", "tcp:" + port)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
            } catch (IOException e) {
                System.err.println("[PhoneScreen] Could not remove ADB forward: " + e.getMessage());
            }
        }
    }

    private NativeImage toNativeImage(Frame frame) {
        if (frame.image == null || frame.image.length == 0 || !(frame.image[0] instanceof ByteBuffer pixels)) return null;
        int width = frame.imageWidth;
        int height = frame.imageHeight;
        int stride = frame.imageStride;
        int channels = frame.imageChannels;
        if (channels != 3 && channels != 4) return null;
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        try {
            for (int y = 0; y < height; y++) {
                int row = y * stride;
                for (int x = 0; x < width; x++) {
                    int offset = row + x * channels;
                    // JavaCV's default COLOR output is packed BGR24.
                    int r = pixels.get(offset + (channels == 4 ? 0 : 2)) & 0xFF;
                    int g = pixels.get(offset + 1) & 0xFF;
                    int b = pixels.get(offset + (channels == 4 ? 2 : 0)) & 0xFF;
                    int a = channels == 4 ? pixels.get(offset + 3) & 0xFF : 0xFF;
                    // NativeImage.setPixel() accepts ARGB; its RGBA backing
                    // storage conversion is handled internally by Minecraft.
                    image.setPixel(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            return image;
        } catch (RuntimeException e) {
            image.close();
            throw e;
        }
    }

    private static String runCommand(String executable, String... arguments) throws IOException, InterruptedException {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(executable);
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream stream = process.getInputStream()) {
            stream.transferTo(output);
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Command timed out: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IOException(String.join(" ", command) + " failed: " + output.toString(java.nio.charset.StandardCharsets.UTF_8));
        }
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Prefer a physical phone if an Android emulator is also attached. */
    private static String findPreferredDevice(String adb) throws IOException, InterruptedException {
        String wirelessAddress = getWirelessAddress();
        if (!wirelessAddress.isBlank()) {
            // The user has explicitly selected Wi-Fi. Connect through ADB's
            // existing wireless-debugging pairing and never fall back to USB.
            runCommand(adb, "connect", wirelessAddress);
            String wirelessDevices = runCommand(adb, "devices", "-l");
            for (String line : wirelessDevices.split("\\R")) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length >= 2 && fields[0].equals(wirelessAddress) && fields[1].equals("device")) {
                    return wirelessAddress;
                }
            }
            throw new IOException("ADB could not connect to the configured Wi-Fi device " + wirelessAddress
                    + ". Check Wireless debugging is enabled, the phone and PC are on the same network, and the pairing is still active.");
        }

        String output = runCommand(adb, "devices", "-l");
        java.util.ArrayList<String> authorized = new java.util.ArrayList<>();
        java.util.ArrayList<String> physical = new java.util.ArrayList<>();
        for (String line : output.split("\\R")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 2 || !fields[1].equals("device") || fields[0].equals("List")) continue;
            authorized.add(fields[0]);
            if (!fields[0].startsWith("emulator-") && !fields[0].startsWith("127.0.0.1:")) {
                physical.add(fields[0]);
            }
        }
        if (!physical.isEmpty()) return physical.get(0);
        return authorized.isEmpty() ? null : authorized.get(0);
    }

    private static String getWirelessAddress() {
        Path config = Minecraft.getInstance().gameDirectory.toPath().resolve("config/phonescreen.properties");
        if (!Files.isRegularFile(config)) return "";
        java.util.Properties properties = new java.util.Properties();
        try (var reader = Files.newBufferedReader(config)) {
            properties.load(reader);
            return properties.getProperty("wirelessAddress", "").trim();
        } catch (IOException e) {
            System.err.println("[PhoneScreen] Could not read wireless ADB address: " + e.getMessage());
            return "";
        }
    }

    private static Path findScrcpyServer(String adb) {
        if (adb == null) return null;
        Path candidate = Path.of(adb).toAbsolutePath().getParent().resolve("scrcpy-server");
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private static String findScrcpyVersion(String adb) throws IOException, InterruptedException {
        if (adb == null) return null;
        Path executable = Path.of(adb).toAbsolutePath().getParent().resolve("scrcpy.exe");
        if (!Files.isRegularFile(executable)) return null;
        Process process = new ProcessBuilder(executable.toString(), "--version").redirectErrorStream(true).start();
        String output;
        try (InputStream stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return null;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("scrcpy ")) return trimmed.substring("scrcpy ".length()).split("\\s", 2)[0];
        }
        return null;
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void queueFrame(NativeImage image, int width, int height) {
        CapturedFrame replaced = pendingFrame.getAndSet(new CapturedFrame(image, width, height));
        if (replaced != null) replaced.image().close();
        scheduleFrameUpload();
    }

    private void scheduleFrameUpload() {
        if (frameUploadQueued.compareAndSet(false, true)) {
            Minecraft.getInstance().execute(this::uploadPendingFrame);
        }
    }

    private void uploadPendingFrame() {
        try {
            CapturedFrame newest = pendingFrame.getAndSet(null);
            if (newest != null) uploadFrame(newest.image(), newest.width(), newest.height());
        } finally {
            frameUploadQueued.set(false);
            if (pendingFrame.get() != null) scheduleFrameUpload();
        }
    }

    // -----------------------------------------------------------------
    // -----------------------------------------------------------------
    // MINECRAFT TEXTURE
    // -----------------------------------------------------------------

    private void uploadFrame(
            NativeImage frame,
            int width,
            int height
    ) {

        if (!running.get()) {
            frame.close();
            return;
        }

        try {

            /*
             * First frame, or a resolution/orientation change.
             */
            if (
                    dynamicTexture == null
                            || textureWidth != width
                            || textureHeight != height
            ) {

                if (dynamicTexture != null) {
                    dynamicTexture.close();
                    dynamicTexture = null;
                }

                dynamicTexture =
                        new DynamicTexture(
                                () -> "phone_display",
                                frame
                        );

                Minecraft.getInstance()
                        .getTextureManager()
                        .register(
                                PHONE_TEXTURE_ID,
                                dynamicTexture
                        );

                textureWidth = width;
                textureHeight = height;

                textureReady = true;

                System.out.println(
                        "[PhoneScreen] Texture resized to "
                                + width
                                + "x"
                                + height
                );

                return;
            }

            /*
             * Same resolution:
             * replace the image and upload it.
             */
            dynamicTexture.setPixels(frame);
            dynamicTexture.upload();

        } catch (Exception e) {

            frame.close();

            System.err.println(
                    "[PhoneScreen] Failed to upload frame:"
            );

            e.printStackTrace();
        }
    }

    // -----------------------------------------------------------------
    // ADB SCROLL
    // -----------------------------------------------------------------

    public void scrollDown() {
        swipePhone(true);
    }

    /** Sends one phone swipe in the opposite direction from the repeated X gesture. */
    public void scrollUp() {
        swipePhone(false);
    }

    private void swipePhone(boolean scrollDown) {

        if (!running.get()) {
            return;
        }

        String adb = findAdbExecutable();
        if (adb == null) return;
        String deviceSerial = activeDeviceSerial;
        if (deviceSerial == null) return;

        int width = deviceWidth;
        int height = deviceHeight;
        if (width <= 0 || height <= 0) return;

        // Keep the gesture centered and apply the configurable screen-height range.
        int x = width / 2;
        int halfRange = swipeRangePercent / 2;
        int startPercent = scrollDown ? 50 + halfRange : 50 - halfRange;
        int endPercent = scrollDown ? 50 - halfRange : 50 + halfRange;
        int startY = height * startPercent / 100;
        int endY = height * endPercent / 100;
        try {
            new ProcessBuilder(
                    adb, "-s", deviceSerial, "shell", "input", "swipe",
                    Integer.toString(x), Integer.toString(startY),
                    Integer.toString(x), Integer.toString(endY), "140"
            ).redirectErrorStream(true).start().onExit();

        } catch (Exception e) {

            System.err.println(
                    "[PhoneScreen] Failed to send "
                            + "ADB swipe:"
            );

            e.printStackTrace();
        }
    }

    public void setSwipeRangePercent(int percent) {
        swipeRangePercent = Math.max(10, Math.min(90, percent));
    }

    public int getSwipeRangePercent() {
        return swipeRangePercent;
    }

    /** Locates platform-tools adb, including the copy distributed with scrcpy. */
    private static String findAdbExecutable() {
        String cached = cachedAdbExecutable;
        if (cached != null && Files.isRegularFile(Path.of(cached))) return cached;

        String executableName = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "adb.exe" : "adb";
        java.util.LinkedHashSet<Path> candidates = new java.util.LinkedHashSet<>();
        candidates.add(Path.of("C:\\scrcpy", executableName));

        for (String variable : new String[]{"ANDROID_PLATFORM_TOOLS", "ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
            String value = System.getenv(variable);
            if (value != null && !value.isBlank()) {
                Path configured = Path.of(value);
                candidates.add(variable.equals("ANDROID_PLATFORM_TOOLS") ? configured.resolve(executableName)
                        : configured.resolve("platform-tools").resolve(executableName));
            }
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) candidates.add(Path.of(localAppData, "Android", "Sdk", "platform-tools", executableName));
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            candidates.add(Path.of(userHome, "AppData", "Local", "Android", "Sdk", "platform-tools", executableName));
            candidates.add(Path.of(userHome, "scrcpy", executableName));
            candidates.add(Path.of(userHome, "Downloads", "platform-tools", executableName));
            candidates.add(Path.of(userHome, "Desktop", "platform-tools", executableName));
            addToolFolders(candidates, Path.of(userHome, "Desktop"), executableName);
            addToolFolders(candidates, Path.of(userHome, "Downloads"), executableName);
        }
        candidates.add(Path.of("C:\\platform-tools", executableName));

        String pathValue = System.getenv("PATH");
        if (pathValue != null) {
            for (String directory : pathValue.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (directory.isBlank()) continue;
                Path dir = Path.of(directory);
                candidates.add(dir.resolve(executableName));
                // The official Windows scrcpy archive includes adb.exe beside scrcpy.exe.
                String scrcpyName = executableName.equals("adb.exe") ? "scrcpy.exe" : "scrcpy";
                Path scrcpy = dir.resolve(scrcpyName);
                if (Files.isRegularFile(scrcpy)) candidates.add(dir.resolve(executableName));
            }
        }

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                cachedAdbExecutable = candidate.toAbsolutePath().toString();
                return cachedAdbExecutable;
            }
        }
        return null;
    }

    private static void addToolFolders(java.util.Set<Path> candidates, Path parent, String executableName) {
        if (!Files.isDirectory(parent)) return;
        try (var folders = Files.list(parent)) {
            folders.filter(Files::isDirectory)
                    .filter(folder -> {
                        String name = folder.getFileName().toString().toLowerCase();
                        return name.startsWith("scrcpy") || name.equals("platform-tools");
                    })
                    .map(folder -> folder.resolve(executableName))
                    .forEach(candidates::add);
        } catch (java.io.IOException e) {
            System.err.println("[PhoneScreen] Could not search for ADB in " + parent + ": " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    // STATE
    // -----------------------------------------------------------------

    public static Identifier getPhoneTextureId() {
        return PHONE_TEXTURE_ID;
    }

    public boolean hasTexture() {
        return textureReady;
    }

    public int getStreamWidth() {
        return streamWidth;
    }

    public int getStreamHeight() {
        return streamHeight;
    }

    // -----------------------------------------------------------------
    // STOP
    // -----------------------------------------------------------------

    public void stop() {

        if (!running.compareAndSet(true, false)) {
            return;
        }

        Socket socket = streamSocket;
        streamSocket = null;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        destroyCaptureProcess();
        CapturedFrame pending = pendingFrame.getAndSet(null);
        if (pending != null) pending.image().close();

        Minecraft.getInstance().execute(() -> {

            if (dynamicTexture != null) {

                dynamicTexture.close();
                dynamicTexture = null;
            }

            textureReady = false;

            textureWidth = 0;
            textureHeight = 0;
        });

        executor.shutdownNow();
    }

    private void destroyCaptureProcess() {

        Process process =
                captureProcess;

        captureProcess = null;

        if (
                process != null
                        && process.isAlive()
        ) {

            process.destroy();

            try {

                if (
                        !process.waitFor(
                                500,
                                TimeUnit.MILLISECONDS
                        )
                ) {
                    process.destroyForcibly();
                }

            } catch (InterruptedException e) {

                Thread.currentThread()
                        .interrupt();

                process.destroyForcibly();
            }
        }
    }

    private record CapturedFrame(NativeImage image, int width, int height) {
    }

}
