package io.prometheus.jmx;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.prometheus.client.Collector;

import io.prometheus.jmx.util.HttpClient;
import io.prometheus.jmx.util.XmlTool;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.yaml.snakeyaml.Yaml;


import javax.management.MalformedObjectNameException;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;


/**
 * Collects Eureka registry node info.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 *   new EurekaInfoCollector().register();
 * }
 * </pre>
 * Metrics being exported:
 * <pre>
 *   eureka_nodes_info{version="3.2.0",name="jmx_prometheus_httpserver",} 1.0
 * </pre>
 */
public class EurekaInfoCollector extends Collector {

  private static final Logger LOGGER = Logger.getLogger(EurekaInfoCollector.class.getName());

  private static final String EUREKA_SERVER_PORT = "server.port";
  private static final String EUREKA_METRIC_ENABLED = "metric.eureka.enabled";
  private static final String EUREKA_CLUSTER = "metric.eureka.cluster";
  private static final String EUREKA_CLUSTER_NAME = "metric.eureka.clusterName";

  private static final int DEFAULT_CONNECT_TIMEOUT = 15000;
  private static final int DEFAULT_CONNECTION_REQUEST_TIMEOUT = 3000;
  private static final int DEFAULT_SOCKET_TIMEOUT = 120000;
  private static final String LOCALHOST = "127.0.0.1";
  private static final String REQUEST_SCHEME = "http://";
  private static final String APP_URL = "/eureka/apps";
  private static final String SERVER_URL = "/eureka/status";

  private static final String EUREKA_STATUS_UP = "UP";
  private static final String EUREKA_STATUS_DOWN = "DOWN";
  private static final String EUREKA_DEFAULT_RENEWALINTERVALINSECS = "30";
  private static final String EUREKA_DEFAULT_DURATIONINSECS = "90";

  private Config config;
  private File configFile;
  private static List<MetricFamilySamples> mfs = new ArrayList<MetricFamilySamples>();


  public EurekaInfoCollector(File in) throws IOException, MalformedObjectNameException {
    configFile = in;
    config = loadConfig((Map<String, Object>)new Yaml().load(new FileReader(in)));
  }

  public EurekaInfoCollector(String yamlConfig) throws MalformedObjectNameException {
    config = loadConfig((Map<String, Object>)new Yaml().load(yamlConfig));
  }

  public EurekaInfoCollector(InputStream inputStream) throws MalformedObjectNameException {
    config = loadConfig((Map<String, Object>)new Yaml().load(inputStream));
  }

  private Config loadConfig(Map<String, Object> yamlConfig) throws MalformedObjectNameException {
    Config cfg = new Config();

    if (yamlConfig == null) {
      yamlConfig = new HashMap<String, Object>();
    }
    Object enabled = get(EUREKA_METRIC_ENABLED, yamlConfig);
    if (null != enabled) {
      cfg.setEnabled(Boolean.parseBoolean(String.valueOf(enabled)));
    }
    if (cfg.getEnabled()) {
      Object serverPort = get(EUREKA_SERVER_PORT, yamlConfig);
      if (null != serverPort) {
        cfg.setPort(String.valueOf(serverPort));
      }
      Object eurekaCluster = get(EUREKA_CLUSTER, yamlConfig);
      if (null != eurekaCluster) {
        String addrs = (String)eurekaCluster;
        List<String> cluster = Arrays.asList(addrs.split(","));
        String regx = "^(http|https|ftp)://";
        List objects = cluster.stream().map(addr -> {
          addr = addr.trim();
          if (!addr.matches(regx)) {
            addr = REQUEST_SCHEME + addr;
          }
          if (addr.lastIndexOf("/") == addr.length() - 1) {
            addr = addr.substring(0, addr.length() - 2);
          }
          return addr;
        }).collect(Collectors.toList());
        cfg.setCluster((String[]) objects.toArray(new String[objects.size()]));
      }
      Object eurekaClusterName = get(EUREKA_CLUSTER_NAME,yamlConfig);
      if (null != eurekaClusterName) {
        cfg.setClusterName((String)eurekaClusterName);
      }
    }
    return cfg;
  }

  /**
   * support java properties access pattern.  key = path1.path2.path3;
   * @param key
   * @return
   */
  private static Object get(String key, Map<String, Object> config) {
    // access recursive by path
    String[] paths = key.split("\\.");
    Map<String, Object> map = config;
    for (int index = 0; index < paths.length - 1; index++) {
      // get sub-map
      map = (Map<String, Object>)map.get(paths[index]);
    }
    return map.get(paths[paths.length - 1]);
  }

  @Override
  public List<MetricFamilySamples> collect() {

    if (!config.getEnabled()) {
      return mfs;
    }

    String appUrl = REQUEST_SCHEME + LOCALHOST + ":" + config.getPort() + APP_URL;
    if (!mfs.isEmpty()) {
      mfs.clear();
    }

    try {
      //scrape the nodeInfo registried on the eureka cluslter
      scrapeNodeInfo(appUrl);
      //scrape the eureka servers' status
      scrapeServerInfo(config.getClusterName());

    } catch (Exception e) {
    }
    return mfs;
  }

  public void scrapeNodeInfo(String appUrl) {
    try {
              HttpClient.sendGet(appUrl, null, new AppHandler(config.getClusterName()), DEFAULT_CONNECT_TIMEOUT,
                      DEFAULT_CONNECTION_REQUEST_TIMEOUT, DEFAULT_SOCKET_TIMEOUT);
    } catch (Exception e) {
    }
  }

  public void scrapeServerInfo(String clusterName) {
    String[] cluster = config.getCluster();
    if (null != cluster && cluster.length > 0) {
      for (String eureka : cluster) {
        try {
          HttpClient.sendGet(eureka + SERVER_URL, null, new ServerHandler(config.getClusterName()), DEFAULT_CONNECT_TIMEOUT,
                  DEFAULT_CONNECTION_REQUEST_TIMEOUT, DEFAULT_SOCKET_TIMEOUT);
        } catch (Exception e) {
          if (e instanceof ConnectException) {
            String replicas = Arrays.stream(cluster).collect(Collectors.joining("/, ")) + "/";
            String instanceId = eureka.substring(eureka.indexOf("://") + 3);
            List<MetricFamilySamples.Sample> samples = new ArrayList<MetricFamilySamples.Sample>();
            samples.add(new MetricFamilySamples.Sample(
                    "eureka_server_info", Arrays.asList("eureka_cluster","replicas", "instance_id", "status", "renewal_interval_in_secs", "duration_in_secs"),
                    Arrays.asList(clusterName, replicas, instanceId, EUREKA_STATUS_DOWN, EUREKA_DEFAULT_RENEWALINTERVALINSECS, EUREKA_DEFAULT_DURATIONINSECS), 1));
            mfs.add(new MetricFamilySamples("eureka_server_info", Type.GAUGE, "A metric shows that eureka servers info.", samples));

          }
        }
      }
    }

  }

  private static class AppHandler implements HttpClient.IHttpResponseHandler {

    private String clusterName;

    public AppHandler(String clusterName) {
      this.clusterName = clusterName;
    }

    @Override
    public void handle(CloseableHttpResponse response) throws Exception {
      List<MetricFamilySamples.Sample> samples;
      try {
        HttpEntity entity = response.getEntity();
        if (entity != null) {
          String msg = EntityUtils.toString(entity, "utf-8");
          JSONObject jsonObject = XmlTool.documentToJSONObject(msg);
          JSONArray applications = ((JSONArray) jsonObject.get("applications"));
          if ( null != applications && applications.size() > 0) {
            for (int i = 0; i < applications.size(); i++) {
              JSONArray application = ((JSONArray) ((JSONObject) applications.get(i)).get("application"));
              for (int j = 0; j < application.size(); j++) {
                JSONArray instances = ((JSONArray) ((JSONObject) application.get(j)).get("instance"));
                for (int k = 0; k < instances.size(); k++) {
                  samples = new ArrayList<MetricFamilySamples.Sample>();
                  samples.add(new MetricFamilySamples.Sample(
                          "eureka_nodes_info", Arrays.asList("eureka_cluster", "application", "host", "instance_id", "status"),
                          Arrays.asList(clusterName, ((JSONObject) instances.get(k)).getString("app"),
                                  ((JSONObject) instances.get(k)).getString("hostName"),
                                  ((JSONObject) instances.get(k)).getString("instanceId"),
                                  ((JSONObject) instances.get(k)).getString("status")), 1));
                  mfs.add(new MetricFamilySamples("eureka_nodes_info", Type.GAUGE, "A metric shows that the service node info which registried on the eureka server.", samples));
                }
              }
            }
          }
          return;
        }
        throw new Exception("Response unreceived");
      } finally {
        response.close();
      }
    }

  }

  private static class ServerHandler implements HttpClient.IHttpResponseHandler {

    private String clusterName;

    public ServerHandler(String clusterName) {
      this.clusterName = clusterName;
    }

    @Override
    public void handle(CloseableHttpResponse response) throws Exception {
      List<MetricFamilySamples.Sample> samples;
      try {
        HttpEntity entity = response.getEntity();
        if (entity != null) {
          String msg = EntityUtils.toString(entity, "utf-8");
          JSONObject jsonObject = XmlTool.documentToJSONObject(msg);
          JSONArray statusInfo = (JSONArray) jsonObject.get("com.netflix.eureka.util.StatusInfo");
          if (null != statusInfo) {
            for (int i = 0; i < statusInfo.size(); i++) {
              JSONArray applicationStats = ((JSONArray)((JSONObject) statusInfo.get(i)).get("applicationStats"));
              String replicas = ((String) ((JSONObject) applicationStats.get(0)).get("registered-replicas"));

              JSONArray instanceInfo = (JSONArray)((JSONObject) statusInfo.get(i)).get("instanceInfo");
              String instanceId = (String) ((JSONObject) instanceInfo.get(0)).get("instanceId");
              String hostName = (String) ((JSONObject) instanceInfo.get(0)).get("hostName");
              if (!isIp(instanceId.substring(0, instanceId.indexOf(":")-1))) {
                instanceId = hostName + instanceId.substring(instanceId.indexOf(":"));
              }
              String status = (String) ((JSONObject) instanceInfo.get(0)).get("status");
              JSONArray leaseInfo = (JSONArray) ((JSONObject) instanceInfo.get(0)).get("leaseInfo");
              String renewalIntervalInSecs = (String) ((JSONObject) leaseInfo.get(0)).get("renewalIntervalInSecs");
              String durationInSecs = (String) ((JSONObject) leaseInfo.get(0)).get("durationInSecs");

              samples = new ArrayList<MetricFamilySamples.Sample>();
              samples.add(new MetricFamilySamples.Sample(
                      "eureka_server_info", Arrays.asList("eureka_cluster","replicas", "instance_id", "status", "renewal_interval_in_secs", "duration_in_secs"),
                      Arrays.asList(clusterName, replicas, instanceId, status, renewalIntervalInSecs, durationInSecs), 1));
              mfs.add(new MetricFamilySamples("eureka_server_info", Type.GAUGE, "A metric shows that eureka servers info.", samples));
            }

          }
          return;
        }
        throw new Exception("Response unreceived");
      } finally {
        response.close();
      }
    }

    public boolean isIp(String ip) {
      String regex = "((2(5[0-5]|[0-4]\\d))|[0-1]?\\d{1,2})(\\.((2(5[0-5]|[0-4]\\d))|[0-1]?\\d{1,2})){3}";
      return ip.matches(regex);
    }
  }



  private static class Config {
    Boolean enabled = false;
    String port = "8761";
    String[] cluster;
    String clusterName = "default";

    public Boolean getEnabled() {
      return enabled;
    }

    public void setEnabled(Boolean enabled) {
      this.enabled = enabled;
    }

    public String getPort() {
      return port;
    }

    public void setPort(String port) {
      this.port = port;
    }

    public String[] getCluster() {
      return cluster;
    }

    public void setCluster(String[] cluster) {
      this.cluster = cluster;
    }

    public String getClusterName() {
      return clusterName;
    }

    public void setClusterName(String clusterName) {
      this.clusterName = clusterName;
    }

  }



  public static void main(String[] args) {
    try {
      new EurekaInfoCollector(new File("D:\\AgreeTech\\南研云盘\\AFA5.3.4_publish@20191127\\EUREKA\\eureka-1.7\\release\\conf\\application.yml")).collect();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (MalformedObjectNameException e) {
      e.printStackTrace();
    }
  }
}
