package com.bellgado.logistics_ted.service.distance;

import java.util.List;

/**
 * Builds a {@link RouteCostMatrix} over a fixed list of WGS-84 points. The list order defines the
 * matrix indices: point at position {@code i} corresponds to matrix index {@code i}.
 */
public interface RouteMatrixService {

    /**
     * @param points {@code [lat, lng]} pairs; index 0 is conventionally the origin and the last
     *               index is conventionally the destination, but the matrix itself does not care.
     */
    RouteCostMatrix compute(List<double[]> points);
}
