package de.aploi.sussurrobyeyed.wear.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import de.aploi.sussurrobyeyed.wear.MainActivity
import de.aploi.sussurrobyeyed.wear.R

/**
 * Single-fixed-layout tile that drops a "Tap to dictate" surface onto the
 * watch tile carousel.
 *
 * The tile is intentionally minimal: it has no recording state of its own
 * because tiles render server-side and can't observe Kotlin Flows. Tapping
 * the tile launches [MainActivity], which already handles the
 * Idle → Recording transition the moment the user taps inside it. This
 * gives the user a one-swipe entry to a session without making the tile
 * itself stateful (which would otherwise require a `getUpdater().requestUpdate`
 * cadence we don't have lifecycle hooks for).
 */
class SussurroTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val launch = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name)
                    .build(),
            )
            .build()

        val clickable = ModifiersBuilders.Clickable.Builder()
            .setId("sussurro_tile_root")
            .setOnClick(launch)
            .build()

        // Watch surfaces vary wildly (round 1.2", square 1.4", …). Lean on
        // a vertical Column with breathing room so the layout looks
        // intentional on every device the renderer drops us into.
        val content: LayoutElementBuilders.LayoutElement = Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(clickable)
                    .setSemantics(
                        ModifiersBuilders.Semantics.Builder()
                            .setContentDescription(getString(R.string.tile_action))
                            .build(),
                    )
                    .build(),
            )
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(
                        Text.Builder()
                            .setText(getString(R.string.tile_brand))
                            .setFontStyle(
                                FontStyle.Builder()
                                    .setSize(sp(BRAND_TEXT_SP))
                                    .setColor(argb(BRAND_COLOR))
                                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                                    .build(),
                            )
                            .build(),
                    )
                    .addContent(Spacer.Builder().setHeight(dp(8f)).build())
                    .addContent(
                        Text.Builder()
                            .setText(getString(R.string.tile_action))
                            .setFontStyle(
                                FontStyle.Builder()
                                    .setSize(sp(ACTION_TEXT_SP))
                                    .setColor(argb(ACTION_COLOR))
                                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(content).build())
                    .build(),
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline)
            // Tile content is static; we don't need the system to ever
            // poll us for a refresh. 0 disables proactive refresh.
            .setFreshnessIntervalMillis(0)
            .build()

        return immediate(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> {
        // Tile contains only text + a clickable container; no drawables or
        // images to ship. Returning an empty resource bundle is the
        // canonical no-op response.
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .build()
        return immediate(resources)
    }

    /**
     * Build an already-completed [ListenableFuture]. We don't depend on
     * full Guava (just `listenablefuture` for the interface), so we use
     * androidx's [CallbackToFutureAdapter] as the lightweight stand-in
     * for `Futures.immediateFuture(...)`. The internal [ResolvableFuture]
     * API is `@RestrictTo(LIBRARY_GROUP_PREFIX)` and trips lint, so we
     * lean on the public completer-callback flavour here.
     */
    private fun <T> immediate(value: T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(value)
            "SussurroTile.immediate"
        }

    private companion object {
        /**
         * Bumped whenever the resource bundle shape changes. Stays at "1"
         * because we currently ship no images / drawables.
         */
        const val RESOURCES_VERSION = "1"

        // Hand-picked sizes that read well on both round and square
        // watches without setting a fixed size that clips on small ones.
        // sp scales with the user's accessibility text size, which is
        // the correct affordance for a text-only tile.
        const val BRAND_TEXT_SP = 24f
        const val ACTION_TEXT_SP = 14f

        // Mirror the WatchColors palette used by the Compose UI so the
        // tile and the in-app screen feel like the same app. ProtoLayout
        // takes argb ints rather than Compose `Color`, so the values are
        // duplicated here — keep them lock-stepped with WatchColors if
        // the palette ever changes.
        const val BRAND_COLOR = 0xFFFFFFFF.toInt()
        const val ACTION_COLOR = 0xFF8AB4FF.toInt()
    }
}
