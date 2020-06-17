package com.mapbox.navigation.base.routerefresh

/**
 * Provider for processing routing events
 */
open class RoutingOptionsProvider {

    private val routeRefreshAdapter: RouteRefreshAdapter by lazy {
        MapboxRouteRefreshAdapter()
    }

    /**
     * Defines adapter that will be used by SDK on off route event.
     * By default uses [MapboxRouteRefreshAdapter]
     */
    open fun provideOffRouteRefreshAdapter(): RouteRefreshAdapter = routeRefreshAdapter

    /**
     * Defines adapter that will be used by SDK to define faster route.
     * By default uses [MapboxRouteRefreshAdapter]
     *
     * Note: cannot be overwritten
     */
    fun provideFasterRouteRefreshAdapter(): RouteRefreshAdapter = routeRefreshAdapter
}
