package com.phonescreen.client;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.tesseract.TessBaseAPI;
import org.bytedeco.tesseract.global.tesseract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** OCR helper used only when the player asks to find the Next button. */
final class NextButtonRecognizer {
    private static final String MODEL_RESOURCE = "/assets/phonescreen/tessdata/eng.traineddata";
    private static final String MODEL_FILE = "eng.traineddata";

    private NextButtonRecognizer() { }

    static Point findNext(ByteBuffer source, int width, int height, int stride, int channels) throws IOException {
        if (channels != 3 && channels != 4) return null;
        byte[] rgb = new byte[width * height * 3];
        for (int y = 0; y < height; y++) {
            int sourceRow = y * stride;
            int targetRow = y * width * 3;
            for (int x = 0; x < width; x++) {
                int sourceOffset = sourceRow + x * channels;
                int targetOffset = targetRow + x * 3;
                // FFmpegFrameGrabber supplies BGR(A); Tesseract expects RGB.
                rgb[targetOffset] = source.get(sourceOffset + 2);
                rgb[targetOffset + 1] = source.get(sourceOffset + 1);
                rgb[targetOffset + 2] = source.get(sourceOffset);
            }
        }

        Path modelDirectory = ensureModel();
        try (TessBaseAPI api = new TessBaseAPI()) {
            if (api.Init(modelDirectory.toString(), "eng") != 0) {
                throw new IOException("Tesseract could not initialize its English model.");
            }
            api.SetPageSegMode(tesseract.PSM_SPARSE_TEXT);
            api.SetImage(rgb, width, height, 3, width * 3);
            BytePointer result = api.GetTSVText(0);
            try {
                String tsv = result == null ? "" : result.getString();
                Point point = findBestMatch(tsv, width, height);
                if (point == null) {
                    // Retry with normal page segmentation in case OCR grouped
                    // the button label into a page text block.
                    api.SetPageSegMode(tesseract.PSM_AUTO);
                    api.SetImage(rgb, width, height, 3, width * 3);
                    BytePointer fallback = api.GetTSVText(0);
                    try {
                        tsv = fallback == null ? "" : fallback.getString();
                        point = findBestMatch(tsv, width, height);
                    } finally {
                        if (fallback != null) tesseract.TessDeleteText(fallback);
                    }
                }
                if (point == null) {
                    System.out.println("[PhoneScreen] OCR did not match Next. Recognized words: " + summarizeWords(tsv));
                }
                return point;
            } finally {
                if (result != null) tesseract.TessDeleteText(result);
                api.End();
            }
        }
    }

    private static Path ensureModel() throws IOException {
        Path directory = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config/phonescreen/tessdata");
        Path model = directory.resolve(MODEL_FILE);
        if (Files.isRegularFile(model) && Files.size(model) > 1_000_000) return directory;
        Files.createDirectories(directory);
        try (InputStream input = NextButtonRecognizer.class.getResourceAsStream(MODEL_RESOURCE)) {
            if (input == null) throw new IOException("Bundled English OCR model is missing.");
            Files.copy(input, model, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return directory;
    }

    private static Point findBestMatch(String tsv, int imageWidth, int imageHeight) {
        Point best = null;
        float bestConfidence = 30.0F;
        for (String line : tsv.split("\\R")) {
            String[] columns = line.split("\\t", 12);
            if (columns.length < 12 || !columns[0].equals("5")) continue;
            String word = columns[11].replaceAll("[^A-Za-z]", "");
            if (!word.equalsIgnoreCase("next")) continue;
            try {
                float confidence = Float.parseFloat(columns[10]);
                int left = Integer.parseInt(columns[6]);
                int top = Integer.parseInt(columns[7]);
                int width = Integer.parseInt(columns[8]);
                int height = Integer.parseInt(columns[9]);
                if (confidence > bestConfidence && width > 0 && height > 0
                        && left >= 0 && top >= 0 && left + width <= imageWidth && top + height <= imageHeight) {
                    bestConfidence = confidence;
                    best = new Point(left + width / 2, top + height / 2, confidence);
                }
            } catch (NumberFormatException ignored) { }
        }
        return best;
    }

    private static String summarizeWords(String tsv) {
        StringBuilder words = new StringBuilder();
        for (String line : tsv.split("\\R")) {
            String[] columns = line.split("\\t", 12);
            if (columns.length < 12 || !columns[0].equals("5") || columns[11].isBlank()) continue;
            if (!words.isEmpty()) words.append(' ');
            words.append(columns[11]);
            if (words.length() >= 240) {
                words.append("...");
                break;
            }
        }
        return words.isEmpty() ? "<none>" : words.toString();
    }

    record Point(int x, int y, float confidence) { }
}
