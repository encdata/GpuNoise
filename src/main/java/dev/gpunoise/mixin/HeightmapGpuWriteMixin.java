package dev.gpunoise.mixin;

import dev.gpunoise.GpuHeightmapAccess;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.function.Predicate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Heightmap updates in NoiseBasedChunkGenerator are strictly top-to-bottom.
 * On a new heightmap, the first matching state in each column is therefore
 * the final value; avoid the normal per-block scan and mutable-position path.
 */
@Mixin(Heightmap.class)
abstract class HeightmapGpuWriteMixin implements GpuHeightmapAccess {
    @Shadow @Final private BitStorage data;
    @Shadow @Final private Predicate<BlockState> isOpaque;

    @Unique private boolean gpunoise$emptyAtStart;
    @Unique private boolean[] gpunoise$written;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void gpunoise$checkEmpty(CallbackInfo callback) {
        boolean empty = true;
        for (int i = 0; i < data.getSize(); i++) {
            if (data.get(i) != 0) {
                empty = false;
                break;
            }
        }
        gpunoise$emptyAtStart = empty;
        gpunoise$written = empty ? new boolean[256] : null;
    }

    @Override
    public boolean gpunoise$tryDescendingWrite(int x, int y, int z, BlockState state) {
        if (!gpunoise$emptyAtStart || gpunoise$written == null) return false;
        int index = x + z * 16;
        if (gpunoise$written[index]) return true;
        if (!isOpaque.test(state)) return true;
        gpunoise$setHeight(x, z, y + 1);
        gpunoise$written[index] = true;
        return true;
    }

    @org.spongepowered.asm.mixin.gen.Invoker("setHeight")
    protected abstract void gpunoise$setHeight(int x, int z, int value);
}
