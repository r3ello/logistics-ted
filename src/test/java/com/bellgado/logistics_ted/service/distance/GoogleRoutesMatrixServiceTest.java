package com.bellgado.logistics_ted.service.distance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bellgado.logistics_ted.config.RoutingProperties;
import com.bellgado.logistics_ted.service.distance.GoogleRoutesMatrixService.IncompleteMatrixException;
import com.bellgado.logistics_ted.service.distance.GoogleRoutesMatrixService.MatrixElement;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoogleRoutesMatrixServiceTest {

    private static final RoutingProperties.Google CONFIG = new RoutingProperties.Google(
        "fake-key-for-test", "https://example.com", "DRIVE", "TRAFFIC_AWARE", 5);

    @Test
    void assembleMatrixBuildsKilometresFromElements() {
        List<MatrixElement> elements = List.of(
            element(0, 0, 0L),
            element(0, 1, 1234L),
            element(1, 0, 1500L),
            element(1, 1, 0L)
        );

        RouteCostMatrix matrix = GoogleRoutesMatrixService.assembleMatrix(elements, 2);

        assertThat(matrix.size()).isEqualTo(2);
        assertThat(matrix.km(0, 0)).isZero();
        assertThat(matrix.km(0, 1)).isEqualTo(1.234);
        assertThat(matrix.km(1, 0)).isEqualTo(1.5);
        assertThat(matrix.km(1, 1)).isZero();
    }

    @Test
    void assembleMatrixThrowsWhenAnyOffDiagonalCellIsMissing() {
        List<MatrixElement> incomplete = List.of(
            element(0, 1, 1000L)
        );

        assertThatThrownBy(() -> GoogleRoutesMatrixService.assembleMatrix(incomplete, 2))
            .isInstanceOf(IncompleteMatrixException.class)
            .hasMessageContaining("no usable distance");
    }

    @Test
    void assembleMatrixTreatsNonRouteExistsAsMissing() {
        List<MatrixElement> mixed = List.of(
            element(0, 1, 1000L),
            new MatrixElement(1, 0, null, null, "ROUTE_NOT_FOUND")
        );

        assertThatThrownBy(() -> GoogleRoutesMatrixService.assembleMatrix(mixed, 2))
            .isInstanceOf(IncompleteMatrixException.class);
    }

    @Test
    void computeFallsBackToHaversineWhenHttpCallThrows() {
        RouteMatrixService fallback = mock(RouteMatrixService.class);
        RouteCostMatrix haversineMatrix = new RouteCostMatrix(new double[][]{{0, 5}, {5, 0}});
        when(fallback.compute(any())).thenReturn(haversineMatrix);

        var service = new GoogleRoutesMatrixService(null, CONFIG, fallback) {
            @Override
            protected List<MatrixElement> callRoutes(List<double[]> points) {
                throw new RuntimeException("connection refused");
            }
        };

        RouteCostMatrix result = service.compute(List.of(
            new double[]{0, 0},
            new double[]{1, 1}
        ));

        assertThat(result.km(0, 1)).isEqualTo(5.0);
        verify(fallback, times(1)).compute(any());
    }

    @Test
    void computeFallsBackWhenResponseIsMissingCells() {
        RouteMatrixService fallback = mock(RouteMatrixService.class);
        RouteCostMatrix haversineMatrix = new RouteCostMatrix(new double[][]{{0, 9}, {9, 0}});
        when(fallback.compute(any())).thenReturn(haversineMatrix);

        var service = new GoogleRoutesMatrixService(null, CONFIG, fallback) {
            @Override
            protected List<MatrixElement> callRoutes(List<double[]> points) {
                return List.of(element(0, 1, 2000L));
            }
        };

        RouteCostMatrix result = service.compute(List.of(
            new double[]{0, 0},
            new double[]{1, 1}
        ));

        assertThat(result.km(0, 1)).isEqualTo(9.0);
        verify(fallback, times(1)).compute(any());
    }

    @Test
    void computeFallsBackWhenApiKeyIsBlank_withoutCallingRoutes() {
        RouteMatrixService fallback = mock(RouteMatrixService.class);
        RouteCostMatrix haversineMatrix = new RouteCostMatrix(new double[][]{{0, 3}, {3, 0}});
        when(fallback.compute(any())).thenReturn(haversineMatrix);

        var emptyKey = new RoutingProperties.Google("", "https://example.com", "DRIVE", "TRAFFIC_AWARE", 5);
        boolean[] called = {false};
        var service = new GoogleRoutesMatrixService(null, emptyKey, fallback) {
            @Override
            protected List<MatrixElement> callRoutes(List<double[]> points) {
                called[0] = true;
                return List.of();
            }
        };

        RouteCostMatrix result = service.compute(List.of(
            new double[]{0, 0},
            new double[]{1, 1}
        ));

        assertThat(called[0]).isFalse();
        assertThat(result.km(0, 1)).isEqualTo(3.0);
        verify(fallback, times(1)).compute(any());
    }

    @Test
    void computeReturnsGoogleMatrixWhenAllCellsPresent() {
        RouteMatrixService fallback = mock(RouteMatrixService.class);

        var service = new GoogleRoutesMatrixService(null, CONFIG, fallback) {
            @Override
            protected List<MatrixElement> callRoutes(List<double[]> points) {
                return List.of(
                    element(0, 0, 0L),
                    element(0, 1, 4500L),
                    element(1, 0, 4500L),
                    element(1, 1, 0L)
                );
            }
        };

        RouteCostMatrix result = service.compute(List.of(
            new double[]{0, 0},
            new double[]{1, 1}
        ));

        assertThat(result.km(0, 1)).isEqualTo(4.5);
        assertThat(result.km(1, 0)).isEqualTo(4.5);
        verify(fallback, times(0)).compute(any());
    }

    private static MatrixElement element(int origin, int destination, Long meters) {
        return new MatrixElement(origin, destination, meters, "0s", "ROUTE_EXISTS");
    }
}
