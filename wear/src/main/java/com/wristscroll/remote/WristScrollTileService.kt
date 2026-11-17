package com.wristscroll.remote

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.wristscroll.remote.shared.Protocol

/**
 * Wear OS Tile with one centred button that launches MainActivity with the
 * boolean extra "start_in_stealth" set to true.
 */
class WristScrollTileService : TileService() {

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val COLOR_STEALTH_GREEN = 0xFF00E676.toInt()
        private const val COLOR_BLACK = 0xFF000000.toInt()
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val launchActivity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(MainActivity::class.java.name)
            .addKeyToExtraMapping(
                Protocol.EXTRA_START_IN_STEALTH,
                ActionBuilders.AndroidBooleanExtra.Builder()
                    .setValue(true)
                    .build()
            )
            .build()

        val launchAction = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(launchActivity)
            .build()

        val clickable = ModifiersBuilders.Clickable.Builder()
            .setId("launch_stealth_remote")
            .setOnClick(launchAction)
            .build()

        val centerButton = CompactChip.Builder(
            this,
            getString(R.string.tile_button_text),
            clickable,
            requestParams.deviceConfiguration
        )
            .setChipColors(
                ChipColors(
                    argb(COLOR_STEALTH_GREEN),
                    argb(COLOR_BLACK)
                )
            )
            .build()

        val rootLayout = LayoutElementBuilders.Box.Builder()
            .setWidth( androidx.wear.protolayout.DimensionBuilders.expand() )
            .setHeight( androidx.wear.protolayout.DimensionBuilders.expand() )
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(centerButton)
            .build()

        val timeline = TimelineBuilders.Timeline.fromLayoutElement(rootLayout)

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline)
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .build()
        return Futures.immediateFuture(resources)
    }
}
