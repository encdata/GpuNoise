package dev.gpunoise.mixin;

import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.world.level.levelgen.Beardifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Beardifier.class)
public interface BeardifierAccessor {
    @Accessor("pieceIterator")
    ObjectListIterator<Beardifier.Rigid> gpunoise$pieceIterator();

    @Accessor("junctionIterator")
    ObjectListIterator<net.minecraft.world.level.levelgen.structure.pools.JigsawJunction>
    gpunoise$junctionIterator();

    @Accessor("BEARD_KERNEL")
    static float[] gpunoise$kernel() { throw new AssertionError(); }
}
