package app.shilex.android.assistant

data class ScreenElement(
    val text: String = "",
    val description: String = "",
    val resourceId: String = "",
    val className: String = "",
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val bounds: String = ""
)

data class ScreenState(
    val packageName: String,
    val screenTitle: String = "",
    val elements: List<ScreenElement> = emptyList(),
    val capturedAt: Long = System.currentTimeMillis()
) {
    fun containsText(value: String): Boolean {
        if (value.isBlank()) return false
        return elements.any {
            it.text.contains(value, ignoreCase = true) ||
                it.description.contains(value, ignoreCase = true)
        }
    }

    fun hasClickableTarget(value: String): Boolean {
        if (value.isBlank()) return false
        return elements.any {
            it.clickable &&
                it.enabled &&
                (
                    it.text.contains(value, ignoreCase = true) ||
                        it.description.contains(value, ignoreCase = true)
                )
        }
    }

    fun findClickableTarget(value: String): ScreenElement? {
        if (value.isBlank()) return null
        return elements.firstOrNull {
            it.clickable &&
                it.enabled &&
                (
                    it.text.equals(value, ignoreCase = true) ||
                        it.description.equals(value, ignoreCase = true)
                )
        } ?: elements.firstOrNull {
            it.clickable &&
                it.enabled &&
                (
                    it.text.contains(value, ignoreCase = true) ||
                        it.description.contains(value, ignoreCase = true)
                )
        }
    }
}
