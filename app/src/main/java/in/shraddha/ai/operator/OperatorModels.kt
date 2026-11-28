package `in`.shraddha.ai.operator

import android.graphics.Rect
import java.util.UUID

data class ScreenElement(
    val id: String,
    val text: String,
    val description: String,
    val className: String,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val password: Boolean,
    val packageName: String = "",
    val hintText: String = ""
)

data class ScreenSnapshot(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val activityName: String,
    val capturedAt: Long,
    val elements: List<ScreenElement>
)

enum class OperatorActionType { OPEN_APP, CLICK_TEXT, TYPE_TEXT, BACK, HOME, SCROLL_FORWARD }

data class OperatorStep(
    val action: OperatorActionType,
    val target: String = "",
    val value: String = "",
    val expectedVisibleText: String = "",
    val requiresConfirmation: Boolean = false,
    val explicitlyAuthorizedByUser: Boolean = false
)

enum class OperatorTaskState { IDLE, OBSERVING, EXECUTING, VERIFYING, WAITING_CONFIRMATION, COMPLETED, FAILED, CANCELLED }

data class OperatorEvent(val state: OperatorTaskState, val message: String, val snapshot: ScreenSnapshot? = null, val stepIndex: Int? = null)
