package com.payflow.routing.service;

import com.payflow.routing.routing.BankRoute;
import com.payflow.routing.routing.RoutingDecision;
import com.payflow.routing.routing.RoutingMetricsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SmartRoutingService Unit Tests")
class SmartRoutingServiceTest {

    @Mock
    private RoutingMetricsRepository metricsRepository;

    private SmartRoutingService smartRoutingService;

    private BankRoute hdfc;
    private BankRoute icici;
    private BankRoute axis;

    @BeforeEach
    void setUp() {
        smartRoutingService = new SmartRoutingService(metricsRepository);

        hdfc = new BankRoute("hdfc-001", "HDFC Bank", 0.95, 120.0, 1.5, true);
        icici = new BankRoute("icici-001", "ICICI Bank", 0.88, 150.0, 1.2, true);
        axis = new BankRoute("axis-001", "Axis Bank", 0.80, 200.0, 1.0, true);
    }

    @Test
    @DisplayName("selectRoute - exploit should select bank with highest success rate")
    void selectRoute_Exploit_SelectsBestBank() {
        // Set epsilon to 0 to always exploit
        smartRoutingService.setEpsilon(0.0);
        when(metricsRepository.getActiveBankRoutes()).thenReturn(List.of(hdfc, icici, axis));

        RoutingDecision decision = smartRoutingService.selectRoute();

        assertThat(decision).isNotNull();
        assertThat(decision.selectedBank().getBankId()).isEqualTo("hdfc-001");
        assertThat(decision.selectedBank().getSuccessRate()).isEqualTo(0.95);
        assertThat(decision.isExplore()).isFalse();
    }

    @Test
    @DisplayName("selectRoute - explore should select a random bank")
    void selectRoute_Explore_SelectsRandomBank() {
        // Set epsilon to 1.0 to always explore
        smartRoutingService.setEpsilon(1.0);
        when(metricsRepository.getActiveBankRoutes()).thenReturn(List.of(hdfc, icici, axis));

        RoutingDecision decision = smartRoutingService.selectRoute();

        assertThat(decision).isNotNull();
        assertThat(decision.isExplore()).isTrue();
        assertThat(decision.selectedBank()).isIn(hdfc, icici, axis);
    }

    @Test
    @DisplayName("selectRoute - should throw IllegalStateException when no active banks")
    void selectRoute_NoBanks_ThrowsException() {
        when(metricsRepository.getActiveBankRoutes()).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> smartRoutingService.selectRoute())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active bank routes");
    }

    @Test
    @DisplayName("selectRoute - single bank should return that bank without explore/exploit logic")
    void selectRoute_SingleBank_ReturnsThatBank() {
        when(metricsRepository.getActiveBankRoutes()).thenReturn(List.of(icici));

        RoutingDecision decision = smartRoutingService.selectRoute();

        assertThat(decision).isNotNull();
        assertThat(decision.selectedBank().getBankId()).isEqualTo("icici-001");
        assertThat(decision.isExplore()).isFalse();
    }

    @Test
    @DisplayName("selectRoute - exploit with equal success rates prefers lower latency")
    void selectRoute_Exploit_EqualSuccessRate_PrefersLowerLatency() {
        smartRoutingService.setEpsilon(0.0);

        BankRoute bankA = new BankRoute("bank-a", "Bank A", 0.90, 100.0, 1.5, true);
        BankRoute bankB = new BankRoute("bank-b", "Bank B", 0.90, 200.0, 1.5, true);

        when(metricsRepository.getActiveBankRoutes()).thenReturn(List.of(bankA, bankB));

        RoutingDecision decision = smartRoutingService.selectRoute();

        assertThat(decision).isNotNull();
        // Bank A has lower latency, should be selected when success rates are equal
        assertThat(decision.selectedBank().getBankId()).isEqualTo("bank-a");
    }
}
