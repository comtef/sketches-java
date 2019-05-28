package com.datadoghq.sketch.gk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

public class GKArray {

    static class Entry {

        double v;
        long g;
        long delta;

        Entry(double v, long g, long delta) {
            this.v = v;
            this.g = g;
            this.delta = delta;
        }
    }

    private final double rankAccuracy;
    private ArrayList<Entry> entries;
    private final double[] incoming;
    private int incomingIndex;
    private long compressedCount;
    private double minValue;

    public GKArray(double rankAccuracy) {
        this.rankAccuracy = rankAccuracy;
        this.entries = new ArrayList<>();
        this.incoming = new double[(int) (1 / rankAccuracy) + 1];
        this.incomingIndex = 0;
        this.minValue = Double.MAX_VALUE;
        this.compressedCount = 0L;
    }

    public GKArray(GKArray sketch) {
        this.rankAccuracy = sketch.rankAccuracy;
        this.entries = new ArrayList<>(sketch.entries);
        this.incoming = Arrays.copyOf(sketch.incoming, sketch.incoming.length);
        this.incomingIndex = sketch.incomingIndex;
        this.compressedCount = sketch.compressedCount;
        this.minValue = sketch.minValue;
    }

    public double getRankAccuracy() {
        return rankAccuracy;
    }

    public boolean isEmpty() {
        return entries.isEmpty() && incomingIndex == 0;
    }

    public long getTotalCount() {
        if (incomingIndex > 0) {
            compress();
        }
        return compressedCount;
    }

    public double getMinValue() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        compressIfNecessary();
        return minValue;
    }

    public double getMaxValue() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        compressIfNecessary();
        return entries.get(entries.size() - 1).v;
    }

    public void add(double value) {
        incoming[incomingIndex++] = value;
        if (incomingIndex == incoming.length) {
            compress();
        }
    }

    public void mergeWith(GKArray other) {

        if (rankAccuracy != other.rankAccuracy) {
            throw new IllegalArgumentException("The sketches don't have the same accuracy parameter.");
        }

        if (other.isEmpty()) {
            return;
        }

        if (isEmpty()) {
            entries = new ArrayList<>(other.entries);
            System.arraycopy(other.incoming, 0, incoming, 0, other.incomingIndex);
            incomingIndex = other.incomingIndex;
            compressedCount = other.compressedCount;
            minValue = other.minValue;
            return;
        }

        other.compressIfNecessary();

        final long spread = (long) (other.rankAccuracy * (other.compressedCount - 1));

        final List<Entry> incomingEntries = new ArrayList<>(other.entries.size() + 1);

        long n;
        if ((n = other.entries.get(0).g + other.entries.get(0).delta - spread - 1) > 0) {
            incomingEntries.add(new Entry(other.minValue, n, 0));
        } else {
            minValue = Math.min(minValue, other.minValue);
        }

        for (int i = 0; i < other.entries.size() - 1; i++) {
            incomingEntries.add(new Entry(
                    other.entries.get(i).v,
                    other.entries.get(i + 1).g + other.entries.get(i + 1).delta - other.entries.get(i).delta,
                    0
            ));
        }

        incomingEntries.add(new Entry(
                other.entries.get(other.entries.size() - 1).v,
                spread + 1,
                0
        ));

        compress(incomingEntries, other.compressedCount);
    }

    public double getValueAtQuantile(double quantile) {

        if (quantile < 0 || quantile > 1) {
            throw new IllegalArgumentException("Invalid quantile.");
        }

        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        compressIfNecessary();

        if (quantile == 0) { // TODO why is that necessary?
            return minValue;
        }

        final long rank = (long) (quantile * (compressedCount - 1)) + 1;
        final long spread = (long) (rankAccuracy * (compressedCount - 1));
        long gSum = 0;
        int i;
        for (i = 0; i < entries.size(); i++) {
            gSum += entries.get(i).g;
            if (gSum + entries.get(i).delta > rank + spread) { //TODO +1 ?
                break;
            }
        }

        if (i == 0) {
            return minValue;
        } else {
            return entries.get(i - 1).v;
        }
    }

    private void compressIfNecessary() {
        if (incomingIndex > 0) {
            compress();
        }
    }

    public void compress() {
        compress(new ArrayList<>(), 0L);
    }

    private void compress(List<Entry> additionalEntries, long additionalCount) {

        for (int i = 0; i < incomingIndex; i++) {
            additionalEntries.add(new Entry(incoming[i], 1, 0));
        }
        additionalEntries.sort(Comparator.comparingDouble(e -> e.v));

        compressedCount += additionalCount + incomingIndex;
        if (!additionalEntries.isEmpty()) {
            minValue = Math.min(minValue, additionalEntries.get(0).v);
        }

        final long removalThreshold = 2 * (long) (rankAccuracy * (compressedCount - 1));
        final ArrayList<Entry> mergedEntries = new ArrayList<>(entries.size() + additionalEntries.size() / 3);

        int i = 0, j = 0;
        while (i < additionalEntries.size() || j < entries.size()) {

            if (i == additionalEntries.size()) {

                if (j + 1 < entries.size() &&
                        entries.get(j).g + entries.get(j + 1).g + entries.get(j + 1).delta <= removalThreshold) {
                    // Removable from sketch.
                    entries.get(j + 1).g += entries.get(j).g;
                } else {
                    mergedEntries.add(entries.get(j));
                }

                j++;

            } else if (j == entries.size()) {

                // Done with sketch; now only considering incoming.
                if (i + 1 < additionalEntries.size() &&
                        additionalEntries.get(i).g + additionalEntries.get(i + 1).g + additionalEntries.get(i + 1).delta
                                <= removalThreshold) {
                    // Removable from incoming.
                    additionalEntries.get(i + 1).g += additionalEntries.get(i).g;
                } else {
                    mergedEntries.add(additionalEntries.get(i));
                }

                i++;

            } else if (additionalEntries.get(i).v < entries.get(j).v) {

                if (additionalEntries.get(i).g + entries.get(j).g + entries.get(j).delta <= removalThreshold) {
                    entries.get(j).g += additionalEntries.get(i).g;
                } else {
                    additionalEntries.get(i).delta = entries.get(j).g + entries.get(j).delta - additionalEntries.get(i).g;
                    mergedEntries.add(additionalEntries.get(i));
                }

                i++;

            } else {

                if (j + 1 < entries.size() &&
                        entries.get(j).g + entries.get(j + 1).g + entries.get(j + 1).delta <= removalThreshold) {
                    // Removable from sketch.
                    entries.get(j + 1).g += entries.get(j).g;
                } else {
                    mergedEntries.add(entries.get(j));
                }

                j++;

            }
        }

        entries = mergedEntries;
        incomingIndex = 0;
    }

    public int getNumEntries() {
        return entries.size();
    }
}
