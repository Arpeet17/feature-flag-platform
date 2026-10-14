package com.ffp.feature_flag_platform.experiment.stats;

/**
 * Probability distributions needed for experiment analysis, implemented from first principles
 * (no math library dependency). Accuracy is far beyond what decision thresholds need; each
 * function is tested against published reference values.
 */
public final class Distributions {

    private static final double EPS = 1e-15;
    private static final int MAX_ITERATIONS = 500;

    private Distributions() {
    }

    // ------------------------------------------------------------------ normal

    /** Standard normal CDF via the complementary error function. */
    public static double normalCdf(double z) {
        return 0.5 * erfc(-z / Math.sqrt(2));
    }

    /** Two-sided p-value for a z statistic. */
    public static double normalTwoSidedP(double z) {
        return erfc(Math.abs(z) / Math.sqrt(2));
    }

    /**
     * Complementary error function: Maclaurin series of erf below 2 (where 1 - erf loses no
     * meaningful precision), Laplace's continued fraction above (evaluated backwards). Relative
     * error is below 1e-12 across the range, see DistributionsTest.
     */
    static double erfc(double x) {
        if (x < 0) {
            return 2 - erfc(-x);
        }
        return x < 2 ? 1 - erf(x) : continuedFractionErfc(x);
    }

    private static double erf(double x) {
        double sum = x;
        double term = x;
        double x2 = x * x;
        for (int n = 1; n < 200; n++) {
            term *= -x2 / n;
            double delta = term / (2 * n + 1);
            sum += delta;
            if (Math.abs(delta) < EPS * Math.abs(sum)) {
                break;
            }
        }
        return 2 / Math.sqrt(Math.PI) * sum;
    }

    private static double continuedFractionErfc(double x) {
        double f = 0;
        for (int n = 120; n >= 1; n--) {
            f = n / 2.0 / (x + f);
        }
        return Math.exp(-x * x) / Math.sqrt(Math.PI) / (x + f);
    }

    /** Inverse standard normal CDF (Acklam's algorithm, refined with one Halley step). */
    public static double normalQuantile(double p) {
        if (p <= 0 || p >= 1) {
            throw new IllegalArgumentException("p must be in (0, 1)");
        }
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02,
                -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01,
                -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00,
                4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double pLow = 0.02425;
        double x;
        if (p < pLow) {
            double q = Math.sqrt(-2 * Math.log(p));
            x = (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        } else if (p <= 1 - pLow) {
            double q = p - 0.5;
            double r = q * q;
            x = (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                    / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
        } else {
            double q = Math.sqrt(-2 * Math.log(1 - p));
            x = -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        double e = normalCdf(x) - p;
        double u = e * Math.sqrt(2 * Math.PI) * Math.exp(x * x / 2);
        return x - u / (1 + x * u / 2);
    }

    // ------------------------------------------------------------------ chi-square (via regularized gamma)

    /** Survival function P(X > x) of a chi-square distribution with k degrees of freedom. */
    public static double chiSquareSurvival(double x, int k) {
        if (x <= 0) {
            return 1;
        }
        return regularizedGammaQ(k / 2.0, x / 2.0);
    }

    static double regularizedGammaQ(double a, double x) {
        if (x < a + 1) {
            return 1 - gammaSeries(a, x);
        }
        return gammaContinuedFraction(a, x);
    }

    private static double gammaSeries(double a, double x) {
        double sum = 1 / a;
        double term = sum;
        for (int n = 1; n < MAX_ITERATIONS; n++) {
            term *= x / (a + n);
            sum += term;
            if (Math.abs(term) < Math.abs(sum) * EPS) {
                break;
            }
        }
        return sum * Math.exp(-x + a * Math.log(x) - logGamma(a));
    }

    private static double gammaContinuedFraction(double a, double x) {
        // Modified Lentz's method.
        double tiny = 1e-300;
        double b = x + 1 - a;
        double c = 1 / tiny;
        double d = 1 / b;
        double h = d;
        for (int i = 1; i < MAX_ITERATIONS; i++) {
            double an = -i * (i - a);
            b += 2;
            d = an * d + b;
            if (Math.abs(d) < tiny) d = tiny;
            c = b + an / c;
            if (Math.abs(c) < tiny) c = tiny;
            d = 1 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1) < EPS) {
                break;
            }
        }
        return Math.exp(-x + a * Math.log(x) - logGamma(a)) * h;
    }

    /** Lanczos approximation of ln Γ(x), accurate to ~1e-15 for x > 0. */
    static double logGamma(double x) {
        double[] g = {0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
                -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};
        if (x < 0.5) {
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - logGamma(1 - x);
        }
        x -= 1;
        double sum = g[0];
        for (int i = 1; i < g.length; i++) {
            sum += g[i] / (x + i);
        }
        double t = x + 7.5;
        return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(sum);
    }

    // ------------------------------------------------------------------ Student's t (via regularized beta)

    /** Two-sided p-value for a t statistic with (possibly fractional) degrees of freedom. */
    public static double studentTTwoSidedP(double t, double df) {
        if (Double.isInfinite(df) || df > 1e7) {
            return normalTwoSidedP(t);
        }
        return regularizedBeta(df / (df + t * t), df / 2, 0.5);
    }

    static double regularizedBeta(double x, double a, double b) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        double logFront = logGamma(a + b) - logGamma(a) - logGamma(b) + a * Math.log(x) + b * Math.log(1 - x);
        if (x < (a + 1) / (a + b + 2)) {
            return Math.exp(logFront) * betaContinuedFraction(x, a, b) / a;
        }
        return 1 - Math.exp(logFront) * betaContinuedFraction(1 - x, b, a) / b;
    }

    private static double betaContinuedFraction(double x, double a, double b) {
        double tiny = 1e-300;
        double c = 1;
        double d = 1 - (a + b) * x / (a + 1);
        if (Math.abs(d) < tiny) d = tiny;
        d = 1 / d;
        double h = d;
        for (int m = 1; m < MAX_ITERATIONS; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((a + m2 - 1) * (a + m2));
            d = 1 + aa * d;
            if (Math.abs(d) < tiny) d = tiny;
            c = 1 + aa / c;
            if (Math.abs(c) < tiny) c = tiny;
            d = 1 / d;
            h *= d * c;
            aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1));
            d = 1 + aa * d;
            if (Math.abs(d) < tiny) d = tiny;
            c = 1 + aa / c;
            if (Math.abs(c) < tiny) c = tiny;
            d = 1 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1) < EPS) {
                break;
            }
        }
        return h;
    }
}
