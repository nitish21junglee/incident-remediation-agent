package com.hackathon.incident_remediation_agent.evidence;

import java.util.List;

/**
 * One SignalFlow program's result for an incident: what it aggregated to either side of the
 * trigger, plus the points exactly as SignalFx returned them.
 *
 * <p>{@code rawPoints} is persisted so a run can be re-read after the fact. It is deliberately not
 * put in front of the model: a six-hour window at one-minute resolution is hundreds of points per
 * series, and several programs of that would swamp the source files in the prompt. The model gets
 * {@link #before} and {@link #during} instead.
 *
 * @param program    the {@code SignalFxProgram} name
 * @param filter     the SignalFlow filter clause it ran with, which says whether the program was
 *                   scoped by {@code service.name} or by {@code k8s.namespace.name}
 * @param pointCount points returned before the label filtering that {@code before} and
 *                   {@code during} apply, so zero here distinguishes "no data" from "genuinely 0"
 * @param rawPoints  the returned JSON array, or null when it was too large to store
 * @param error      why this program produced nothing, or null when it succeeded
 */
public record SignalFxExport(
    String program,
    String filter,
    int pointCount,
    Aggregate before,
    Aggregate during,
    String rawPoints,
    String error
) {

    public static SignalFxExport failed(String program, String filter, String error) {
        return new SignalFxExport(program, filter, 0, Aggregate.EMPTY, Aggregate.EMPTY, null, error);
    }

    /**
     * Counters are read through {@link #sum}, gauges through {@link #mean}, and {@link #max}
     * catches a single hot pod that an average across pods would hide.
     */
    public record Aggregate(double sum, double mean, double max) {

        public static final Aggregate EMPTY = new Aggregate(0, 0, 0);

        public static Aggregate of(List<Double> values) {
            if (values == null || values.isEmpty()) {
                return EMPTY;
            }
            return new Aggregate(
                values.stream().mapToDouble(Double::doubleValue).sum(),
                values.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                values.stream().mapToDouble(Double::doubleValue).max().orElse(0));
        }
    }
}
