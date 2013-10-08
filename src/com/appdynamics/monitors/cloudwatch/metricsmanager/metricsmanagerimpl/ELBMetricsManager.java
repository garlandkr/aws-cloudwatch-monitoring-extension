package com.appdynamics.monitors.cloudwatch.metricsmanager.metricsmanagerimpl;

import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsRequest;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;
import com.appdynamics.monitors.cloudwatch.metricsmanager.MetricsManager;
import com.singularity.ee.agent.systemagent.api.MetricWriter;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class ELBMetricsManager extends MetricsManager {

    private static final String NAMESPACE = "AWS/ELB";

    /**
     * Gather metrics for AWS/ELB
     * @return	Map     Map containing metrics
     */
    @Override
    public Map gatherMetrics() {
        List<com.amazonaws.services.cloudwatch.model.Metric> elbMetricsList = getMetrics(NAMESPACE, "LoadBalancerName", "AvailabilityZone");

        //Top level     -- Key = LoadBalancerName,      Value = HashMap of availability zones
        //Mid level     -- Key = AvailabilityZoneName,  Value = HashMap of Metrics
        //Bottom level  -- Key = MetricName,            Value = List of datapoints
        HashMap<String, HashMap<String, HashMap<String, List<Datapoint>>>> elbMetrics = new HashMap<String, HashMap<String,HashMap<String,List<Datapoint>>>>();

        for (com.amazonaws.services.cloudwatch.model.Metric metric : elbMetricsList) {
            List<Dimension> dimensions = metric.getDimensions();
            String loadBalancerName = dimensions.get(0).getValue();
            String availabilityZone = dimensions.get(1).getValue();
            if (!elbMetrics.containsKey(loadBalancerName)) {
                elbMetrics.put(loadBalancerName, new HashMap<String, HashMap<String, List<Datapoint>>>());
            }
            if (!elbMetrics.get(loadBalancerName).containsKey(availabilityZone)) {
                elbMetrics.get(loadBalancerName).put(availabilityZone, new HashMap<String, List<Datapoint>>());
            }
            if (!elbMetrics.get(loadBalancerName).get(availabilityZone).containsKey(metric.getMetricName())) {

                if (!amazonCloudWatchMonitor.isMetricDisabled(NAMESPACE, metric.getMetricName())) {
                    GetMetricStatisticsRequest getMetricStatisticsRequest = createGetMetricStatisticsRequest(NAMESPACE, metric.getMetricName(), "Average", dimensions);
                    GetMetricStatisticsResult getMetricStatisticsResult = awsCloudWatch.getMetricStatistics(getMetricStatisticsRequest);
                    elbMetrics.get(loadBalancerName).get(availabilityZone).put(metric.getMetricName(), getMetricStatisticsResult.getDatapoints());
                }
            }
        }

        return elbMetrics;
    }

    /**
     * Print metrics for AWS/ELB
     * @param metrics   Map containing metrics
     */
    @Override
    public void printMetrics(Map metrics) {
        HashMap<String, HashMap<String, HashMap<String, List<Datapoint>>>> elbMetrics = (HashMap<String, HashMap<String,HashMap<String,List<Datapoint>>>>) metrics;
        Iterator loadBalancerIterator = elbMetrics.keySet().iterator();

        while (loadBalancerIterator.hasNext()) {
            String loadBalancerName = loadBalancerIterator.next().toString();
            HashMap<String, HashMap<String,List<Datapoint>>> availabilityZones = elbMetrics.get(loadBalancerName);
            Iterator zoneIterator = availabilityZones.keySet().iterator();
            while (zoneIterator.hasNext()) {
                String zoneName = zoneIterator.next().toString();
                HashMap<String, List<Datapoint>> metricsMap = availabilityZones.get(zoneName);
                Iterator metricsIterator = metricsMap.keySet().iterator();
                while (metricsIterator.hasNext()) {
                    String metricName = metricsIterator.next().toString();
                    List<Datapoint> datapoints = metricsMap.get(metricName);
                    if (datapoints != null && datapoints.size() > 0) {
                        Datapoint data = datapoints.get(0);
                        amazonCloudWatchMonitor.printMetric(getNamespacePrefix() + loadBalancerName + "|" + "Availability Zone|" +  zoneName + "|",metricName + "(" + data.getUnit() + ")", data.getAverage(),
                                MetricWriter.METRIC_AGGREGATION_TYPE_OBSERVATION,
                                MetricWriter.METRIC_TIME_ROLLUP_TYPE_AVERAGE,
                                MetricWriter.METRIC_CLUSTER_ROLLUP_TYPE_INDIVIDUAL);

                    }
                }
            }
        }
    }

    /**
     * Construct namespace prefix for AWS/ELB
     * @return String   Namespace prefix
     */
    @Override
    public String getNamespacePrefix() {
        return NAMESPACE.substring(4,NAMESPACE.length()) + "|" + "Load Balancer Name|";
    }
}