package com.linkedin.pinot.server.starter;

import java.io.File;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.pinot.common.data.DataManager;
import com.linkedin.pinot.common.query.QueryExecutor;
import com.linkedin.pinot.server.conf.NettyServerConfig;
import com.linkedin.pinot.server.conf.ServerConf;
import com.linkedin.pinot.transport.netty.NettyServer;
import com.linkedin.pinot.transport.netty.NettyTCPServer;
import com.linkedin.pinot.transport.netty.NettyServer.RequestHandlerFactory;


/**
 * Initialize a ServerBuilder with serverConf file.
 * 
 * @author xiafu
 *
 */
public class ServerBuilder {

  private static Logger LOGGER = LoggerFactory.getLogger(ServerBuilder.class);
  public static final String PINOT_PROPERTIES = "pinot.properties";

  private final ServerConf _serverConf;

  public ServerConf getConfiguration() {
    return _serverConf;
  }

  /**
   * Construct from config file path
   * @param configFilePath Path to the config file
   * @throws Exception
   */
  public ServerBuilder(File configFilePath) throws Exception {
    if (!configFilePath.exists()) {
      LOGGER.error("configuration file: " + configFilePath.getAbsolutePath() + " does not exist.");
      throw new ConfigurationException("configuration file: " + configFilePath.getAbsolutePath() + " does not exist.");
    }

    // build _serverConf
    PropertiesConfiguration serverConf = new PropertiesConfiguration();
    serverConf.setDelimiterParsingDisabled(false);
    serverConf.load(configFilePath);
    _serverConf = new ServerConf(serverConf);
  }

  /**
   * Construct from config directory and a config file which resides under it
   * @param confDir Directory under which config file is present
   * @param file Config File
   * @throws Exception
   */
  public ServerBuilder(String confDir, String file) throws Exception {
    this(new File(confDir, file));
  }

  /**
   * Construct from config directory and default config file
   * @param confDir Directory under which pinot.properties file is present
   * @throws Exception
   */
  public ServerBuilder(String confDir) throws Exception {
    this(new File(confDir, PINOT_PROPERTIES));
  }

  /**
   * Initialize with Configuration file
   * @param Configuration object
   * @throws Exception
   */
  public ServerBuilder(Configuration config) {
    _serverConf = new ServerConf(config);
  }

  /**
   * Initialize with ServerConf object
   * @param ServerConf object
   */
  public ServerBuilder(ServerConf serverConf) {
    _serverConf = serverConf;
  }

  /**
   * Build Instance DataManager
   * @throws InstantiationException
   * @throws IllegalAccessException
   * @throws ClassNotFoundException
   */
  public DataManager buildInstanceDataManager() throws InstantiationException,
      IllegalAccessException, ClassNotFoundException {
    String className = _serverConf.getInstanceDataManagerClassName();
    LOGGER.info("Trying to Load Instance DataManager by Class : " + className);
    DataManager instanceDataManager = (DataManager) Class.forName(className).newInstance();
    instanceDataManager.init(_serverConf.getInstanceDataManagerConfig());
    return instanceDataManager;
  }

  /**
   * Build QueryExecutor
   * @param instanceDataManager
   * @return
   * @throws InstantiationException
   * @throws IllegalAccessException
   * @throws ClassNotFoundException
   * @throws ConfigurationException 
   */
  public QueryExecutor buildQueryExecutor(DataManager instanceDataManager) throws InstantiationException,
      IllegalAccessException, ClassNotFoundException, ConfigurationException {
    String className = _serverConf.getQueryExecutorClassName();
    LOGGER.info("Trying to Load Query Executor by Class : " + className);
    QueryExecutor queryExecutor = (QueryExecutor) Class.forName(className).newInstance();
    queryExecutor.init(_serverConf.getQueryExecutorConfig(), instanceDataManager);
    return queryExecutor;
  }

  /**
   * Build RequestHandlerFactory
   * @param queryExecutor
   * @return
   * @throws InstantiationException
   * @throws IllegalAccessException
   * @throws ClassNotFoundException
   */
  public RequestHandlerFactory buildRequestHandlerFactory(QueryExecutor queryExecutor) throws InstantiationException,
      IllegalAccessException, ClassNotFoundException {
    String className = _serverConf.getRequestHandlerFactoryClassName();
    LOGGER.info("Trying to Load Request Handler Factory by Class : " + className);
    RequestHandlerFactory requestHandlerFactory = (RequestHandlerFactory) Class.forName(className).newInstance();
    requestHandlerFactory.init(queryExecutor);
    return requestHandlerFactory;
  }

  public NettyServer buildNettyServer(NettyServerConfig nettyServerConfig, RequestHandlerFactory requestHandlerFactory) {
    LOGGER.info("Trying to build NettyTCPServer with port : " + nettyServerConfig.getPort());
    NettyServer nettyServer = new NettyTCPServer(nettyServerConfig.getPort(), requestHandlerFactory, null);
    return nettyServer;
  }
}