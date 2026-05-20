package com.bellgado.logistics_ted.config;

import com.bellgado.logistics_ted.service.distance.CachingRouteMatrixService;
import com.bellgado.logistics_ted.service.distance.DistanceService;
import com.bellgado.logistics_ted.service.distance.GoogleRoutesMatrixService;
import com.bellgado.logistics_ted.service.distance.HaversineMatrixService;
import com.bellgado.logistics_ted.service.distance.RouteCostCache;
import com.bellgado.logistics_ted.service.distance.RouteMatrixService;
import com.bellgado.logistics_ted.service.solver.HeuristicRouteSolver;
import com.bellgado.logistics_ted.service.solver.RouteSolver;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the routing layer. The {@code @Primary} {@link RouteMatrixService} is the caching
 * decorator; the underlying delegate is chosen by {@code routing.provider}:
 * {@code haversine} (default) or {@code google}. The Google variant always falls back to a
 * haversine instance on error, so a misconfigured key never takes the endpoint down.
 */
@Configuration
@EnableConfigurationProperties(RoutingProperties.class)
public class RoutingConfig {

    private static final Logger log = LoggerFactory.getLogger(RoutingConfig.class);

    @Bean
    public RouteMatrixService delegateMatrixService(DistanceService distance,
                                                    RoutingProperties props) {
        HaversineMatrixService haversine = new HaversineMatrixService(distance);
        return switch (props.provider().toLowerCase()) {
            case "google" -> {
                log.info("Routing provider: google ({}); haversine reserved as fallback.",
                    props.google().baseUrl());
                yield new GoogleRoutesMatrixService(
                    googleRestClient(props.google()), props.google(), haversine);
            }
            case "haversine" -> {
                log.info("Routing provider: haversine.");
                yield haversine;
            }
            default -> throw new IllegalStateException(
                "Unknown routing.provider '" + props.provider() + "'; expected haversine or google.");
        };
    }

    @Bean
    @Primary
    public RouteMatrixService routeMatrixService(
            @Qualifier("delegateMatrixService") RouteMatrixService delegate,
            RoutingProperties props) {
        RouteCostCache cache = new RouteCostCache(
            Duration.ofSeconds(props.cache().ttlSeconds()),
            props.cache().maxSize(),
            Clock.systemUTC());
        return new CachingRouteMatrixService(delegate, cache);
    }

    @Bean
    public RouteSolver routeSolver() {
        return new HeuristicRouteSolver();
    }

    private static RestClient googleRestClient(RoutingProperties.Google config) {
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory();
        rf.setReadTimeout(Duration.ofSeconds(config.requestTimeoutSeconds()));
        return RestClient.builder()
            .baseUrl(config.baseUrl())
            .requestFactory(rf)
            .build();
    }
}
