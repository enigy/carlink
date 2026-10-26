package com.carlink.ui.pane

/** The cards the pane shows in turn, in swipe order: the route first, the music, the clock last. */
enum class PaneCard { DIRECTIONS, MEDIA, CLOCK }

/** CarPlay showing [app] full on its side, put in front by the pane's title, and the card the pane stepped [to] beside it. */
data class Aside(
    val app: PaneCard,
    val to: PaneCard,
)

/**
 * Which cards the pane offers and which one it shows. The pane cannot see which CarPlay app is in
 * front (the box reports no such thing), so it shows what CarPlay is NOT showing by its own tells:
 * the music by default, the route once the driver has put the Now Playing app in front from the
 * pane's own source tap, and the music again once a tap on CarPlay's Dock says they left it. A swipe
 * picks any card. The call card is not here: a call takes the whole pane for as long as it lasts, and
 * the deck resumes where it was.
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
     * The card for the pane once the driver leaves the CarPlay app the pane's title put in front
     * ([aside]) while [shown] is up: the app's own card back (owner, 10-26: back to the music when they
     * leave Now Playing), if the pane still shows the card it stepped to and the app's card is still
     * dealt. A card the driver swiped to since is theirs, and stays.
     */
    fun onLeft(
        aside: Aside,
        shown: PaneCard,
        pages: List<PaneCard>,
    ): PaneCard = if (shown == aside.to && aside.app in pages) aside.app else shown

    /**
     * The card for the pane when music starts playing while [shown] is up: the music, taken from the
     * clock only (owner, 10-26: "when music starts, bring the media card, instead of the clock"). A
     * route the driver is looking at keeps the pane, and a deck without a music card is left alone.
     * This is the play-after-pause case; a track that first appears is [after]'s rule.
     */
    fun onMusicStart(
        pages: List<PaneCard>,
        shown: PaneCard,
    ): PaneCard = if (shown == PaneCard.CLOCK && PaneCard.MEDIA in pages) PaneCard.MEDIA else shown

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
