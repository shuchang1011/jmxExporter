package cn.com.agree.eureka;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.com.agree.eureka.core.NamedThreadFactory;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.management.MalformedObjectNameException;

@Component
public class JavaAgent {

    private static final Logger LOGGER = Logger.getLogger(JavaAgent.class.getName());

    private static ExecutorService scrapeExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("Eureka-scrape-Thread"));

    static HTTPServer server;

    public static void agentmain(String agentArgument, Instrumentation instrumentation) throws Exception {
        premain(agentArgument, instrumentation);
    }

    public static void premain(String agentArgument, Instrumentation instrumentation) throws Exception {
        // Bind to all interfaces by default (this includes IPv6).


        scrapeExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    LOGGER.info("eureka metric scrape agent is starting...");
                    String host = "0.0.0.0";
                    Config config = parseConfig(agentArgument, host);
                    new BuildInfoCollector().register();
                    new EurekaInfoCollector(new File(config.file)).register();
                    new JmxCollector(new File(config.file)).register();
                    //初始化默认Exporter（注册常用的机器性能指标采集器）
                    DefaultExports.initialize();
                    //基于http请求的方式连接MBeanServer，通过该server来操作MBean对象
                    server = new HTTPServer(config.socket, CollectorRegistry.defaultRegistry, true);
                } catch (IllegalArgumentException e) {
                    System.err.println("Usage: -javaagent:/path/to/JavaAgent.jar=[host:]<port>:<yaml configuration file> " + e.getMessage());
                    System.exit(1);
                } catch (MalformedObjectNameException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

    }

    @PreDestroy
    public void destory() {
        LOGGER.info("destroy eureka scrape agent....");
        if (server != null) {
            server.stop();
        }
        scrapeExecutor.shutdown();
        LOGGER.info("destory eureka scrape agent completely");
    }

    /**
     * Parse the Java Agent configuration. The arguments are typically specified to the JVM as a javaagent as
     * {@code -javaagent:/path/to/agent.jar=<CONFIG>}. This method parses the {@code <CONFIG>} portion.
     * @param args provided agent args
     * @param ifc default bind interface
     * @return configuration to use for our application
     */
    public static Config parseConfig(String args, String ifc) {
        Pattern pattern = Pattern.compile(
                "^(?:((?:[\\w.]+)|(?:\\[.+])):)?" +  // host name, or ipv4, or ipv6 address in brackets
                        "(\\d{1,5}):" +              // port
                        "(.+)");                     // config file

        Matcher matcher = pattern.matcher(args);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Malformed arguments - " + args);
        }

        String givenHost = matcher.group(1);
        String givenPort = matcher.group(2);
        String givenConfigFile = matcher.group(3);

        int port = Integer.parseInt(givenPort);

        InetSocketAddress socket;
        if (givenHost != null && !givenHost.isEmpty()) {
            socket = new InetSocketAddress(givenHost, port);
        }
        else {
            socket = new InetSocketAddress(ifc, port);
            givenHost = ifc;
        }

        return new Config(givenHost, port, givenConfigFile, socket);
    }

    static class Config {
        String host;
        int port;
        String file;
        InetSocketAddress socket;

        Config(String host, int port, String file, InetSocketAddress socket) {
            this.host = host;
            this.port = port;
            this.file = file;
            this.socket = socket;
        }
    }
}
