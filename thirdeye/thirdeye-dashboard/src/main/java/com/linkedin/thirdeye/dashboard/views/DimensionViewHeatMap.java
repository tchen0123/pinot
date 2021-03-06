package com.linkedin.thirdeye.dashboard.views;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.thirdeye.dashboard.api.HeatMap;
import com.linkedin.thirdeye.dashboard.api.HeatMapCell;
import com.linkedin.thirdeye.dashboard.api.QueryResult;
import com.linkedin.thirdeye.dashboard.util.SnapshotUtils;
import io.dropwizard.views.View;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class DimensionViewHeatMap extends View {
  private static final Logger LOGGER = LoggerFactory.getLogger(DimensionViewHeatMap.class);
  private static final TypeReference<List<String>> LIST_TYPE_REFERENCE = new TypeReference<List<String>>(){};
  private final ObjectMapper objectMapper;
  private final List<HeatMap> heatMaps;

  public DimensionViewHeatMap(ObjectMapper objectMapper, Map<String, QueryResult> queryResults) throws Exception {
    super("dimension/heat-map.ftl");
    this.objectMapper = objectMapper;
    this.heatMaps = new ArrayList<>();

    for (Map.Entry<String, QueryResult> entry : queryResults.entrySet()) {
      this.heatMaps.addAll(generateHeatMaps(entry.getKey(), entry.getValue()));
    }

    Collections.sort(heatMaps);
  }

  public List<HeatMap> getHeatMaps() {
    return heatMaps;
  }

  private List<HeatMap> generateHeatMaps(String dimension, QueryResult queryResult) throws Exception {
    int dimensionIdx = queryResult.getDimensions().indexOf(dimension);

    if (queryResult.getData().isEmpty()) {
      return Collections.emptyList();
    }

    // Snapshot
    Map<String, Set<String>> snapshotValues = new HashMap<>();
    for (String metricName : queryResult.getMetrics()) {
      snapshotValues.put(metricName, new HashSet<String>());
    }
    try {
      String[][] snapshot = SnapshotUtils.snapshot(2, queryResult); // show top 2 movers
      for (int i = 0; i < queryResult.getMetrics().size(); i++) {
        String[] snapshotCombinations = snapshot[i];
        String metricName = queryResult.getMetrics().get(i);
        for (String combinationString : snapshotCombinations) {
          if (SnapshotUtils.REST.equals(combinationString)) {
            continue;
          }
          List<String> combination = objectMapper.readValue(combinationString.getBytes(), LIST_TYPE_REFERENCE);
          String value = combination.get(dimensionIdx);
          snapshotValues.get(metricName).add(value);
        }
      }
      LOGGER.info("snapshotValues={}", snapshotValues);
    } catch (Exception e) {
      LOGGER.error("Error generating snapshot", e);
    }

    // Initialize metric info
    Map<String, List<HeatMapCell>> allCells = new HashMap<>();
    Map<String, DescriptiveStatistics> allBaselineStats = new HashMap<>();
    Map<String, DescriptiveStatistics> allCurrentStats = new HashMap<>();
    for (int i = 0; i < queryResult.getMetrics().size(); i++) {
      String metric = queryResult.getMetrics().get(i);
      allCells.put(metric, new ArrayList<HeatMapCell>());
      allBaselineStats.put(metric, new DescriptiveStatistics());
      allCurrentStats.put(metric, new DescriptiveStatistics());
    }

    for (Map.Entry<String, Map<String, Number[]>> entry : queryResult.getData().entrySet()) {
      List<String> combination = objectMapper.readValue(entry.getKey(), LIST_TYPE_REFERENCE);
      String value = combination.get(dimensionIdx);

      // Find min / max times (for current / baseline)
      long minTime = -1;
      long maxTime = -1;
      for (String timeString : entry.getValue().keySet()) {
        long time = Long.valueOf(timeString);
        if (minTime == -1 || time < minTime) {
          minTime = time;
        }
        if (maxTime == -1 || time > maxTime) {
          maxTime = time;
        }
      }

      // Add local stats
      for (int i = 0; i < queryResult.getMetrics().size(); i++) {
        String metric = queryResult.getMetrics().get(i);
        Number baselineValue = getMetricValue(entry.getValue().get(String.valueOf(minTime)), i);
        Number currentValue = getMetricValue(entry.getValue().get(String.valueOf(maxTime)), i);

        if (baselineValue != null) {
          allBaselineStats.get(metric).addValue(baselineValue.doubleValue());
        }

        if (currentValue != null) {
          allCurrentStats.get(metric).addValue(currentValue.doubleValue());
        }

        HeatMapCell cell = new HeatMapCell(objectMapper, value);
        cell.addStat(baselineValue);
        cell.addStat(currentValue);

        allCells.get(metric).add(cell);
      }
    }

    List<HeatMap> heatMaps = new ArrayList<>();
    for (Map.Entry<String, List<HeatMapCell>> entry : allCells.entrySet()) {
      String metric = entry.getKey();
      DescriptiveStatistics baselineStats = allBaselineStats.get(metric);
      DescriptiveStatistics currentStats = allCurrentStats.get(metric);
      NormalDistribution baselineDist = getDistribution(baselineStats);
      NormalDistribution currentDist = getDistribution(currentStats);

      // Add global stats
      for (HeatMapCell cell : entry.getValue()) {
        Number baseline = cell.getStats().get(0);
        Number current = cell.getStats().get(1);

        // Baseline p-value
        if (baseline == null || baselineDist == null) {
          cell.addStat(null);
        } else {
          cell.addStat(baselineDist.cumulativeProbability(baseline.doubleValue()));
        }

        // Current p-value
        if (current == null || currentDist == null) {
          cell.addStat(null);
        } else {
          cell.addStat(currentDist.cumulativeProbability(current.doubleValue()));
        }

        // Contribution difference
        if (baselineStats.getSum() > 0 && currentStats.getSum() > 0) {
          double currentContribution = current == null ? 0 : current.doubleValue() / currentStats.getSum();
          double baselineContribution = baseline == null ? 0 : baseline.doubleValue() / baselineStats.getSum();
          cell.addStat(currentContribution - baselineContribution);
        } else {
          cell.addStat(null);
        }

        // Volume difference
        if (baselineStats.getSum() > 0) {
          double currentValue = current == null ? 0 : current.doubleValue();
          double baselineValue = baseline == null ? 0 : baseline.doubleValue();
          cell.addStat((currentValue - baselineValue) / baselineStats.getSum());
        } else {
          cell.addStat(null);
        }

        // Snapshot category (i.e. is one of the biggest movers)
        if (snapshotValues.get(metric).contains(cell.getValue())) {
          cell.addStat(1);
        } else {
          cell.addStat(0);
        }
      }

      heatMaps.add(new HeatMap(objectMapper, entry.getKey(), dimension, entry.getValue(), Arrays.asList(
          "baseline_value",
          "current_value",
          "baseline_cdf_value",
          "current_cdf_value",
          "contribution_difference",
          "volume_difference",
          "snapshot_category"
      )));
    }

    return heatMaps;
  }

  private static Number getMetricValue(Number[] metrics, int idx) {
    if (metrics == null) {
      return null;
    }
    return metrics[idx];
  }

  private static NormalDistribution getDistribution(DescriptiveStatistics stats) {
    NormalDistribution dist = null;
    try {
      dist = new NormalDistribution(stats.getMean(), stats.getStandardDeviation());
    } catch (Exception e) {
      LOGGER.warn("Could not get statistics", e);
    }
    return dist;
  }
}
