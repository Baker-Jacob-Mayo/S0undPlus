package com.s0undplus.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPlacementTest {
    @Test
    fun onASideUpAndDownSlideAlongItAndLeftAndRightSwapSides() {
        val p = ChatPlacement(sideHeight = 50, sidePlace = 50)
        assertEquals(45, p.moved(0, -1).sidePlace)
        assertEquals(ChatPosition.LEFT, p.moved(-1, 0).position)
        assertEquals(ChatPosition.RIGHT, p.moved(-1, 0).moved(1, 0).position)
        assertEquals(0, p.copy(sidePlace = 0).moved(0, -1).sidePlace)
        assertEquals(100, p.copy(sidePlace = 100).moved(0, 1).sidePlace)
    }

    @Test
    fun floatingChatGoesWhereverItIsPushedButStaysOnScreen() {
        val p = ChatPlacement(position = ChatPosition.FLOAT, floatX = 50, floatY = 50)
        val moved = p.moved(-1, 1)
        assertEquals(45, moved.floatX)
        assertEquals(55, moved.floatY)
        assertEquals(ChatPosition.FLOAT, moved.position)
        assertEquals(0, p.copy(floatX = 0).moved(-1, 0).floatX)
        assertEquals(-1f, p.copy(floatX = 0).xBias, 0.001f)
        assertEquals(1f, p.copy(floatY = 100).yBias, 0.001f)
    }

    @Test
    fun aPlacementSurvivesARoundTripAndBadTextKeepsDefaults() {
        val p = ChatPlacement(ChatPosition.FLOAT, ChatLayout.OVERLAY, 35, 60, 20, 10, 90, 25, 40)
        assertEquals(p, ChatPlacement.decode(p.encode(), ChatLayout.OVERLAY))
        assertEquals(ChatPlacement(style = ChatLayout.SIDE), ChatPlacement.decode(null, ChatLayout.SIDE))
        // Out of range is pulled back in; garbage falls back to the default.
        assertEquals(ChatPlacement.MAX_WIDTH, ChatPlacement.decode("LEFT,999,x", ChatLayout.OVERLAY).sideWidth)
        assertEquals(ChatPosition.RIGHT, ChatPlacement.decode("SIDEWAYS", ChatLayout.OVERLAY).position)
    }

    @Test
    fun resizingKeepsWithinLimitsAndBesideTheVideoIsAlwaysFullHeight() {
        val over = ChatPlacement(sideWidth = 40, sideHeight = 60)
        assertEquals(45, over.resized(1, 0).sideWidth)
        assertEquals(65, over.resized(0, -1).sideHeight) // Up is taller
        assertEquals(ChatPlacement.MAX_WIDTH, over.copy(sideWidth = ChatPlacement.MAX_WIDTH).resized(1, 0).sideWidth)
        assertEquals(ChatPlacement.MIN_HEIGHT, over.copy(sideHeight = ChatPlacement.MIN_HEIGHT).resized(0, 1).sideHeight)

        val beside = over.copy(style = ChatLayout.SIDE)
        assertTrue(beside.beside)
        assertEquals(100, beside.heightPercent)
        assertEquals(60, beside.resized(0, -1).sideHeight)
        assertFalse(beside.copy(position = ChatPosition.FLOAT).beside)
    }
}
