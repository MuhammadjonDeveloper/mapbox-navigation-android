package com.mapbox.navigation.base.routerefresh

import android.location.Location
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.trip.model.RouteProgress

/**
 * Provider is used for *Reroute* and *Faster Route* flow.
 *
 * *Reroute* provider might be overwritten via `MapboxNavigation.setRouteOptionsProviderOnOffRoute`.
 */
interface RouteOptionsProvider {

    /**
     * Provide a new instance *RouteOptions* based on initial *RouteOptions*, *RouteProgress* and
     * current *Location*
     */
    fun newRouteOptions(
        routeOptions: RouteOptions?,
        routeProgress: RouteProgress?,
        location: Location?
    ): RouteOptions?
}
