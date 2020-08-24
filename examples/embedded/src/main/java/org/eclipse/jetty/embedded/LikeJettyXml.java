//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.embedded;

import java.io.FileNotFoundException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.PropertiesConfigurationManager;
import org.eclipse.jetty.deploy.bindings.DebugListenerBinding;
import org.eclipse.jetty.deploy.providers.WebAppProvider;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.rewrite.handler.MsieSslRule;
import org.eclipse.jetty.rewrite.handler.RewriteHandler;
import org.eclipse.jetty.rewrite.handler.ValidUrlRule;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.AsyncRequestLogWriter;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.DebugListener;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LowResourceMonitor;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnectionStatistics;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.webapp.Configurations;

/**
 * Starts the Jetty Distribution's demo-base directory using entirely
 * embedded jetty techniques.
 */
public class LikeJettyXml
{
    public static Server createServer(int port, int securePort, boolean addDebugListener) throws Exception
    {
        // Path to as-built jetty-distribution directory
        Path jettyDistro = JettyDistribution.get();

        // Find jetty home and base directories
        String homePath = System.getProperty("jetty.home", jettyDistro.resolve("jetty-home").toString());
        Path homeDir = Paths.get(homePath);

        String basePath = System.getProperty("jetty.base", jettyDistro.resolve("demo-base").toString());
        Path baseDir = Paths.get(basePath);

        // Configure jetty.home and jetty.base system properties
        String jettyHome = homeDir.toAbsolutePath().toString();
        String jettyBase = baseDir.toAbsolutePath().toString();
        System.setProperty("jetty.home", jettyHome);
        System.setProperty("jetty.base", jettyBase);

        // === jetty.xml ===
        // Setup Threadpool
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMaxThreads(500);

        // Server
        Server server = new Server(threadPool);

        // Scheduler
        server.addBean(new ScheduledExecutorScheduler(null, false));

        // HTTP Configuration
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSecureScheme("https");
        httpConfig.setSecurePort(securePort);
        httpConfig.setOutputBufferSize(32768);
        httpConfig.setRequestHeaderSize(8192);
        httpConfig.setResponseHeaderSize(8192);
        httpConfig.setSendServerVersion(true);
        httpConfig.setSendDateHeader(false);
        // httpConfig.addCustomizer(new ForwardedRequestCustomizer());

        // Handler Structure
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(new HandlerList(contexts, new DefaultHandler()));

        // === jetty-jmx.xml ===
        MBeanContainer mbContainer = new MBeanContainer(
            ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbContainer);

        // === jetty-http.xml ===
        ServerConnector http = new ServerConnector(server,
            new HttpConnectionFactory(httpConfig));
        http.setPort(port);
        http.setIdleTimeout(30000);
        server.addConnector(http);

        // === jetty-https.xml ===
        // SSL Context Factory
        Path keystorePath = Paths.get("src/main/resources/etc/keystore.p12").toAbsolutePath();
        if (!Files.exists(keystorePath))
            throw new FileNotFoundException(keystorePath.toString());
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystorePath.toString());
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setTrustStorePath(keystorePath.toString());
        sslContextFactory.setTrustStorePassword("storepwd");

        // SSL HTTP Configuration
        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());

        // SSL Connector
        ServerConnector sslConnector = new ServerConnector(server,
            new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
            new HttpConnectionFactory(httpsConfig));
        sslConnector.setPort(securePort);
        server.addConnector(sslConnector);

        // === jetty-deploy.xml ===
        DeploymentManager deployer = new DeploymentManager();
        if (addDebugListener)
        {
            DebugListener debug = new DebugListener(System.err, true, true, true);
            server.addBean(debug);
            deployer.addLifeCycleBinding(new DebugListenerBinding(debug));
        }
        deployer.setContexts(contexts);
        deployer.setContextAttribute(
            "org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern",
            ".*/jetty-servlet-api-[^/]*\\.jar$|.*/javax.servlet.jsp.jstl-.*\\.jar$|.*/[^/]*taglibs.*\\.jar$");

        WebAppProvider webAppProvider = new WebAppProvider();
        webAppProvider.setMonitoredDirName(jettyBase + "/webapps");
        webAppProvider.setDefaultsDescriptor(jettyHome + "/etc/webdefault.xml");
        webAppProvider.setScanInterval(1);
        webAppProvider.setExtractWars(true);
        webAppProvider.setConfigurationManager(new PropertiesConfigurationManager());

        deployer.addAppProvider(webAppProvider);
        server.addBean(deployer);

        // === setup jetty plus ==
        Configurations.setServerDefault(server).add(new EnvConfiguration(), new PlusConfiguration(), new AnnotationConfiguration());

        // === jetty-stats.xml ===
        StatisticsHandler stats = new StatisticsHandler();
        stats.setHandler(server.getHandler());
        server.setHandler(stats);
        ServerConnectionStatistics.addToAllConnectors(server);

        // === Rewrite Handler
        RewriteHandler rewrite = new RewriteHandler();
        rewrite.setHandler(server.getHandler());
        server.setHandler(rewrite);
        rewrite.addRule(new MsieSslRule());
        rewrite.addRule(new ValidUrlRule());

        // === jetty-requestlog.xml ===
        AsyncRequestLogWriter logWriter = new AsyncRequestLogWriter(jettyBase + "/logs/yyyy_mm_dd.request.log");
        logWriter.setFilenameDateFormat("yyyy_MM_dd");
        logWriter.setRetainDays(90);
        logWriter.setTimeZone("GMT");
        CustomRequestLog requestLog = new CustomRequestLog(logWriter, CustomRequestLog.EXTENDED_NCSA_FORMAT + " \"%C\"");
        server.setRequestLog(requestLog);

        // === jetty-lowresources.xml ===
        LowResourceMonitor lowResourcesMonitor = new LowResourceMonitor(server);
        lowResourcesMonitor.setPeriod(1000);
        lowResourcesMonitor.setLowResourcesIdleTimeout(200);
        lowResourcesMonitor.setMonitorThreads(true);
        lowResourcesMonitor.setMaxMemory(0);
        lowResourcesMonitor.setMaxLowResourcesTime(5000);
        server.addBean(lowResourcesMonitor);

        // === test-realm.xml ===
        HashLoginService login = new HashLoginService();
        login.setName("Test Realm");
        login.setConfig(jettyBase + "/etc/realm.properties");
        login.setHotReload(false);
        server.addBean(login);

        return server;
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        int securePort = ExampleUtil.getPort(args, "jetty.https.port", 8443);
        Server server = createServer(port, securePort, true);

        // Extra options
        server.setDumpAfterStart(true);
        server.setDumpBeforeStop(false);
        server.setStopAtShutdown(true);

        // Start the server
        server.start();
        server.join();
    }
}
