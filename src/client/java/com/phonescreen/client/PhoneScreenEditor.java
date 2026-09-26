package com.phonescreen.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** In-game editor for positioning and sizing the phone HUD. */
final class PhoneScreenEditor extends Screen {
    private int dragOffsetX;
    private int dragOffsetY;
    private boolean draggingPhone;

    PhoneScreenEditor() {
        super(Component.literal("PhoneScreen HUD Editor"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, width, height, 0xA0000000);

        int x = PhoneScreenClient.getPhoneX();
        int y = PhoneScreenClient.getPhoneY();
        int phoneWidth = PhoneScreenClient.getPhoneWidth();
        int phoneHeight = PhoneScreenClient.getPhoneHeight();
        if (x == Integer.MIN_VALUE) {
            x = width - phoneWidth - 10;
            PhoneScreenClient.movePhoneHudTo(x, 10);
            y = PhoneScreenClient.getPhoneY();
        }

        if (PhoneScreenClient.getStreamManager() != null
                && PhoneScreenClient.getStreamManager().hasTexture()) {
            PhoneScreenClient.renderEditorPreview(graphics);
        } else {
            graphics.fill(x, y, x + phoneWidth, y + phoneHeight, 0xFF15151F);
            graphics.centeredText(font, "Waiting for phone...", x + phoneWidth / 2, y + phoneHeight / 2, 0xFFFFFFFF);
        }
        graphics.outline(x - 1, y - 1, phoneWidth + 2, phoneHeight + 2, 0xFF55D6FF);

        graphics.fill(8, 8, width - 8, 34, 0xC0000000);
        graphics.centeredText(font, "PhoneScreen HUD Editor", width / 2, 11, 0xFFFFFFFF);
        graphics.centeredText(font, "Drag the phone to move it. Scroll anywhere to resize. Press Esc or Done to save.",
                width / 2, 22, 0xFFE0E0E0);
        graphics.text(font, "Width: " + phoneWidth + " px", 12, 42, 0xFFFFFFFF);
        int buttonX = width / 2 - 50;
        int buttonY = height - 36;
        graphics.fill(buttonX, buttonY, buttonX + 100, buttonY + 20, 0xFF34343F);
        graphics.outline(buttonX, buttonY, 100, 20, 0xFFFFFFFF);
        graphics.centeredText(font, "Done", width / 2, buttonY + 6, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int buttonX = width / 2 - 50;
        int buttonY = height - 36;
        if (event.button() == 0 && event.x() >= buttonX && event.x() <= buttonX + 100
                && event.y() >= buttonY && event.y() <= buttonY + 20) {
            onClose();
            return true;
        }
        if (event.button() == 0 && isOverPhone(event.x(), event.y())) {
            draggingPhone = true;
            dragOffsetX = (int) event.x() - PhoneScreenClient.getPhoneX();
            dragOffsetY = (int) event.y() - PhoneScreenClient.getPhoneY();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingPhone && event.button() == 0) {
            PhoneScreenClient.movePhoneHudTo((int) event.x() - dragOffsetX, (int) event.y() - dragOffsetY);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0 && draggingPhone) {
            draggingPhone = false;
            PhoneScreenClient.saveHudLayout();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount != 0) {
            int direction = verticalAmount > 0 ? 1 : -1;
            PhoneScreenClient.resizePhoneHud(direction * 20);
            PhoneScreenClient.saveHudLayout();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void onClose() {
        PhoneScreenClient.saveHudLayout();
        super.onClose();
    }

    private boolean isOverPhone(double mouseX, double mouseY) {
        int x = PhoneScreenClient.getPhoneX();
        int y = PhoneScreenClient.getPhoneY();
        return x != Integer.MIN_VALUE && mouseX >= x && mouseX <= x + PhoneScreenClient.getPhoneWidth()
                && mouseY >= y && mouseY <= y + PhoneScreenClient.getPhoneHeight();
    }
}
