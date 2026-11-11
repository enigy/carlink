package com.carlink.fuel

import com.carlink.ui.settings.FuelGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** OpenStreetMap through Overpass, from a made-up answer (no real stations or places). */
class OverpassSourceTest {
    private val here = GeoPoint(-33.9, 151.2)
    private val diesel = CarFuel(CarFuel.Kind.DIESEL)
    private val gasoline = CarFuel(CarFuel.Kind.GASOLINE, FuelGrade.REGULAR)

    private val answer =
        """
        {"elements":[
          {"type":"node","id":1,"lat":-33.91,"lon":151.2,"tags":{"amenity":"fuel","name":"Made-up Fuel","fuel:diesel":"yes",
           "addr:housenumber":"12","addr:street":"Example Street","addr:city":"Sampletown"}},
          {"type":"way","id":2,"center":{"lat":-33.901,"lon":151.2},"tags":{"amenity":"fuel","brand":"Invented Oil"}},
          {"type":"node","id":3,"lat":-33.902,"lon":151.2,"tags":{"amenity":"fuel","operator":"Nobody","fuel:diesel":"no"}},
          {"type":"relation","id":4,"tags":{"amenity":"fuel","name":"No centre"}},
          {"type":"node","id":5,"lat":-33.95,"lon":151.2}
        ]}
        """.trimIndent()

    @Test
    fun `the query asks for fuel within the radius, with dots in every locale`() {
        assertEquals(
            "[out:json][timeout:15];nwr[\"amenity\"=\"fuel\"](around:8047,-33.900000,151.200000);out center tags 40;",
            OverpassSource.query(here, 8_046.72),
        )
    }

    @Test
    fun `stations are named, placed and kept or dropped by their diesel tag`() {
        val all = OverpassSource.parse(answer, gasoline)
        assertEquals(listOf("osm:node/1", "osm:way/2", "osm:node/3", "osm:node/5"), all.map { it.id })
        val first = all[0]
        assertEquals("Made-up Fuel", first.name)
        assertEquals("12 Example Street, Sampletown", first.address)
        assertEquals("a way's centre", GeoPoint(-33.901, 151.2), all[1].at)
        assertEquals("brand when there's no name", "Invented Oil", all[1].name)
        assertEquals("Fuel station", all[3].name)
        assertNull(all[3].address)
        assertEquals("diesel drops a station tagged no", listOf("osm:node/1", "osm:way/2", "osm:node/5"), OverpassSource.parse(answer, diesel).map { it.id })
    }

    @Test
    fun `for diesel the stations known to sell it come first, then the nearest`() {
        val source = OverpassSource { answer }
        assertEquals(listOf("osm:node/1", "osm:way/2", "osm:node/5"), source.stationsNear(here, diesel, 8_000.0).map { it.id })
        assertEquals("gasoline: nearest first", listOf("osm:way/2", "osm:node/3", "osm:node/1", "osm:node/5"), source.stationsNear(here, gasoline, 8_000.0).map { it.id })
    }
}
