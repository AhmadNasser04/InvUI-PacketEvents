package xyz.xenondevs.invui.gui

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemType
import xyz.xenondevs.commons.provider.Provider
import xyz.xenondevs.invui.ExperimentalReactiveApi
import xyz.xenondevs.invui.PropertyAdapter
import xyz.xenondevs.invui.inventory.Inventory
import xyz.xenondevs.invui.item.ItemProvider
import xyz.xenondevs.invui.item.ItemWrapper
import xyz.xenondevs.invui.item.simpleOrConstItem

/**
 * Adds an item ingredient for [key] that uses the [ItemProvider] from [provider].
 */
@JvmName("addIngredientMaterialProvider")
@ExperimentalReactiveApi
fun <S : IngredientMapper<S>> IngredientMapper<S>.addIngredient(key: Char, provider: Provider<Material>): S =
    addIngredient(key, simpleOrConstItem(provider.map { ItemWrapper(ItemStack.of(it)) }))

/**
 * Adds an item ingredient for [key] that uses the [ItemProvider] from [provider].
 */
@JvmName("addIngredientItemTypeProvider")
@ExperimentalReactiveApi
fun <S : IngredientMapper<S>> IngredientMapper<S>.addIngredient(key: Char, provider: Provider<ItemType>): S =
    addIngredient(key, simpleOrConstItem(provider.map { ItemWrapper(it.createItemStack()) }))

/**
 * Adds an item ingredient for [key] that uses the [ItemProvider] from [provider].
 */
@JvmName("addIngredientItemProviderProvider")
@ExperimentalReactiveApi
fun <S : IngredientMapper<S>> IngredientMapper<S>.addIngredient(key: Char, provider: Provider<ItemProvider>): S =
    addIngredient(key, simpleOrConstItem(provider))

/**
 * Adds an [Inventory] ingredient for [key] that starts at [offset] and uses [background] for empty slots.
 */
@JvmName("addIngredientMaterialProvider")
@ExperimentalReactiveApi
fun <S : IngredientMapper<S>> IngredientMapper<S>.addIngredient(key: Char, inventory: Inventory, background: Provider<Material>, offset: Int = 0): S =
    addIngredient(key, inventory, PropertyAdapter(background.map { ItemWrapper(ItemStack.of(it)) }), offset)

/**
 * Adds an [Inventory] ingredient for [key] that starts at [offset] and uses [background] for empty slots.
 */
@JvmName("addIngredientItemTypeProvider")
@ExperimentalReactiveApi
fun <S : IngredientMapper<S>> IngredientMapper<S>.addIngredient(key: Char, inventory: Inventory, background: Provider<ItemType>, offset: Int = 0): S =
    addIngredient(key, inventory, PropertyAdapter(background.map { ItemWrapper(it.createItemStack()) }), offset)

/**
 * Adds an [Inventory] ingredient for [key] that starts at [offset] and uses [background] for empty slots.
 */
@JvmName("addIngredientItemProviderProvider")
@ExperimentalReactiveApi
fun <S : IngredientMapper<S>> IngredientMapper<S>.addIngredient(key: Char, inventory: Inventory, background: Provider<ItemProvider>, offset: Int = 0): S =
    addIngredient(key, inventory, PropertyAdapter(background), offset)