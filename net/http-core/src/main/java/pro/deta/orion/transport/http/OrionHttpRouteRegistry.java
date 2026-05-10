package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Singleton
public final class OrionHttpRouteRegistry {
    private final Map<String, OrionHttpRoute> routes;
    private final List<OrionHttpRoute> patternRoutes;
    private final List<RouteDescriptor> routeTable;

    @Inject
    public OrionHttpRouteRegistry(Set<OrionHttpRoute> routes) {
        RouteRegistry registry = routeRegistry(routes);
        this.routes = registry.routes();
        this.patternRoutes = registry.patternRoutes();
        this.routeTable = registry.routeTable();
    }

    OrionHttpRoute routeFor(String url) {
        OrionHttpRoute route = routes.get(url);
        if (route != null) {
            return route;
        }
        for (OrionHttpRoute patternRoute : patternRoutes) {
            if (WildcardMatcher.matches(patternRoute.urlPattern(), url)) {
                return patternRoute;
            }
        }
        return null;
    }

    public List<RouteDescriptor> routeTable() {
        return routeTable;
    }

    private static RouteRegistry routeRegistry(Set<OrionHttpRoute> routes) {
        Map<String, OrionHttpRoute> result = new LinkedHashMap<>();
        List<OrionHttpRoute> patternRoutes = new ArrayList<>();
        List<RouteDescriptor> routeTable = new ArrayList<>();
        for (OrionHttpRoute route : routes) {
            if (result.put(route.urlPattern(), route) != null) {
                throw new IllegalStateException("Duplicate HTTP route: " + route.urlPattern());
            }
            boolean pattern = route.urlPattern().contains("*");
            if (pattern) {
                patternRoutes.add(route);
            }
            routeTable.add(new RouteDescriptor(
                    route.urlPattern(),
                    route.authorization(),
                    route.allowedMethods(),
                    route.getClass().getSimpleName(),
                    pattern));
        }
        patternRoutes.sort(Comparator
                .comparingInt((OrionHttpRoute route) -> route.urlPattern().replace("*", "").length())
                .reversed()
                .thenComparing(OrionHttpRoute::urlPattern));
        routeTable.sort(Comparator.comparing(RouteDescriptor::urlPattern));
        return new RouteRegistry(Map.copyOf(result), List.copyOf(patternRoutes), List.copyOf(routeTable));
    }

    public record RouteDescriptor(
            String urlPattern,
            String authorization,
            List<String> methods,
            String handler,
            boolean pattern) {
    }

    private record RouteRegistry(
            Map<String, OrionHttpRoute> routes,
            List<OrionHttpRoute> patternRoutes,
            List<RouteDescriptor> routeTable) {
    }
}
