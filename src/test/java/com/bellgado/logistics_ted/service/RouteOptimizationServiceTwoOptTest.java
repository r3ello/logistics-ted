package com.bellgado.logistics_ted.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bellgado.logistics_ted.service.distance.RouteCostMatrix;
import com.bellgado.logistics_ted.service.solver.CandidateStop;
import com.bellgado.logistics_ted.service.solver.HeuristicRouteSolver;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for the asymmetric-safe 2-opt introduced in phase 4 and now living on
 * {@link HeuristicRouteSolver}. The full-flow goldens in {@link RouteOptimizationServiceTest}
 * all run with symmetric haversine and cannot detect a regression where the asymmetric
 * re-scoring is lost.
 */
class RouteOptimizationServiceTwoOptTest {

    @Test
    void doesNotSwapWhenInteriorBecomesExpensiveUnderAsymmetricCosts() {
        // 4×4 matrix: 0 = origin, 1 = A, 2 = B, 3 = C. Forward A→B→C is cheap; reversed legs
        // (B→A, C→B) are 1000× more expensive, modelling one-way streets. Naive scoring (entry
        // + exit only) sees origin→C as much cheaper than origin→A and would swap [A,B,C] →
        // [C,B,A]; the asymmetric scoring catches the interior cost.
        double[][] km = {
            {0,    100,  5,    1   },
            {100,  0,    1,    5   },
            {5,    1000, 0,    1   },
            {1,    5,    1000, 0   }
        };
        RouteCostMatrix matrix = new RouteCostMatrix(km);

        List<CandidateStop> result = HeuristicRouteSolver.twoOpt(
            List.of(stop(1, 1), stop(2, 2), stop(3, 3)), 0, matrix);

        assertThat(result).extracting(CandidateStop::id).containsExactly(1, 2, 3);
    }

    @Test
    void stillSwapsWhenSymmetricReversalIsBeneficial() {
        // Symmetric layout where the forward route [A,B,C] zigzags and reversing to [C,B,A]
        // genuinely shortens the trip — asymmetric scoring degenerates to the symmetric case
        // here and the swap still fires.
        double[][] km = {
            {0, 100, 50,  1  },
            {100, 0, 10,  50 },
            {50,  10, 0,  10 },
            {1,   50, 10, 0  }
        };
        RouteCostMatrix matrix = new RouteCostMatrix(km);

        List<CandidateStop> result = HeuristicRouteSolver.twoOpt(
            List.of(stop(1, 1), stop(2, 2), stop(3, 3)), 0, matrix);

        assertThat(result).extracting(CandidateStop::id).containsExactly(3, 2, 1);
    }

    private static CandidateStop stop(int id, int index) {
        CandidateStop c = new CandidateStop(id, "h" + id, "loc", 0, 0);
        c.setIndex(index);
        return c;
    }
}
