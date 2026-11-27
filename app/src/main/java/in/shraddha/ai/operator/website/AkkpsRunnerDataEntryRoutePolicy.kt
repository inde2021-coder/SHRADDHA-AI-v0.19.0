package `in`.shraddha.ai.operator.website

/** Auto-fill is allowed only on a create form or an explicitly opened edit form. */
internal object AkkpsRunnerDataEntryRoutePolicy {
    private val editableModules = setOf(
        AkkpsRunnerModule.FARMERS,
        AkkpsRunnerModule.PLOTS,
        AkkpsRunnerModule.AWD_REPORTS
    )

    fun isAllowed(route: AkkpsRunnerRouteMatch?, editFormSessionAuthorized: Boolean): Boolean =
        route != null &&
            route.module in editableModules &&
            (route.isCreateRoute || route.isEditRoute ||
                (route.isDetailRoute && editFormSessionAuthorized))
}
