package com.s0undplus.tv.data

/** Where chat sits over the player: down one side, or floating anywhere on the picture. */
enum class ChatPosition(val label: String) {
    RIGHT("Right"),
    LEFT("Left"),
    FLOAT("Floating"),
}

/**
 * Chat's size and place, all in percent so they mean the same on any screen. On a side, chat is [sideWidth] wide and,
 * over the video, [sideHeight] tall at [sidePlace] (0 at the top, 100 at the bottom); beside the video it is always
 * full height. Floating chat is [floatWidth] x [floatHeight] at [floatX], [floatY] (0..100 across the room left for it).
 */
data class ChatPlacement(
    val position: ChatPosition = ChatPosition.RIGHT,
    val style: ChatLayout = ChatLayout.OVERLAY,
    val sideWidth: Int = 40,
    val sideHeight: Int = 100,
    val sidePlace: Int = 100,
    val floatX: Int = 100,
    val floatY: Int = 100,
    val floatWidth: Int = 30,
    val floatHeight: Int = 45,
) {
    val floating: Boolean get() = position == ChatPosition.FLOAT

    /** Beside the video: the picture shrinks to make room, so chat is never over it. */
    val beside: Boolean get() = !floating && style == ChatLayout.SIDE

    val widthPercent: Int get() = if (floating) floatWidth else sideWidth

    val heightPercent: Int get() = when {
        floating -> floatHeight
        beside -> 100
        else -> sideHeight
    }

    /** Horizontal place, -1 (left edge) to 1 (right edge), as a Compose bias. */
    val xBias: Float get() = when (position) {
        ChatPosition.LEFT -> -1f
        ChatPosition.RIGHT -> 1f
        ChatPosition.FLOAT -> floatX / 50f - 1f
    }

    /** Vertical place, -1 (top) to 1 (bottom), as a Compose bias. */
    val yBias: Float get() = (if (floating) floatY else sidePlace) / 50f - 1f

    /**
     * One press of the D-pad while moving: on a side, Up and Down slide chat along it and Left and Right swap sides;
     * floating, it goes that way.
     */
    fun moved(dx: Int, dy: Int): ChatPlacement = when {
        floating -> copy(floatX = (floatX + dx * MOVE_STEP).coerceIn(0, 100), floatY = (floatY + dy * MOVE_STEP).coerceIn(0, 100))
        dx < 0 -> copy(position = ChatPosition.LEFT)
        dx > 0 -> copy(position = ChatPosition.RIGHT)
        else -> copy(sidePlace = (sidePlace + dy * MOVE_STEP).coerceIn(0, 100))
    }

    /** One press while resizing: Right is wider, Left narrower, Up taller, Down shorter. */
    fun resized(dx: Int, dy: Int): ChatPlacement = if (floating) {
        copy(
            floatWidth = (floatWidth + dx * SIZE_STEP).coerceIn(MIN_WIDTH, MAX_WIDTH),
            floatHeight = (floatHeight - dy * SIZE_STEP).coerceIn(MIN_HEIGHT, 100),
        )
    } else {
        copy(
            sideWidth = (sideWidth + dx * SIZE_STEP).coerceIn(MIN_WIDTH, MAX_WIDTH),
            // Beside the video chat is always full height: nothing to change.
            sideHeight = if (beside) sideHeight else (sideHeight - dy * SIZE_STEP).coerceIn(MIN_HEIGHT, 100),
        )
    }

    /** Everything but [style] (kept as its own setting), as "RIGHT,40,100,100,100,100,30,45". */
    fun encode(): String = listOf(position.name, sideWidth, sideHeight, sidePlace, floatX, floatY, floatWidth, floatHeight).joinToString(",")

    companion object {
        /** Reads [encode]'s form; anything missing or unreadable keeps its default. */
        fun decode(text: String?, style: ChatLayout): ChatPlacement {
            val d = ChatPlacement(style = style)
            val parts = text?.split(",").orEmpty()
            fun int(i: Int, default: Int, range: IntRange) = parts.getOrNull(i)?.trim()?.toIntOrNull()?.coerceIn(range) ?: default
            return d.copy(
                position = ChatPosition.entries.firstOrNull { it.name == parts.getOrNull(0)?.trim() } ?: d.position,
                sideWidth = int(1, d.sideWidth, MIN_WIDTH..MAX_WIDTH),
                sideHeight = int(2, d.sideHeight, MIN_HEIGHT..100),
                sidePlace = int(3, d.sidePlace, 0..100),
                floatX = int(4, d.floatX, 0..100),
                floatY = int(5, d.floatY, 0..100),
                floatWidth = int(6, d.floatWidth, MIN_WIDTH..MAX_WIDTH),
                floatHeight = int(7, d.floatHeight, MIN_HEIGHT..100),
            )
        }

        const val MOVE_STEP = 5
        const val SIZE_STEP = 5
        const val MIN_WIDTH = 20
        const val MAX_WIDTH = 60
        const val MIN_HEIGHT = 20
    }
}
