package ro.filegrid.simulator.web;

import java.time.Instant;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ro.filegrid.simulator.anomaly.Anomaly;
import ro.filegrid.simulator.anomaly.AnomalyInjector;
import ro.filegrid.simulator.engine.SimClock;
import ro.filegrid.simulator.engine.SimulationEngine;
import ro.filegrid.simulator.fleet.Fleet;
import ro.filegrid.simulator.send.Sender;

/**
 * Runtime control, e.g. switch from a normal day to a peak during a demo:
 * <pre>
 * curl -X PUT localhost:8090/api/control -H 'Content-Type: application/json' -d '{"speed": 10}'
 * curl -X PUT localhost:8090/api/control/anomalies -H 'Content-Type: application/json' -d '{"DUPLICATE": 0.2}'
 * </pre>
 */
@RestController
@RequestMapping("/api/control")
@ConditionalOnProperty(name = "simulator.mode", havingValue = "run", matchIfMissing = true)
public class ControlController {

    private final SimulationEngine engine;
    private final SimClock clock;
    private final Sender sender;
    private final AnomalyInjector anomalies;
    private final Fleet fleet;

    public ControlController(SimulationEngine engine, SimClock clock, Sender sender, AnomalyInjector anomalies, Fleet fleet) {
        this.engine = engine;
        this.clock = clock;
        this.sender = sender;
        this.anomalies = anomalies;
        this.fleet = fleet;
    }

    public record State(Instant simulatedTime, double speed, double effectiveSpeed, boolean paused, int meters,
                        String fleetFingerprint, double estimatedMessagesPerSecond, int pending, int inFlight,
                        int delayed, Map<Anomaly, Double> anomalyRates) {}

    public record Change(Double speed, Boolean paused) {}

    @GetMapping
    public State state() {
        return new State(clock.now(), clock.speed(), clock.effectiveSpeed(), engine.paused(), fleet.size(),
                fleet.fingerprint(), engine.estimatedMessagesPerSecond(), sender.pending(), sender.inFlight(),
                engine.delayedMessages(), anomalies.rates());
    }

    @PutMapping
    public State change(@RequestBody Change change) {
        if (change.speed() != null) clock.setSpeed(change.speed());
        if (change.paused() != null) engine.setPaused(change.paused());
        return state();
    }

    @PutMapping("/anomalies")
    public State anomalies(@RequestBody Map<Anomaly, Double> rates) {
        anomalies.updateRates(rates);
        return state();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
