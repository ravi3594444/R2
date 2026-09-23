package ai.wakey.android.accessibility

import ai.wakey.android.accessibility.ScrollAction.Backward
import ai.wakey.android.accessibility.ScrollAction.Down
import ai.wakey.android.accessibility.ScrollAction.Forward
import ai.wakey.android.accessibility.ScrollAction.Left
import ai.wakey.android.accessibility.ScrollAction.Right
import ai.wakey.android.accessibility.ScrollAction.Up
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollPlannerTest {
    private val page = ScrollCandidate(0, box(0, 200, 1080, 2400), setOf(Down, Forward))
    private val carousel = ScrollCandidate(1, box(0, 300, 1080, 700), setOf(Forward, Backward), horizontalHint = true)
    private val dropdown = ScrollCandidate(2, box(100, 300, 600, 900), setOf(Down, Up, Forward, Backward))

    @Test
    fun `the largest list on the requested axis that can still move is used`() {
        val plan = ScrollPlanner.plan(ScrollDirection.Down, listOf(carousel, dropdown, page))
        assertEquals(ScrollPlan.Perform(page, listOf(Down, Forward)), plan)
    }

    @Test
    fun `a list that can only go the other way is at its end unless another can move`() {
        val atBottom = page.copy(actions = setOf(Up, Backward))
        assertEquals(ScrollPlan.AtEnd(atBottom), ScrollPlanner.plan(ScrollDirection.Down, listOf(atBottom)))
        assertEquals(
            ScrollPlan.Perform(dropdown, listOf(Down, Forward)),
            ScrollPlanner.plan(ScrollDirection.Down, listOf(atBottom, dropdown)),
        )
    }

    @Test
    fun `horizontal scrolls use horizontal containers only`() {
        assertEquals(ScrollPlan.Perform(carousel, listOf(Forward)), ScrollPlanner.plan(ScrollDirection.Right, listOf(page, carousel)))
        assertEquals(ScrollPlan.Swipe(null), ScrollPlanner.plan(ScrollDirection.Left, listOf(page)))
    }

    @Test
    fun `an explicit target is used whatever its axis`() {
        assertEquals(
            ScrollPlan.Perform(carousel, listOf(Forward)),
            ScrollPlanner.plan(ScrollDirection.Down, listOf(carousel), explicit = true),
        )
    }

    @Test
    fun `a scrollable node that reports no actions is still tried`() {
        val silent = ScrollCandidate(0, box(0, 0, 1080, 2000), emptySet())
        assertEquals(ScrollPlan.Perform(silent, listOf(Up, Backward)), ScrollPlanner.plan(ScrollDirection.Up, listOf(silent)))
        assertEquals(ScrollPlan.Swipe(null), ScrollPlanner.plan(ScrollDirection.Down, emptyList()))
    }

    @Test
    fun `swipes drag against the scroll direction and stay inside the area`() {
        val area = box(0, 200, 1000, 2200)
        val down = ScrollPlanner.swipe(ScrollDirection.Down, area)
        assertEquals(SwipeLine(500, 1600, 500, 800), down)
        assertEquals(SwipeLine(500, 800, 500, 1600), ScrollPlanner.swipe(ScrollDirection.Up, area))
        assertEquals(SwipeLine(700, 1200, 300, 1200), ScrollPlanner.swipe(ScrollDirection.Right, area))
        assertEquals(SwipeLine(300, 1200, 700, 1200), ScrollPlanner.swipe(ScrollDirection.Left, area))
        listOf(down.startX, down.endX).forEach { assertTrue(it in area.left..area.right) }
    }
}
