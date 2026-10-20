package dev.gpunoise.mixin;

import dev.gpunoise.GpuCompileStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps slow Vulkan setup visible while Minecraft is rendering a loading screen. */
@Mixin(Screen.class)
public abstract class ClientCompileStatusMixin {
    @Shadow @Final protected Minecraft minecraft;
    @Shadow public int width;
    @Shadow public int height;

    @Inject(method = "render", at = @At("TAIL"))
    private void gpunoise$renderCompileStatus(GuiGraphics graphics, int mouseX, int mouseY,
                                               float partialTick, CallbackInfo ci) {
        GpuCompileStatus.Snapshot status = GpuCompileStatus.current();
        if (status == null) return;

        int left = 12;
        int top = Math.max(12, height - 48);
        int right = Math.min(width - 12, 420);
        graphics.fill(left, top, right, top + 34, 0xC0101010);
        long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - status.startedAtMillis()) / 1000L);
        String elapsed = String.format(java.util.Locale.ROOT, "%02d:%02d",
                elapsedSeconds / 60L, elapsedSeconds % 60L);
        graphics.drawString(minecraft.font,
                Component.literal("GPU Noise: Compiling terrain (" + elapsed + ")"),
                left + 10, top + 7, 0xFFFFFFFF);
        graphics.drawString(minecraft.font,
                Component.literal(status.message()), left + 10, top + 20, 0xFFB8D8FF);
    }
}
