package com.mapbox.navigation.base.routerefresh

import android.location.Location
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.internal.route.RouteUrl
import com.mapbox.navigation.base.trip.model.RouteProgress
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MapboxRouteRefreshAdapterTest {

    private val accessToken = "pk.1234pplffd"

    private lateinit var routeRefreshAdapter: MapboxRouteRefreshAdapter
    private lateinit var location: Location

    companion object {
        private const val DEFAULT_REROUTE_BEARING_ANGLE = 11f
    }

    @Before
    fun setup() {
        routeRefreshAdapter = MapboxRouteRefreshAdapter()

        mockLocation()
    }

    @Test
    fun new_options_return_with_null_bearings() {
        val routeOptions = provideRouteOptionsWithCoordinates()
        val routeProgress: RouteProgress = mockk(relaxed = true)

        val newRouteOptions =
            routeRefreshAdapter.newRouteOptions(routeOptions, routeProgress, location)
        assertNotNull(newRouteOptions)

        val expectedBearings = listOf(
            listOf(
                DEFAULT_REROUTE_BEARING_ANGLE.toDouble(),
                MapboxRouteRefreshAdapter.DEFAULT_REROUTE_BEARING_TOLERANCE
            ),
            null,
            null,
            null
        )
        val actualBearings = newRouteOptions!!.bearingsList()

        assertEquals(expectedBearings, actualBearings)
    }

    @Test
    fun new_options_return_with_bearing() {
        val routeOptions = provideRouteOptionsWithCoordinatesAndBearings()
        val routeProgress: RouteProgress = mockk(relaxed = true)

        val newRouteOptions =
            routeRefreshAdapter.newRouteOptions(routeOptions, routeProgress, location)
        assertNotNull(newRouteOptions)

        val expectedBearings = listOf(
            listOf(DEFAULT_REROUTE_BEARING_ANGLE.toDouble(), 10.0),
            listOf(20.0, 20.0),
            listOf(30.0, 30.0),
            listOf(40.0, 40.0)
        )
        val actualBearings = newRouteOptions!!.bearingsList()

        assertEquals(expectedBearings, actualBearings)
    }

    @Test
    fun no_options_on_invalid_input() {
        val invalidInput = mutableListOf<Triple<RouteOptions?, RouteProgress?, Location?>>()
        invalidInput.add(Triple(null, mockk(), mockk()))
        invalidInput.add(Triple(mockk(), null, null))
        invalidInput.add(Triple(mockk(), mockk(), null))

        invalidInput.forEach { (routeOptions, routeProgress, location) ->
            val message =
                """routeOptions is ${routeOptions.isNullToString()}; routeProgress is ${routeProgress.isNullToString()}; location is ${location.isNullToString()}"""

            assertNull(
                message,
                routeRefreshAdapter.newRouteOptions(routeOptions, routeProgress, location)
            )
        }
    }

    private fun mockLocation() {
        location = mockk(relaxUnitFun = true)
        every { location.longitude } returns -122.4232
        every { location.latitude } returns 23.54423
        every { location.bearing } returns DEFAULT_REROUTE_BEARING_ANGLE
    }

    private fun provideRouteOptionsWithCoordinates() =
        provideDefaultRouteOptionsBuilder()
            .coordinates(
                listOf(
                    Point.fromLngLat(1.0, 1.0),
                    Point.fromLngLat(1.0, 1.0),
                    Point.fromLngLat(1.0, 1.0),
                    Point.fromLngLat(1.0, 1.0)
                )
            )
            .build()

    private fun provideRouteOptionsWithCoordinatesAndBearings() =
        provideRouteOptionsWithCoordinates()
            .toBuilder()
            .bearingsList(
                listOf(
                    listOf(10.0, 10.0),
                    listOf(20.0, 20.0),
                    listOf(30.0, 30.0),
                    listOf(40.0, 40.0),
                    listOf(50.0, 50.0),
                    listOf(60.0, 60.0)
                )
            )
            .build()

    private fun provideDefaultRouteOptionsBuilder() =
        RouteOptions.builder()
            .accessToken(accessToken)
            .baseUrl(RouteUrl.BASE_URL)
            .user(RouteUrl.PROFILE_DEFAULT_USER)
            .profile(RouteUrl.PROFILE_DRIVING)
            .coordinates(emptyList())
            .geometries("")
            .requestUuid("")

    private fun Any?.isNullToString(): String = if (this == null) "Null" else "NonNull"
}
