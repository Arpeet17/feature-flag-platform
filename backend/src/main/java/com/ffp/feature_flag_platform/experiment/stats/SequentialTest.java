package com.ffp.feature_flag_platform.experiment.stats;

/**
 * Mixture sequential probability ratio test (mSPRT) for a difference in conversion rates:
 * a test that stays valid when the data is checked again and again as it arrives.
 *
 * <p>A fixed-horizon z-test at α = 0.05 is only valid if it is run once. A guarded rollout
 * checks its guardrail every few seconds; re-running the z-test at every check ("peeking")
 * means one of the checks crosses p &lt; 0.05 by chance far more often than 5% of the time
 * (simulated: 22% of A/A rollouts with 15 checks, 50% with 240). This test instead
 * computes, at each check, the likelihood ratio Λ of "the rates differ" (effect drawn from a
 * normal mixture N(0, τ²)) against "they are equal". Under the null, Λ is a martingale, so by
 * Ville's inequality P(Λ ever reaches 1/α) ≤ α, however often it is checked. 1/Λ is an
 * "always valid" p-value.
 *
 * <p>Uses the normal approximation with the pooled variance (exact under the null, which is
 * what the false-positive guarantee needs). τ is set to {@value #MIXTURE_SD} standard
 * deviations of the metric: large regressions are rejected within a few hundred users; small
 * ones need more data, which is the price of being allowed to look continuously.
 *
 * @see <a href="https://arxiv.org/abs/1512.04922">Johari, Pekelis, Walsh: Always Valid Inference (2015)</a>
 */
public final class SequentialTest {

    /** Mixture standard deviation τ, in units of the metric's standard deviation. */
    public static final double MIXTURE_SD = 0.1;

    private SequentialTest() {
    }

    /**
     * @param logLikelihoodRatio ln Λ: evidence that the two rates differ (0 = none)
     * @param pValue             always-valid p-value, min(1, 1/Λ)
     * @param rejected           Λ ≥ 1/α: the rates differ, at level α under continuous monitoring
     * @param difference         treatment rate minus control rate
     */
    public record Result(double logLikelihoodRatio, double pValue, boolean rejected, double difference) {
    }

    public static Result conversion(long treatmentUsers, long treatmentConversions, long controlUsers,
                                    long controlConversions, double alpha) {
        if (treatmentUsers == 0 || controlUsers == 0) {
            return new Result(0, 1, false, 0);
        }
        double pt = (double) treatmentConversions / treatmentUsers;
        double pc = (double) controlConversions / controlUsers;
        double pooled = (double) (treatmentConversions + controlConversions) / (treatmentUsers + controlUsers);
        double variance = pooled * (1 - pooled);
        if (variance == 0) {
            return new Result(0, 1, false, pt - pc);
        }
        double v = variance * (1.0 / treatmentUsers + 1.0 / controlUsers); // variance of the difference estimate
        double tau2 = MIXTURE_SD * MIXTURE_SD * variance;
        double diff = pt - pc;
        double logLambda = 0.5 * Math.log(v / (v + tau2)) + diff * diff * tau2 / (2 * v * (v + tau2));
        return new Result(logLambda, Math.min(1, Math.exp(-logLambda)), logLambda >= Math.log(1 / alpha), diff);
    }
}
