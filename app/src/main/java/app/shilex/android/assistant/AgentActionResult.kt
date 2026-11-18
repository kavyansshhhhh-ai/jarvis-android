package app.shilex.android.assistant

enum class ActionStatus {
    SUCCESS,
    FAILED,
    WRONG_SCREEN,
    TARGET_NOT_FOUND,
    TIMEOUT,
    ALREADY_COMPLETED
}

data class AgentActionResult(
    val status: ActionStatus,
    val message: String,
    val expectedDestination: String = "",
    val actualDestination: String = "",
    val retryCount: Int = 0,
    val verifiedScreenState: ScreenState? = null
) {
    val isSuccessful: Boolean
        get() = status == ActionStatus.SUCCESS || status == ActionStatus.ALREADY_COMPLETED
}
