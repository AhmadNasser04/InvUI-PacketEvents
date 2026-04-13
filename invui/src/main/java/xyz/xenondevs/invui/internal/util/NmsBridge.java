package xyz.xenondevs.invui.internal.util;

import com.mojang.serialization.Dynamic;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.component.BundleContents;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Narrow NMS-backed helper for two operations PacketEvents does not support
 */
public final class NmsBridge {

    private NmsBridge() {}

    /**
     * Reads one item from the v5 binary stream, applying datafix migration
     * from {@code sourceDataVersion} up to the running server's data version,
     * and returns it as a Bukkit {@link ItemStack}.
     */
    public static ItemStack readV5Item(InputStream in, int sourceDataVersion) {
        try {
            CompoundTag tag = NbtIo.read(new DataInputStream(in));
            int currentVersion = CraftMagicNumbers.INSTANCE.getDataVersion();

            Dynamic<Tag> migrated = DataFixers.getDataFixer().update(
                References.ITEM_STACK,
                new Dynamic<>(NbtOps.INSTANCE, tag),
                sourceDataVersion,
                currentVersion
            );

            var registry = MinecraftServer.getServer().registryAccess();
            var ctx = registry.createSerializationContext(NbtOps.INSTANCE);
            var nms = net.minecraft.world.item.ItemStack.CODEC
                .parse(ctx, migrated.getValue())
                .resultOrPartial()
                .orElseThrow(() -> new IllegalStateException(
                    "Failed to decode v5 item (sourceDataVersion=" + sourceDataVersion + ")"));

            return CraftItemStack.asBukkitCopy(nms);
        } catch (Exception e) {
            throw new RuntimeException("NmsBridge: failed to migrate v5 item", e);
        }
    }

    /**
     * Updates the selected-item index of a bundle in place. No-op if the
     * stack is not a bundle.
     */
    public static void setBundleSelectedIndex(ItemStack bundle, int index) {
        var nms = CraftItemStack.unwrap(bundle);
        var contents = nms.get(DataComponents.BUNDLE_CONTENTS);
        if (contents == null) return;

        var mutable = new BundleContents.Mutable(contents);
        mutable.toggleSelectedItem(index);
        nms.set(DataComponents.BUNDLE_CONTENTS, mutable.toImmutable());
    }

    /**
     * Removes the item at the bundle's selected index and returns it. Null
     * if the bundle is empty or has no contents component.
     */
    public static @Nullable ItemStack takeBundleSelected(ItemStack bundle) {
        var nms = CraftItemStack.unwrap(bundle);
        var contents = nms.get(DataComponents.BUNDLE_CONTENTS);
        if (contents == null || contents.isEmpty()) return null;

        int index = Math.clamp(contents.getSelectedItemIndex(), 0, contents.size() - 1);
        var items = new ArrayList<>(contents.items());
        var taken = items.remove(index);
        nms.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(items));
        return CraftItemStack.asBukkitCopy(taken.create());
    }
}
