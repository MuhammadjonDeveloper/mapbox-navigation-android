package com.mapbox.navigation.base.routerefresh

object RouteRefreshAdapterProvider {

    private val routeRefreshAdapter: RouteRefreshAdapter by lazy {
        MapboxRouteRefreshAdapter()
    }

    @JvmStatic
    fun provideRouteRefreshAdapter(): RouteRefreshAdapter = routeRefreshAdapter
}