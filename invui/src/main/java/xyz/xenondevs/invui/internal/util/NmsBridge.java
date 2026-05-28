package xyz.xenondevs.invui.internal.util;

import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.inventory.ItemStack;

import java.io.DataInputStream;
import java.io.InputStream;

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
     * Creates a new {@link ItemStack} of {@code targetType}, applying the data
     * component patch of {@code original} on top of the target type's default
     * components. This makes the result look like {@code original} while being a
     * different item type.
     * <p>
     * Replaces the data-component-registry iteration used on newer API versions,
     * which is unavailable on 1.21.1.
     */
    public static ItemStack asType(ItemStack original, Material targetType) {
        var nmsOriginal = CraftItemStack.asNMSCopy(original);
        var targetItem = CraftMagicNumbers.getItem(targetType);
        var result = new net.minecraft.world.item.ItemStack(targetItem, original.getAmount());
        result.applyComponents(nmsOriginal.getComponentsPatch());
        return CraftItemStack.asBukkitCopy(result);
    }
}
