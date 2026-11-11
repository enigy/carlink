package com.carlink.ui.pane

/**
 * The cards the pane shows in turn, in swipe order: the route first, the truck's range and fuel, the music, the clock
 * (with the truck's speed and outside temperature) last.
 */
enum class PaneCard { DIRECTIONS, FUEL, MEDIA, CLOCK }

/**
 * What fills the pane behind the card: the plain day or night colour ([PLAIN]); the album art with a
 * frosted band behind the words ([BAND], the music card); or the art frosted all over ([FROSTED], the
 * clock while music plays: owner, 10-27, back to plain once it stops, the 1.07 look).
 */
enum class PaneBackdrop {
    PLAIN,
    BAND,
    FROSTED,
    ;

    companion object {
        /** The backdrop for [card] (null: a call) while the phone is [playing] or not. */
        fun of(
            card: PaneCard?,
            playing: Boolean,
        ): PaneBackdrop =
            when {
                card == PaneCard.MEDIA -> BAND
                card == PaneCard.CLOCK && playing -> FROSTED
                else -> PLAIN
            }
    }
}

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
    /** The cards available now. The clock is always there, so the deck is never empty. [fuel]: the Fuel card is dealt. */
    fun pages(
        navigating: Boolean,
        hasTrack: Boolean,
        fuel: Boolean = false,
    ): List<PaneCard> =
        buildList {
            if (navigating) add(PaneCard.DIRECTIONS)
            if (fuel) add(PaneCard.FUEL)
            if (hasTrack) add(PaneCard.MEDIA)
            add(PaneCard.CLOCK)
        }

    /** A fresh pane's card: the music, else the route, else the clock. The Fuel card comes by a swipe or low fuel only. */
    fun default(pages: List<PaneCard>): PaneCard = if (PaneCard.MEDIA in pages) PaneCard.MEDIA else pages.firstOrNull { it != PaneCard.FUEL } ?: PaneCard.CLOCK

    /**
     * The card for the pane once CarPlay shows [inFront] full on its side: the next card that is not
     * it. The route while Now Playing is in front, the music while Maps is, the clock failing both.
     */
    fun beside(
        inFront: PaneCard,
        pages: List<PaneCard>,
    ): PaneCard = pages.firstOrNull { it != inFront && it != PaneCard.FUEL } ?: PaneCard.CLOCK

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

/**
 * The pane and the low-fuel warning (Settings › Vehicle › Show on low fuel): the Fuel card comes up when the warning
 * comes on, and the card it replaced comes back when it goes off, if the driver hasn't swiped away since.
 */
object LowFuelDeck {
    /** The card to show as the warning comes on with [shown] up: the Fuel card, where the deck has it. */
    fun onLow(
        pages: List<PaneCard>,
        shown: PaneCard,
    ): PaneCard = if (PaneCard.FUEL in pages) PaneCard.FUEL else shown

    /**
     * The card to show as the warning goes off: [before], the card the warning replaced, while the Fuel card it brought
     * is still up and [before] is still dealt. A card the driver swiped to stays.
     */
    fun onCleared(
        before: PaneCard?,
        shown: PaneCard,
        pages: List<PaneCard>,
    ): PaneCard {
        val back = before?.takeIf { it != PaneCard.FUEL && it in pages } ?: return shown
        return if (shown == PaneCard.FUEL) back else shown
    }
}
