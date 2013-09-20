package com.appdynamics.monitors.amazon;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.autoscaling.AmazonAutoScaling;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient;
import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult;
import com.amazonaws.services.autoscaling.model.EnableMetricsCollectionRequest;
import com.amazonaws.services.autoscaling.model.EnabledMetric;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClient;
import com.amazonaws.services.cloudwatch.model.*;
import com.singularity.ee.agent.systemagent.api.AManagedMonitor;
import com.singularity.ee.agent.systemagent.api.MetricWriter;
import com.singularity.ee.agent.systemagent.api.TaskExecutionContext;
import com.singularity.ee.agent.systemagent.api.TaskOutput;
import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import sun.tools.java.ClassPath;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class AmazonCloudWatchMonitor extends AManagedMonitor {

    private static final String AWS_ACCESS_KEY = "accessKey";
    private static final String AWS_SECRET_KEY = "secretKey";
    private Logger logger = Logger.getLogger(this.getClass().getName());
    private static AmazonCloudWatchMonitor sInstance = null;

    // The AWS Cloud Watch client that retrieves instance metrics by executing requests
    private AmazonCloudWatch awsCloudWatch;
    // This HashSet of disabled metrics is populated by reading the DisabledMetrics.xml file
    private Set<String> disabledMetrics = new HashSet<String>();

    public AmazonCloudWatchMonitor() {
        //setDisabledMetrics();
        initCloudWatchClient();
    }

    /**
     * Set list of disabled metrics from xml file
     * @return
     */
    private void setDisabledMetrics() {
        try {
            //File disabledMetricsFile = new File("conf/DisabledMetrics.xml");
            File disabledMetricsFile = new File("/mnt/appdynamics/machineagent/monitors/AmazonMonitor/conf/DisabledMetrics.xml");

            //InputStream disabledMetricsFile = ClassLoader.getSystemResourceAsStream("conf/DisabledMetrics.xml");
            //InputStream disabledMetricsFile = this.getClass().getResourceAsStream("/conf/DisabledMetrics.xml");
            String strClassPath = System.getProperty("java.class.path");
            logger.error("Classpath is " + strClassPath);
            if (disabledMetricsFile == null) {
                logger.error("Disabled File is null");
            }
            //InputStream disabledMetricsFile = getClass().getClassLoader().getResourceAsStream("DisabledMetrics.xml");
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(disabledMetricsFile);
            NodeList nList = doc.getElementsByTagName("MetricName");
            logger.error("Number of disabled metrics: " + nList.getLength());
            for (int i = 0; i < nList.getLength(); i++) {
                disabledMetrics.add(nList.item(i).getTextContent());
            }
        }
        catch(Exception e) {
            logger.error(e);
        }
    }

    /**
     * Initialize Amazon Cloud Watch Client
     * @return
     */
    private void initCloudWatchClient() {
//        Properties awsProperties = new Properties();
//        try {
//            //awsProperties.load(new FileInputStream("AwsCredentials.properties"));
//
//            awsProperties.load(getClass().getClassLoader().getResourceAsStream("AwsCredentials.properties"));
//            //awsProperties.load(ClassLoader.getSystemResourceAsStream("AwsCredentials.properties"));
//
//        }
//        catch(IOException e) {
//            e.printStackTrace();
//        }
//        //AWSCredentials awsCredentials = new BasicAWSCredentials(awsProperties.getProperty(AWS_ACCESS_KEY), awsProperties.getProperty(AWS_SECRET_KEY));
        AWSCredentials awsCredentials = new BasicAWSCredentials("AKIAJTB7DYHGUBXOS7BQ", "jbW+aoHbYjFHSoTKrp+U1LEzdMZpvuGLETZuiMyc");
        awsCloudWatch = new AmazonCloudWatchClient(awsCredentials);
    }

    /**
     * Main execution method that uploads the metrics to the AppDynamics Controller
     * @see com.singularity.ee.agent.systemagent.api.ITask#execute(java.util.Map, com.singularity.ee.agent.systemagent.api.TaskExecutionContext)
     */
    @Override
    public TaskOutput execute(Map<String, String> stringStringMap, TaskExecutionContext taskExecutionContext) {
        // gather instance metrics
        HashMap<String, HashMap<String, List<Datapoint>>> instanceMetrics = gatherInstanceMetrics();

        // gather auto-scaling metrics
        HashMap<String, HashMap<String,List<Datapoint>>> autoscalingMetrics = gatherAutoScalingMetrics();

        // print metrics to controller
        printInstanceMetrics(instanceMetrics);

        // print auto-scaling metrics to controller
        printAutoScalingMetrics(autoscalingMetrics);

        return new TaskOutput("AWS Cloud Watch Metric Upload Complete");
    }

    /**
     * Gather metrics for every instance and group by instanceId. Populates global map called cloudWatchMetrics
     */
    private HashMap<String, HashMap<String, List<Datapoint>>> gatherInstanceMetrics() {
        // The outer hashmap has instanceIds as keys and inner hashmaps as the value.
        // The inner hashmaps have metric names as keys and lists of corresponding data points as the values
        HashMap<String, HashMap<String, List<Datapoint>>> cloudWatchMetrics = new HashMap<String, HashMap<String,List<Datapoint>>>();

        List<DimensionFilter> filter = new ArrayList<DimensionFilter>();
        DimensionFilter instanceIdFilter = new DimensionFilter();
        instanceIdFilter.setName("InstanceId");
        filter.add(instanceIdFilter);
        ListMetricsRequest listMetricsRequest = new ListMetricsRequest();
        listMetricsRequest.setDimensions(filter);
        ListMetricsResult instanceMetricsResult = awsCloudWatch.listMetrics(listMetricsRequest);
        List<com.amazonaws.services.cloudwatch.model.Metric> instanceMetrics = instanceMetricsResult.getMetrics();

        for (com.amazonaws.services.cloudwatch.model.Metric m : instanceMetrics) {
            List<Dimension> dimensions = m.getDimensions();
            for (Dimension dimension : dimensions) {
                if (!cloudWatchMetrics.containsKey(dimension.getValue())) {
                    cloudWatchMetrics.put(dimension.getValue(), new HashMap<String,List<Datapoint>>());
                }
                gatherInstanceMetricsHelper(m, dimension, cloudWatchMetrics);
            }
        }
        return cloudWatchMetrics;
    }

    private void gatherInstanceMetricsHelper(com.amazonaws.services.cloudwatch.model.Metric metric,
                                             Dimension dimension,
                                             HashMap<String, HashMap<String, List<Datapoint>>> cloudWatchMetrics) {
        if (disabledMetrics.contains(metric.getMetricName())) {
            return;
        }
        GetMetricStatisticsRequest getMetricStatisticsRequest = createGetMetricStatisticsRequest(metric);
        GetMetricStatisticsResult getMetricStatisticsResult = awsCloudWatch.getMetricStatistics(getMetricStatisticsRequest);
        cloudWatchMetrics.get(dimension.getValue()).put(metric.getMetricName(), getMetricStatisticsResult.getDatapoints());
    }

    private GetMetricStatisticsRequest createGetMetricStatisticsRequest(com.amazonaws.services.cloudwatch.model.Metric m) {
        GetMetricStatisticsRequest getMetricStatisticsRequest = new GetMetricStatisticsRequest()
                .withStartTime(new Date(new Date().getTime() - 1000000000))
                .withNamespace("AWS/EC2")
                .withPeriod(60 * 60)
                .withMetricName(m.getMetricName())
                .withStatistics("Average")
                .withEndTime(new Date());
        return getMetricStatisticsRequest;
    }

    private HashMap<String,HashMap<String,List<Datapoint>>> gatherAutoScalingMetrics() {
        HashMap<String, HashMap<String,List<Datapoint>>> autoScalingMetrics = new HashMap<String,HashMap<String,List<Datapoint>>>();
        AWSCredentials awsCredentials = new BasicAWSCredentials("AKIAJTB7DYHGUBXOS7BQ", "jbW+aoHbYjFHSoTKrp+U1LEzdMZpvuGLETZuiMyc");
        AmazonAutoScalingClient amazonAutoScalingClient = new AmazonAutoScalingClient(awsCredentials);
        DescribeAutoScalingGroupsResult describeAutoScalingGroupsResult = amazonAutoScalingClient.describeAutoScalingGroups();
        List<AutoScalingGroup> autoScalingGroupList = describeAutoScalingGroupsResult.getAutoScalingGroups();
        for (AutoScalingGroup autoScalingGroup : autoScalingGroupList) {
            String groupName = autoScalingGroup.getAutoScalingGroupName();
            if (!autoScalingMetrics.containsKey(groupName)) {
                autoScalingMetrics.put(groupName, new HashMap<String,List<Datapoint>>());
                //TODO: remove this. Ask Pranta
                EnableMetricsCollectionRequest request = new EnableMetricsCollectionRequest();
                request.setAutoScalingGroupName(groupName);
                request.setGranularity("1Minute");
                amazonAutoScalingClient.enableMetricsCollection(request);

            }
            gatherAutoScalingMetricsHelper(autoScalingGroup, autoScalingMetrics);
        }

        return autoScalingMetrics;
    }

    private void gatherAutoScalingMetricsHelper(AutoScalingGroup currentGroup, HashMap<String,HashMap<String,List<Datapoint>>> autoScalingMetrics) {
        HashMap<String,List<Datapoint>> groupMetrics = autoScalingMetrics.get(currentGroup.getAutoScalingGroupName());
        List<EnabledMetric> enabledMetrics = currentGroup.getEnabledMetrics();
        for (EnabledMetric m : enabledMetrics) {
            GetMetricStatisticsRequest getMetricStatisticsRequest = new GetMetricStatisticsRequest()
                    .withStartTime( new Date( System.currentTimeMillis() - TimeUnit.MINUTES.toMillis( 2 ) ) )
                    .withNamespace("AWS/AutoScaling")
                    .withPeriod(60 * 60)
                    .withDimensions(new Dimension().withName("AutoScalingGroupName").withValue(currentGroup.getAutoScalingGroupName()))
                    .withMetricName(m.getMetric())
                    .withStatistics("Average")
                    .withEndTime(new Date());
            GetMetricStatisticsResult getMetricStatisticsResult = awsCloudWatch.getMetricStatistics(getMetricStatisticsRequest);
            List<Datapoint> datapoints = getMetricStatisticsResult.getDatapoints();
            groupMetrics.put(m.getMetric(), datapoints);
        }
    }

    /**
     * Returns the metric to the AppDynamics Controller.
     * @param 	metricName		Name of the Metric
     * @param 	metricValue		Value of the Metric
     * @param 	aggregation		Average OR Observation OR Sum
     * @param 	timeRollup		Average OR Current OR Sum
     * @param 	cluster			Collective OR Individual
     */
    public void printMetric(String metricName, double metricValue, String aggregation, String timeRollup, String cluster)
    {
        try{
            MetricWriter metricWriter = getMetricWriter(getMetricPrefix() + metricName,
                    aggregation,
                    timeRollup,
                    cluster
            );

            metricWriter.printMetric(String.valueOf((long) metricValue));
        } catch (NullPointerException e){
            logger.info("NullPointerException: " + e.getMessage());
        }
    }

    /**
     * Metric Prefix
     * @return	String
     */
    private String getMetricPrefix()
    {
        return "Custom Metrics|Amazon Cloud Watch|";
    }

    private void printInstanceMetrics(HashMap<String, HashMap<String, List<Datapoint>>> cloudWatchMetrics) {
        Iterator outerIterator = cloudWatchMetrics.keySet().iterator();

        while (outerIterator.hasNext()) {
            String instanceId = outerIterator.next().toString();
            HashMap<String, List<Datapoint>> metricStatistics = cloudWatchMetrics.get(instanceId);
            Iterator innerIterator = metricStatistics.keySet().iterator();
            while (innerIterator.hasNext()) {
                String metricName = innerIterator.next().toString();
                List<Datapoint> datapoints = metricStatistics.get(metricName);
                if (datapoints != null && !datapoints.isEmpty()) {
                    Datapoint data = datapoints.get(0);
                    printMetric("EC2|" + "InstanceId|" + instanceId + "|" + metricName + "(" + data.getUnit() + ")", data.getAverage(),
                            MetricWriter.METRIC_AGGREGATION_TYPE_OBSERVATION,
                            MetricWriter.METRIC_TIME_ROLLUP_TYPE_AVERAGE,
                            MetricWriter.METRIC_CLUSTER_ROLLUP_TYPE_INDIVIDUAL);
                }
            }
        }
    }
    private void printAutoScalingMetrics(HashMap<String, HashMap<String,List<Datapoint>>> autoScalingMetrics) {
        Iterator outerIterator = autoScalingMetrics.keySet().iterator();

        while (outerIterator.hasNext()) {
            String autoScalingGroupName = outerIterator.next().toString();
            HashMap<String, List<Datapoint>> metricStatistics = autoScalingMetrics.get(autoScalingGroupName);
            Iterator innerIterator = metricStatistics.keySet().iterator();
            while (innerIterator.hasNext()) {
                String metricName = innerIterator.next().toString();
                List<Datapoint> datapoints = metricStatistics.get(metricName);
                if (datapoints != null && !datapoints.isEmpty()) {
                    Datapoint data = datapoints.get(0);
                    printMetric("AutoScaling|" + "Group Name|" + autoScalingGroupName + "|" + metricName, data.getAverage(),
                            MetricWriter.METRIC_AGGREGATION_TYPE_OBSERVATION,
                            MetricWriter.METRIC_TIME_ROLLUP_TYPE_AVERAGE,
                            MetricWriter.METRIC_CLUSTER_ROLLUP_TYPE_INDIVIDUAL);
                }
            }
        }
    }
}

