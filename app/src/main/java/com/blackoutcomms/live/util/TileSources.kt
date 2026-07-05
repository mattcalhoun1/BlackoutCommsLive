package com.blackoutcomms.live.util

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex

/**
 * All available map tile sources.
 *
 * Each source has a unique [name] that OSMDroid uses as the cache directory name,
 * so switching sources never overwrites or invalidates the other source's cached
 * tiles — both accumulate independently under filesDir/osmdroid/tiles/{name}/.
 */
object TileSources {

    enum class Source(val label: String, val prefKey: String) {
        ESRI_TOPO   ("ESRI World Topo",  "esri_topo"),
        OPEN_TOPO   ("OpenTopoMap",      "open_topo"),
        OSM         ("OpenStreetMap",    "osm"),
        USGS_TOPO (label="USGS Topo", prefKey="usgs")
    }

    val DEFAULT = Source.ESRI_TOPO // Source.USGS_TOPO

    fun build(source: Source): org.osmdroid.tileprovider.tilesource.ITileSource = when (source) {
        Source.USGS_TOPO -> object: XYTileSource (
            "USGS Topo",0, 18, 256, ".png",
            arrayOf("https://cache.chatters.io/tiles/usgs_topo/"),
            "USGS Topo")
        {}

        Source.ESRI_TOPO -> object : OnlineTileSourceBase(
            // Name is used as the OSMDroid cache folder — keep it stable
            "ESRI.WorldTopoMap",
            0, 19, 256, "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/"),
            "© ESRI, HERE, Garmin, FAO, NOAA, USGS"
        ) {
            // ESRI tile URL order is z/y/x (row before column)
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                return "${baseUrl}${z}/${y}/${x}"
            }
        }

        Source.OPEN_TOPO -> XYTileSource(
            "OpenTopoMap",
            0, 17, 256, ".png",
            arrayOf(
                "https://a.tile.opentopomap.org/",
                "https://b.tile.opentopomap.org/",
                "https://c.tile.opentopomap.org/"
            ),
            "© OpenTopoMap contributors, CC-BY-SA"
        )

        Source.OSM -> TileSourceFactory.MAPNIK
    }
}
