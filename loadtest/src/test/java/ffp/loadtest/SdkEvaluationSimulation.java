package ffp.loadtest;

import io.gatling.javaapi.core.Simulation;

import static io.gatling.javaapi.core.CoreDsl.global;

/**
 * Steady-state SDK traffic: how fast is flag evaluation over HTTP once caches are warm?
 *
 * <pre>mvn gatling:test -Dsimulation=ffp.loadtest.SdkEvaluationSimulation -Drps=500 -Dduration=60</pre>
 */
public class SdkEvaluationSimulation extends Simulation {

    {
        setUp(SdkTraffic.evaluateAll(), SdkTraffic.evaluateOne())
                .protocols(SdkTraffic.protocol())
                .assertions(
                        global().failedRequests().percent().lt(0.1),
                        global().responseTime().percentile(95.0).lt(Config.P95_MS),
                        global().responseTime().percentile(99.0).lt(Config.P99_MS));
    }
}
