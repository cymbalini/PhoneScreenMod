package com.phonescreen.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class PhoneScreenClient implements ClientModInitializer {

	private static KeyMapping scrollDownKey;
	private static KeyMapping scrollUpKey;
	private static KeyMapping findNextButtonKey;
	private static KeyMapping togglePhoneKey;

	private static PhoneStreamManager streamManager;
	private static boolean openGuiRequested;

	private static boolean showPhoneOverlay = true;
	private static int phoneX = Integer.MIN_VALUE;
	private static int phoneY = 10;
	private static int phoneWidth = 180;
	private static final int SIZE_STEP = 20;
	private static final int MIN_PHONE_WIDTH = 80;
	private static final int MAX_PHONE_WIDTH = 400;
	private static int scrollHoldTicks;
	private static long lastScrollAtNanos;
	private static final long SCROLL_START_INTERVAL_NANOS = 450_000_000L;
	// 300 ms is 1.5x the initial 450 ms repeat rate.
	private static final long SCROLL_MIN_INTERVAL_NANOS = 300_000_000L;
	private static final int SCROLL_ACCELERATION_TICKS = 20 * 6;

	private static final Identifier PHONE_OVERLAY_ID =
			Identifier.fromNamespaceAndPath("phonescreen", "phone_overlay");

	@Override
	public void onInitializeClient() {
		streamManager = new PhoneStreamManager();
		loadPosition();

		// ------------------------------------------------------------
		// Keybindings
		// ------------------------------------------------------------

		scrollDownKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.phonescreen.scroll_down",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_X,
				KeyMapping.Category.MISC
		));
		scrollUpKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.phonescreen.scroll_up",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_Z,
				KeyMapping.Category.MISC
		));
		findNextButtonKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.phonescreen.find_next",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_N,
				KeyMapping.Category.MISC
		));
		registerCommands();

		togglePhoneKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.phonescreen.toggle",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_P,
				KeyMapping.Category.MISC
		));
		// ------------------------------------------------------------
		// HUD
		// ------------------------------------------------------------
		//
		// Minecraft 26.1 uses Fabric's HudElementRegistry instead of
		// injecting into Gui.render().
		//
		// Placing it after the crosshair means it behaves like a normal
		// HUD element and respects the vanilla HUD visibility setting.
		//

		HudElementRegistry.attachElementAfter(
				VanillaHudElements.SUBTITLES,
				PHONE_OVERLAY_ID,
				(guiGraphics, deltaTracker) -> {
					renderOverlay(guiGraphics);
				}
		);

		// ------------------------------------------------------------
		// Start phone stream
		// ------------------------------------------------------------

		streamManager.start();

		// ------------------------------------------------------------
		// Key handling
		// ------------------------------------------------------------

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (openGuiRequested) {
				openGuiRequested = false;
				client.setScreen(new PhoneScreenEditor());
			}
			while (findNextButtonKey.consumeClick()) {
				streamManager.findAndPressNext();
			}
			while (scrollUpKey.consumeClick()) {
				streamManager.scrollUp();
			}

			if (scrollDownKey.isDown() && client.player != null && client.screen == null) {
				scrollHoldTicks++;
				long interval = SCROLL_START_INTERVAL_NANOS
						- (SCROLL_START_INTERVAL_NANOS - SCROLL_MIN_INTERVAL_NANOS)
						* Math.min(scrollHoldTicks, SCROLL_ACCELERATION_TICKS) / SCROLL_ACCELERATION_TICKS;
				long now = System.nanoTime();
				if (lastScrollAtNanos == 0 || now - lastScrollAtNanos >= interval) {
					streamManager.scrollDown();
					lastScrollAtNanos = now;
				}
			} else {
				scrollHoldTicks = 0;
				lastScrollAtNanos = 0;
			}

			while (togglePhoneKey.consumeClick()) {
				showPhoneOverlay = !showPhoneOverlay;
			}

		});
	}

	private static void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommands.literal("phonescreen")
					.then(ClientCommands.literal("wifi")
							.then(ClientCommands.argument("address", StringArgumentType.greedyString())
									.executes(context -> {
									String address = StringArgumentType.getString(context, "address");
									if (!address.equalsIgnoreCase("off") && !isValidWirelessAddress(address)) {
										context.getSource().sendFeedback(Component.literal(
											"Use /phonescreen wifi <IP:PORT>, for example /phonescreen wifi 192.168.1.50:41231"));
										return 0;
									}
									String savedAddress = address.equalsIgnoreCase("off") ? "" : address;
									if (!saveWirelessAddress(savedAddress)) {
										context.getSource().sendFeedback(Component.literal("Could not save PhoneScreen Wi-Fi address."));
										return 0;
									}
									String message = savedAddress.isEmpty()
											? "PhoneScreen Wi-Fi address cleared. Restart Minecraft to use automatic USB selection."
											: "PhoneScreen Wi-Fi address set to " + savedAddress + ". Restart Minecraft to connect over Wi-Fi.";
									context.getSource().sendFeedback(Component.literal(message));
									return 1;
								})))
					.then(ClientCommands.literal("swipe")
							.then(ClientCommands.argument("percent", IntegerArgumentType.integer(10, 90))
									.executes(context -> {
									int percent = IntegerArgumentType.getInteger(context, "percent");
									if (!saveSwipeRange(percent)) {
										context.getSource().sendFeedback(Component.literal("Could not save PhoneScreen swipe range."));
										return 0;
									}
									streamManager.setSwipeRangePercent(percent);
									context.getSource().sendFeedback(Component.literal(
											"PhoneScreen swipe range set to " + percent + "% of screen height."));
									return 1;
								})))
					.then(ClientCommands.literal("gui")
							.executes(context -> {
								// Chat closes itself just after a client command runs. Open
								// the editor on the next tick so that close cannot replace it.
								openGuiRequested = true;
								return 1;
							}))));
	}

	private static boolean isValidWirelessAddress(String address) {
		int colon = address.lastIndexOf(':');
		if (colon <= 0 || colon == address.length() - 1) return false;
		String host = address.substring(0, colon);
		if (!host.matches("[A-Za-z0-9.-]+")) return false;
		try {
			int port = Integer.parseInt(address.substring(colon + 1));
			return port >= 1 && port <= 65535;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static boolean saveWirelessAddress(String address) {
		Path file = positionFile();
		Properties properties = new Properties();
		try {
			if (Files.isRegularFile(file)) {
				try (var reader = Files.newBufferedReader(file)) { properties.load(reader); }
			}
			properties.setProperty("wirelessAddress", address);
			Files.createDirectories(file.getParent());
			try (var writer = Files.newBufferedWriter(file)) {
				properties.store(writer, "PhoneScreen settings");
			}
			return true;
		} catch (IOException e) {
			System.err.println("[PhoneScreen] Could not save wireless ADB address: " + e.getMessage());
			return false;
		}
	}

	private static boolean saveSwipeRange(int percent) {
		Path file = positionFile();
		Properties properties = new Properties();
		try {
			if (Files.isRegularFile(file)) {
				try (var reader = Files.newBufferedReader(file)) { properties.load(reader); }
			}
			properties.setProperty("swipeRangePercent", Integer.toString(percent));
			Files.createDirectories(file.getParent());
			try (var writer = Files.newBufferedWriter(file)) {
				properties.store(writer, "PhoneScreen settings");
			}
			return true;
		} catch (IOException e) {
			System.err.println("[PhoneScreen] Could not save swipe range: " + e.getMessage());
			return false;
		}
	}

	/**
	 * Renders the phone screen on the Minecraft HUD.
	 */
	public static void renderOverlay(GuiGraphicsExtractor guiGraphics) {
		if (!showPhoneOverlay) {
			return;
		}
		renderPhoneImage(guiGraphics);
	}

	static void renderEditorPreview(GuiGraphicsExtractor guiGraphics) {
		renderPhoneImage(guiGraphics);
	}

	private static void renderPhoneImage(GuiGraphicsExtractor guiGraphics) {

		Identifier textureId = PhoneStreamManager.getPhoneTextureId();

		// Don't render until the first frame has actually arrived.
		if (streamManager == null || !streamManager.hasTexture()) {
			return;
		}

		int screenWidth = guiGraphics.guiWidth();
		int streamWidth = streamManager.getStreamWidth();
		int streamHeight = streamManager.getStreamHeight();
		if (streamWidth <= 0 || streamHeight <= 0) return;
		int phoneHeight = Math.max(1, (int) Math.round(phoneWidth * (double) streamHeight / streamWidth));
		if (phoneX == Integer.MIN_VALUE) phoneX = screenWidth - phoneWidth - 10;
		clampPosition(screenWidth, guiGraphics.guiHeight());

		guiGraphics.blit(
				textureId,
				phoneX,
				phoneY,
				phoneX + phoneWidth,
				phoneY + phoneHeight,
				0.0F,
				1.0F,
				0.0F,
				1.0F
		);
	}

	static int getPhoneX() { return phoneX; }
	static int getPhoneY() { return phoneY; }
	static int getPhoneWidth() { return phoneWidth; }
	static int getPhoneHeight() {
		int streamWidth = streamManager == null ? 0 : streamManager.getStreamWidth();
		int streamHeight = streamManager == null ? 0 : streamManager.getStreamHeight();
		return streamWidth > 0 && streamHeight > 0
				? Math.max(1, (int) Math.round(phoneWidth * (double) streamHeight / streamWidth))
				: phoneWidth * 2;
	}

	static void movePhoneHudTo(int x, int y) {
		phoneX = x;
		phoneY = y;
		clampPosition(Minecraft.getInstance().getWindow().getGuiScaledWidth(),
				Minecraft.getInstance().getWindow().getGuiScaledHeight());
	}

	static void resizePhoneHud(int delta) {
		phoneWidth = Math.max(MIN_PHONE_WIDTH, Math.min(MAX_PHONE_WIDTH, phoneWidth + delta));
		clampPosition(Minecraft.getInstance().getWindow().getGuiScaledWidth(),
				Minecraft.getInstance().getWindow().getGuiScaledHeight());
	}

	static void saveHudLayout() { savePosition(); }

	private static Path positionFile() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config/phonescreen.properties");
	}

	private static void loadPosition() {
		Path file = positionFile();
		if (!Files.isRegularFile(file)) return;
		Properties properties = new Properties();
		try (var reader = Files.newBufferedReader(file)) {
			properties.load(reader);
			phoneX = Integer.parseInt(properties.getProperty("x", Integer.toString(Integer.MIN_VALUE)));
			phoneY = Integer.parseInt(properties.getProperty("y", "10"));
			phoneWidth = Math.max(MIN_PHONE_WIDTH, Math.min(MAX_PHONE_WIDTH,
					Integer.parseInt(properties.getProperty("width", "180"))));
			int swipeRange = Integer.parseInt(properties.getProperty("swipeRangePercent", "20"));
			streamManager.setSwipeRangePercent(swipeRange);
		} catch (IOException | NumberFormatException e) {
			System.err.println("[PhoneScreen] Could not load HUD position: " + e.getMessage());
		}
	}

	private static void savePosition() {
		Path file = positionFile();
        try {
            Files.createDirectories(file.getParent());
            Properties properties = new Properties();
            if (Files.isRegularFile(file)) {
                try (var reader = Files.newBufferedReader(file)) {
                    properties.load(reader);
                }
            }
            properties.setProperty("x", Integer.toString(phoneX));
			properties.setProperty("y", Integer.toString(phoneY));
            properties.setProperty("width", Integer.toString(phoneWidth));
			properties.setProperty("swipeRangePercent", Integer.toString(streamManager.getSwipeRangePercent()));
			try (var writer = Files.newBufferedWriter(file)) { properties.store(writer, "PhoneScreen HUD position"); }
		} catch (IOException e) {
			System.err.println("[PhoneScreen] Could not save HUD position: " + e.getMessage());
		}
	}

	private static void clampPosition(int screenWidth, int screenHeight) {
		if (phoneX == Integer.MIN_VALUE) phoneX = screenWidth - phoneWidth - 10;
		int streamWidth = streamManager == null ? 0 : streamManager.getStreamWidth();
		int streamHeight = streamManager == null ? 0 : streamManager.getStreamHeight();
		int phoneHeight = streamWidth > 0
				? (int) Math.round(phoneWidth * (double) streamHeight / streamWidth)
				: 240;
		phoneX = Math.max(0, Math.min(phoneX, Math.max(0, screenWidth - phoneWidth)));
		phoneY = Math.max(0, Math.min(phoneY, Math.max(0, screenHeight - phoneHeight)));
	}

	public static PhoneStreamManager getStreamManager() {
		return streamManager;
	}
}
