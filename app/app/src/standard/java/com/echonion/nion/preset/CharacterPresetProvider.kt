package com.echonion.nion.preset

/**
 * 标准版预设 Provider —— 无内置角色，所有属性返回 null。
 * standard flavor 专属源集，CharacterPresetProvider 是具体对象。
 */
object CharacterPresetProvider {
    val instance: CharacterPreset = object : CharacterPreset {}
}
