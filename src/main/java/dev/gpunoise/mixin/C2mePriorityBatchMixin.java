package dev.gpunoise.mixin;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Executor;

/** Coalesces repeated C2ME priority changes without modifying C2ME itself. */
@Mixin(targets = "com.ishland.c2me.base.common.scheduler.SchedulingManager", remap = false)
public abstract class C2mePriorityBatchMixin {
    @Shadow private boolean consolidatingLevelUpdates;
    @Shadow @Final private Executor executor;
    @Shadow abstract void updatePriorityFromLevel0(long pos, int level);

    @Unique private Long2IntOpenHashMap gpunoise$pendingPriorities = new Long2IntOpenHashMap();
    @Unique private boolean gpunoise$batching;

    @Inject(method = "updatePriorityFromLevelOnMain", at = @At("HEAD"), cancellable = true)
    private void gpunoise$coalescePriorityUpdate(long pos, int level, CallbackInfo ci) {
        if (!this.gpunoise$batching && !this.consolidatingLevelUpdates) return;
        this.gpunoise$pendingPriorities.put(pos, level);
        ci.cancel();
    }

    @Inject(method = "setConsolidatingLevelUpdates", at = @At("HEAD"), cancellable = true)
    private void gpunoise$flushPriorityBatch(boolean value, CallbackInfo ci) {
        if (value) {
            this.gpunoise$batching = true;
            this.consolidatingLevelUpdates = true;
            ci.cancel();
            return;
        }

        this.gpunoise$batching = false;
        this.consolidatingLevelUpdates = false;
        if (this.gpunoise$pendingPriorities.isEmpty()) {
            ci.cancel();
            return;
        }

        Long2IntOpenHashMap batch = this.gpunoise$pendingPriorities;
        this.gpunoise$pendingPriorities = new Long2IntOpenHashMap();
        this.executor.execute(() -> {
            for (var entry : batch.long2IntEntrySet()) {
                this.updatePriorityFromLevel0(entry.getLongKey(), entry.getIntValue());
            }
        });
        ci.cancel();
    }
}
