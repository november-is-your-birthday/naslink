package com.naslink.util

/**
 * Turns a user template like `holiday_##` into the new name for each selected item:
 * `##` becomes the zero-padded running number, and files keep their original extension
 * unless the template already carries one.
 */
object BatchRename {

    /** Runs of two or more `#` are all accepted as the number slot. */
    private val numberRun = Regex("#{2,}")

    /** A multi-item rename needs an explicit number slot, or every item would collide. */
    fun needsNumberSlot(template: String): Boolean = !numberRun.containsMatchIn(template)

    fun newName(
        template: String,
        originalName: String,
        isDirectory: Boolean,
        oneBasedIndex: Int,
        padWidth: Int,
    ): String {
        val numbered = if (numberRun.containsMatchIn(template)) {
            template.replace(numberRun, oneBasedIndex.toString().padStart(padWidth.coerceAtLeast(1), '0'))
        } else {
            template
        }
        if ('.' in numbered) return numbered
        // Dotfiles such as ".hidden" have no extension to preserve; directories never do.
        val extension = if (!isDirectory && !originalName.startsWith('.')) {
            originalName.substringAfterLast('.', "")
        } else {
            ""
        }
        return if (extension.isEmpty()) numbered else "$numbered.$extension"
    }
}
