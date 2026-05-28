@file:Suppress("UnstableApiUsage")

package xyz.xenondevs.invui.dsl

import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemType
import xyz.xenondevs.commons.provider.Provider
import xyz.xenondevs.commons.provider.combinedProvider
import xyz.xenondevs.commons.provider.provider
import xyz.xenondevs.invui.internal.util.ComponentUtils
import xyz.xenondevs.invui.item.ItemProvider
import xyz.xenondevs.invui.item.ItemWrapper
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Creates a reactive [Provider]-based [ItemProvider] using the DSL, starting from an empty
 * [ItemStack].
 *
 * ```
 * val myProvider = itemProvider {
 *     type by ItemType.DIAMOND_SWORD
 *     name by "<red>Fire Sword"
 *     lore by listOf("<gray>A legendary weapon")
 *     hasGlint by true
 * }
 * ```
 *
 * @see ItemProviderDsl
 */
@ExperimentalDslApi
inline fun itemProvider(itemProvider: ItemProviderDsl.() -> Unit): Provider<ItemProvider> {
    contract { callsInPlace(itemProvider, InvocationKind.EXACTLY_ONCE) }
    return ItemProviderDslImpl(provider(ItemStack.empty())).apply(itemProvider).build()
}

/**
 * Creates a reactive [Provider]-based [ItemProvider] using the DSL, starting from a reactive
 * [base] [ItemStack].
 *
 * ```
 * val baseStack: Provider<ItemStack> = ...
 * val myProvider = itemProvider(baseStack) {
 *     name by "<green>Enhanced Item"
 *     amount by 5
 * }
 * ```
 *
 * @see ItemProviderDsl
 */
@ExperimentalDslApi
inline fun itemProvider(base: Provider<ItemStack>, itemProvider: ItemProviderDsl.() -> Unit): Provider<ItemProvider> {
    contract { callsInPlace(itemProvider, InvocationKind.EXACTLY_ONCE) }
    return ItemProviderDslImpl(base).apply(itemProvider).build()
}

/**
 * Creates a reactive [Provider]-based [ItemProvider] using the DSL, starting from a static
 * [base] [ItemStack].
 *
 * ```
 * val myProvider = itemProvider(ItemStack(Material.DIAMOND)) {
 *     name by "<aqua>Shiny Diamond"
 *     amount by 3
 * }
 * ```
 *
 * @see ItemProviderDsl
 */
@ExperimentalDslApi
inline fun itemProvider(base: ItemStack, itemProvider: ItemProviderDsl.() -> Unit): Provider<ItemProvider> {
    contract { callsInPlace(itemProvider, InvocationKind.EXACTLY_ONCE) }
    return itemProvider(provider(base), itemProvider)
}

/**
 * Creates a reactive [Provider]-based [ItemProvider] using the DSL, starting from an [ItemStack]
 * of the given static [ItemType].
 *
 * ```
 * val myProvider = itemProvider(ItemType.GOLDEN_APPLE) {
 *     name by "<gold>Special Apple"
 *     hasTooltip by true
 * }
 * ```
 *
 * @see ItemProviderDsl
 */
@ExperimentalDslApi
inline fun itemProvider(type: ItemType, itemProvider: ItemProviderDsl.() -> Unit): Provider<ItemProvider> {
    contract { callsInPlace(itemProvider, InvocationKind.EXACTLY_ONCE) }
    return itemProvider(provider(type.createItemStack()), itemProvider)
}

/**
 * DSL scope for configuring an [ItemProvider] with reactive properties.
 *
 * Provides convenient properties for common item attributes like [name], [lore], and [amount].
 * Each property can be set to a static value or bound to a [Provider] for reactive updates.
 * String values for [name] and [lore] are automatically parsed as
 * [MiniMessage][net.kyori.adventure.text.minimessage.MiniMessage].
 *
 * ```
 * val myProvider = itemProvider(ItemType.DIAMOND_SWORD) {
 *     name by "<red>Fire Sword"
 *     lore by listOf("<gray>A legendary weapon", "<gray>Forged in flames")
 *     hasGlint by true
 * }
 * ```
 */
@ItemDslMarker
@ExperimentalDslApi
sealed interface ItemProviderDsl {
    
    /**
     * The base [ItemStack] that all other properties are applied on top of.
     *
     * Defaults to an empty [ItemStack]. Can be set to a static value or bound to a [Provider]:
     * ```
     * base by ItemStack(Material.DIAMOND_SWORD)
     * ```
     */
    val base: ProviderDslProperty<ItemStack>
    
    /**
     * The [ItemType] to override on the base stack, or `null` to keep the base stack's type.
     *
     * Defaults to `null`. Can be set to a static value or bound to a [Provider]:
     * ```
     * type by ItemType.NETHERITE_SWORD
     * ```
     */
    val type: ProviderDslProperty<ItemType?>
    
    /**
     * The stack amount to override, or `null` to keep the base stack's amount.
     *
     * Defaults to `null`. Can be set to a static value or bound to a [Provider]:
     * ```
     * amount by 16
     * ```
     */
    val amount: ProviderDslProperty<Int?>
    
    /**
     * The item name (`minecraft:item_name`),
     * or `null` to keep the base stack's name. Setting this automatically enables the tooltip.
     *
     * Defaults to `null`. Can be set to a [Component] or bound to a [Provider]:
     * ```
     * name by Component.text("Fire Sword").color(NamedTextColor.RED)
     * ```
     *
     * [MiniMessage][net.kyori.adventure.text.minimessage.MiniMessage] strings are also supported
     * via extension functions:
     * ```
     * name by "<red>Fire Sword"
     * ```
     */
    val name: ProviderDslProperty<Component?>
    
    /**
     * The custom name (`minecraft:custom_name`),
     * or `null` to keep the base stack's custom name. Unlike [name], this is the
     * player-visible renamed name (as from an anvil).
     *
     * Defaults to `null`. Can be set to a [Component] or bound to a [Provider]:
     * ```
     * customName by Component.text("My Renamed Sword").decorate(TextDecoration.ITALIC)
     * ```
     *
     * [MiniMessage][net.kyori.adventure.text.minimessage.MiniMessage] strings are also supported
     * via extension functions:
     * ```
     * customName by "<italic>My Renamed Sword"
     * ```
     */
    val customName: ProviderDslProperty<Component?>
    
    /**
     * The item lore lines, or `null` to keep the base stack's lore. Setting this automatically
     * enables the tooltip.
     *
     * Defaults to `null`. Can be set to a list of [Component]s or bound to a [Provider]:
     * ```
     * lore by listOf(
     *     Component.text("Line 1").color(NamedTextColor.GRAY),
     *     Component.text("Line 2").color(NamedTextColor.GRAY),
     * )
     * ```
     *
     * [MiniMessage][net.kyori.adventure.text.minimessage.MiniMessage] string lists are also
     * supported via extension functions:
     * ```
     * lore by listOf("<gray>Line 1", "<gray>Line 2")
     * ```
     */
    val lore: ProviderDslProperty<List<Component>?>
    
    /**
     * Whether the item has an enchantment glint, or `null` to keep the base stack's glint state.
     *
     * Defaults to `null`. Can be set to a static value or bound to a [Provider]:
     * ```
     * hasGlint by true
     * ```
     */
    val hasGlint: ProviderDslProperty<Boolean?>
    
    /**
     * Whether the item shows its tooltip, or `null` to keep the base stack's tooltip state.
     * Automatically set to `true` when [name] or [lore] is set.
     *
     * Defaults to `null`. Can be set to a static value or bound to a [Provider]:
     * ```
     * hasTooltip by false
     * ```
     */
    val hasTooltip: ProviderDslProperty<Boolean?>

}

@PublishedApi
@ExperimentalDslApi
internal class ItemProviderDslImpl(
    private var _base: Provider<ItemStack>
) : ItemProviderDsl {

    private var _type = provider<ItemType?>(null)
    private var _amount = provider<Int?>(null)
    private var _name = provider<Component?>(null)
    private var _customName = provider<Component?>(null)
    private var _lore = provider<List<Component>?>(null)
    private var _hasTooltip = provider<Boolean?>(null)
    private var _hasGlint = provider<Boolean?>(null)

    override val base: ProviderDslProperty<ItemStack>
        get() = ProviderDslProperty(::_base)
    override val type: ProviderDslProperty<ItemType?>
        get() = ProviderDslProperty(::_type)
    override val amount: ProviderDslProperty<Int?>
        get() = ProviderDslProperty(::_amount)
    override val name: ProviderDslProperty<Component?>
        get() = ProviderDslProperty(::_name)
    override val lore: ProviderDslProperty<List<Component>?>
        get() = ProviderDslProperty(::_lore)
    override val hasTooltip: ProviderDslProperty<Boolean?>
        get() = ProviderDslProperty(::_hasTooltip)
    override val customName: ProviderDslProperty<Component?>
        get() = ProviderDslProperty(::_customName)
    override val hasGlint: ProviderDslProperty<Boolean?>
        get() = ProviderDslProperty(::_hasGlint)

    fun build(): Provider<ItemProvider> {
        return combinedProvider(
            _base, _type, _amount, _name, _customName, _lore, _hasTooltip, _hasGlint
        ) { base, type, amount, name, customName, lore, hasTooltip, hasGlint ->
            var result = base.clone()

            @Suppress("DEPRECATION")
            if (type != null)
                result = result.withType(type.asMaterial()!!)

            if (amount != null)
                result.amount = amount

            // setting name or lore implicitly enables the tooltip
            val tooltip = hasTooltip ?: if (name != null || lore != null) true else null

            result.editMeta { meta ->
                if (name != null)
                    meta.itemName(name)
                if (customName != null)
                    meta.displayName(customName)
                if (lore != null)
                    meta.lore(lore.map(ComponentUtils::withoutPreFormatting))
                if (tooltip != null)
                    meta.isHideTooltip = !tooltip
                if (hasGlint != null)
                    meta.setEnchantmentGlintOverride(hasGlint)
            }

            ItemWrapper(result)
        }
    }

}