# PhoneScreen

PhoneScreen mirrors the Android display in the Minecraft HUD using scrcpy's
low-latency H.264 video stream. It connects to the scrcpy server bundled beside
`scrcpy.exe` and decodes the stream with FFmpeg. ADB is used to start scrcpy,
forward its local video socket, and send the phone scroll action.

## Controls

- **P** — show or hide the phone HUD.
- **`/phonescreen gui`** — open the HUD editor. Drag the phone to move it and
  scroll the mouse wheel to resize it. Press **Esc** or click **Done** to save.
- **Hold X** — scroll the phone; the repeated gestures speed up over about six
  seconds, reaching 1.5× the starting rate.
- **Z** — swipe the phone in the opposite direction.
- **N** — find a visible **Next** label on the phone screen and tap it.

The keybinds can be changed under **Options → Controls → Key Binds** in the
**Miscellaneous** category. HUD position and size are saved in
`config/phonescreen.properties` in the Minecraft game directory.

Use `/phonescreen swipe <10-90>` to set the swipe distance as a percentage of
the phone display height; the default is 20%. The setting is saved and applies
immediately to both X and Z. For example, `/phonescreen swipe 40` sends swipes
covering 40% of the screen height.

## Wi-Fi setup

PhoneScreen connects over ADB Wi-Fi when `wirelessAddress` is set in
`config/phonescreen.properties`. With this setting present, it uses that Wi-Fi
device directly and does not fall back to USB. Leave it blank to use automatic
USB device selection.

1. Install the official Windows scrcpy ZIP and keep `adb.exe`, `scrcpy.exe`, and
   `scrcpy-server` together. PhoneScreen searches `C:\scrcpy`, common scrcpy
   folders on the Desktop and Downloads, the Android SDK, and PATH.
2. Connect the phone and PC to the same Wi-Fi network.
3. On Android, open **Developer options → Wireless debugging** and enable it.
   Choose **Pair device with pairing code**. Note the IP address and pairing
   port shown in that dialog.
4. In IntelliJ's Terminal, run `adb pair PHONE_IP:PAIRING_PORT`, then enter the
   pairing code shown on the phone. For example: `adb pair 192.168.1.50:37123`.
   If `adb` is not recognized, run `adb.exe` from the folder where you
   extracted scrcpy, or use its full path in the command.
5. In the Wireless debugging screen, note the main IP address and port (this
   port is different from the pairing port). In Minecraft chat, enter
   `/phonescreen wifi PHONE_IP:PORT`, for example
   `/phonescreen wifi 192.168.1.50:41231`.
   Use `/phonescreen wifi off` to clear the address and return to automatic USB
   selection. The command saves the address in
   `config/phonescreen.properties` in Minecraft's game directory.
6. Restart Minecraft. PhoneScreen will run `adb connect` to that address and
   stream the phone over Wi-Fi. Android may disable Wireless debugging when it
   leaves the network; if that happens, enable it again and update the address
   if Android assigned a new port.

The phone and PC must remain on the same network. Wireless debugging pairing is
remembered by Android, so you normally only need to repeat the pairing step if
you revoke the pairing or reset developer settings.

The scrcpy stream requests up to 60 frames per second at up to 1600 pixels on
its longest dimension, with a 12 Mbps video bitrate. Actual quality and frame
rate depend on the phone and connection.

## Troubleshooting

- **No phone image:** Look in the game log for `[PhoneScreen]` messages. For
  Wi-Fi, check that `wirelessAddress` matches the current Wireless debugging
  IP and port, the phone is on the same Wi-Fi, and `adb devices` lists that
  address as `device`. Also check that `scrcpy.exe`, `scrcpy-server`, and
  `adb.exe` are from the same extracted release folder.
- **HUD is hidden:** Press **P** to toggle it back on. Minecraft's **F1** HUD
  hiding also hides the phone view.
- **The image is too large or small:** Run `/phonescreen gui` and use the mouse
  wheel to resize it.
- **X or Z does nothing:** Keep the phone connected, unlocked, and authorized
  for ADB (USB or Wi-Fi). X sends repeated swipes near the middle of the phone
  display while held; Z sends one swipe in the opposite direction.
- **N does not find the button:** Make sure the **Next** label is visible and
  readable in the phone stream. N scans the current phone frame and taps the
  center of the recognized word; unusual fonts, tiny text, or rotated screens
  may prevent OCR from recognizing it.
- **Some apps show a black screen:** Android apps that mark their content secure
  can block screen capture.

## Development

This project targets Minecraft 26.1.2, Fabric Loader 0.19.5, and Java 25 or
newer. The FFmpeg Java bindings and Windows x64 native libraries are included
as nested Fabric dependencies.

## License

This template is available under the CC0 license. Feel free to learn from it
and incorporate it in your own projects.
