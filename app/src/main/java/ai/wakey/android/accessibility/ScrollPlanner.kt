package ai.wakey.android.accessibility

/** Scroll actions a node can offer. Directions name the content revealed, as Android's do. */
enum class ScrollAction { Down, Up, Left, Right, Forward, Backward }

/** A scrollable node on screen; [key] identifies it to the caller. */
data class ScrollCandidate(
    val key: Int,
    val bounds: NodeBounds,
    /** Scroll actions the node currently offers (Android drops the ones that would do nothing). */
    val actions: Set<ScrollAction>,
    /** Class says horizontal (HorizontalScrollView, ViewPager) even if the actions don't. */
    val horizontalHint: Boolean = false,
)

/** What [ScrollPlanner.plan] decided to do. */
sealed interface ScrollPlan {
    /** Try [actions] in order on [candidate], then swipe inside it if none worked. */
    data class Perform(val candidate: ScrollCandidate, val actions: List<ScrollAction>) : ScrollPlan

    /** The container can scroll, just not this way any more. */
    data class AtEnd(val candidate: ScrollCandidate) : ScrollPlan

    /** Nothing suitable is exposed; swipe across [bounds] (null: the whole screen). */
    data class Swipe(val bounds: NodeBounds?) : ScrollPlan
}

/** A straight finger path, in screen pixels. */
data class SwipeLine(val startX: Int, val startY: Int, val endX: Int, val endY: Int)

/** Picks which container to scroll and how, with a swipe gesture as the fallback. */
object ScrollPlanner {
    private const val SWIPE_NEAR = 0.3
    private const val SWIPE_FAR = 0.7

    /**
     * Chooses what to scroll. With [explicit] the single candidate is the user's target and is used
     * whatever its axis; otherwise the largest container on the right axis that can still move wins.
     */
    fun plan(direction: ScrollDirection, candidates: List<ScrollCandidate>, explicit: Boolean = false): ScrollPlan {
        val vertical = direction == ScrollDirection.Up || direction == ScrollDirection.Down
        val eligible = if (explicit) candidates else candidates.filter { c ->
            val axis = axisOf(c)
            if (vertical) axis != Axis.Horizontal else axis == Axis.Horizontal
        }
        if (eligible.isEmpty()) return ScrollPlan.Swipe(null)
        val movable = eligible.filter { actionsFor(direction, it).isNotEmpty() }
        val chosen = movable.maxByOrNull { it.bounds.area }
        if (chosen != null) return ScrollPlan.Perform(chosen, actionsFor(direction, chosen))
        val largest = eligible.maxBy { it.bounds.area }
        // No scroll actions at all means the node doesn't report them; try anyway before swiping.
        return if (largest.actions.isEmpty()) ScrollPlan.Perform(largest, guessedActions(direction)) else ScrollPlan.AtEnd(largest)
    }

    /**
     * Finger path that reveals content in [direction] inside [area]: scrolling down drags upwards.
     * Stays away from the edges so it isn't taken for a back or notification-shade gesture.
     */
    fun swipe(direction: ScrollDirection, area: NodeBounds): SwipeLine {
        fun at(start: Int, length: Int, fraction: Double) = (start + length * fraction).toInt().coerceAtLeast(0)
        val x = area.centerX.coerceAtLeast(0)
        val y = area.centerY.coerceAtLeast(0)
        val upper = at(area.top, area.height, SWIPE_NEAR)
        val lower = at(area.top, area.height, SWIPE_FAR)
        val leftSide = at(area.left, area.width, SWIPE_NEAR)
        val rightSide = at(area.left, area.width, SWIPE_FAR)
        return when (direction) {
            ScrollDirection.Down -> SwipeLine(x, lower, x, upper)
            ScrollDirection.Up -> SwipeLine(x, upper, x, lower)
            ScrollDirection.Right -> SwipeLine(rightSide, y, leftSide, y)
            ScrollDirection.Left -> SwipeLine(leftSide, y, rightSide, y)
        }
    }

    private enum class Axis { Vertical, Horizontal, Unknown }

    private fun axisOf(c: ScrollCandidate): Axis = when {
        ScrollAction.Down in c.actions || ScrollAction.Up in c.actions -> Axis.Vertical
        ScrollAction.Left in c.actions || ScrollAction.Right in c.actions || c.horizontalHint -> Axis.Horizontal
        else -> Axis.Unknown
    }

    /** Actions on offer that move [c] in [direction], directional before generic. */
    private fun actionsFor(direction: ScrollDirection, c: ScrollCandidate): List<ScrollAction> {
        val (directional, generic) = when (direction) {
            ScrollDirection.Down -> ScrollAction.Down to ScrollAction.Forward
            ScrollDirection.Up -> ScrollAction.Up to ScrollAction.Backward
            ScrollDirection.Right -> ScrollAction.Right to ScrollAction.Forward
            ScrollDirection.Left -> ScrollAction.Left to ScrollAction.Backward
        }
        return listOf(directional, generic).filter { it in c.actions }
    }

    private fun guessedActions(direction: ScrollDirection): List<ScrollAction> = when (direction) {
        ScrollDirection.Down -> listOf(ScrollAction.Down, ScrollAction.Forward)
        ScrollDirection.Up -> listOf(ScrollAction.Up, ScrollAction.Backward)
        ScrollDirection.Right -> listOf(ScrollAction.Right, ScrollAction.Forward)
        ScrollDirection.Left -> listOf(ScrollAction.Left, ScrollAction.Backward)
    }
}
