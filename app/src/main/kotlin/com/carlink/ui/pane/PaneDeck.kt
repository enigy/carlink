package com.carlink.ui.pane

/** The cards the pane shows in turn, in swipe order: the route first, the music, the clock last. */
enum class PaneCard { DIRECTIONS, MEDIA, CLOCK }

/**
 * Which cards the pane offers and which one it shows. The pane cannot see which CarPlay app is in
 * front (the box reports no such thing), so it shows what CarPlay is NOT showing by its own tells:
 * the music by default, and the route once the driver has put the Now Playing app in front from the
 * pane's own source tap. A swipe picks any card. The call card is not here: a call takes the whole
 * pane for as long as it lasts, and the deck resumes where it was.
 */
object PaneDeck {
    /** The cards available now. The clock is always there, so the deck is never empty. */
    fun pages(
        navigating: Boolean,
        hasTrack: Boolean,
    ): List<PaneCard> =
        buildList {
            if (navigating) add(PaneCard.DIRECTIONS)
            if (hasTrack) add(PaneCard.MEDIA)
            add(PaneCard.CLOCK)
        }

    /** A fresh pane's card: the music, else the route, else the clock. */
    fun default(pages: List<PaneCard>): PaneCard = if (PaneCard.MEDIA in pages) PaneCard.MEDIA else pages.first()

    /**
     * The card for the pane once CarPlay shows [inFront] full on its side: the next card that is not
     * it. The route while Now Playing is in front, the music while Maps is, the clock failing both.
     */
    fun beside(
        inFront: PaneCard,
        pages: List<PaneCard>,
    ): PaneCard = pages.firstOrNull { it != inFront } ?: PaneCard.CLOCK

    /**
     * The card to show after the deck changed from [previous] to [current] while [shown] was up. A
     * card that went away gives way to the default; music that begins takes the pane, as it always
     * has; a route that begins takes it only from the clock, never from the music the driver is
     * looking at. Anything else leaves the driver's card alone.
     */
    fun after(
        previous: List<PaneCard>,
        current: List<PaneCard>,
        shown: PaneCard,
    ): PaneCard =
        when {
            shown !in current -> default(current)
            PaneCard.MEDIA in current && PaneCard.MEDIA !in previous -> PaneCard.MEDIA
            PaneCard.DIRECTIONS in current && PaneCard.DIRECTIONS !in previous && shown == PaneCard.CLOCK -> PaneCard.DIRECTIONS
            else -> shown
        }
}
