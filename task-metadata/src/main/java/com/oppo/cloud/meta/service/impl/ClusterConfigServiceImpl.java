/*
 * Copyright 2023 OPPO.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oppo.cloud.meta.service.impl;

import com.alibaba.fastjson2.JSON;
import com.oppo.cloud.common.constant.Constant;
import com.oppo.cloud.common.domain.cluster.hadoop.YarnConf;
import com.oppo.cloud.common.service.RedisService;
import com.oppo.cloud.common.util.YarnUtil;
import com.oppo.cloud.meta.config.HadoopConfig;
import com.oppo.cloud.meta.domain.Properties;
import com.oppo.cloud.meta.domain.YarnConfProperties;
import com.oppo.cloud.meta.domain.YarnPathInfo;
import com.oppo.cloud.meta.service.IClusterConfigService;
import com.oppo.cloud.meta.utils.XMLFileTool;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dom4j.Document;
import org.dom4j.Element;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * YARN、SPARK集群地址配置信息
 */
@Slf4j
@Service
public class ClusterConfigServiceImpl implements IClusterConfigService {

    @Resource
    private RedisService redisService;

    @Resource
    private HadoopConfig config;

    @Resource(name = "restTemplate")
    private RestTemplate restTemplate;

    private static final String YARN_CONF = "http://%s/conf";

    private static final String DEFAULT_FS = "fs.defaultFS";

    private static final String YARN_REMOTE_APP_LOG_DIR = "yarn.nodemanager.remote-app-log-dir";

    private static final String MARREDUCE_DONE_DIR = "mapreduce.jobhistory.done-dir";

    private static final String MARREDUCE_INTERMEDIATE_DONE_DIR = "mapreduce.jobhistory.intermediate-done-dir";


    /**
     * 获取spark history server列表
     */
    @Override
    public List<String> getSparkHistoryServers() {
        return config.getSpark().getSparkHistoryServer();
    }

    /**
     * 获取yarn rm列表
     */
    @Override
    public Map<String, String> getYarnClusters() {
        List<YarnConf> yarnConfList = config.getYarn();
        return YarnUtil.getYarnClusters(yarnConfList);
    }


    /**
     * 更新集群信息
     */
    @Override
    public void updateClusterConfig() {

        log.info("clusterConfig:{}", config);
        // cache spark history server
        List<String> sparkHistoryServerList = config.getSpark().getSparkHistoryServer();
        log.info("{}:{}", Constant.SPARK_HISTORY_SERVERS, sparkHistoryServerList);
        redisService.set(Constant.SPARK_HISTORY_SERVERS, JSON.toJSONString(sparkHistoryServerList));

        // cache yarn server
        List<YarnConf> yarnConfList = config.getYarn();
        // resourceManager 对应的 jobHistoryServer
        Map<String, String> rmJhsMap = new HashMap<>();
        yarnConfList.forEach(clusterInfo -> clusterInfo.getResourceManager()
                .forEach(rm -> rmJhsMap.put(rm, clusterInfo.getJobHistoryServer())));
        redisService.set(Constant.YARN_CLUSTERS, JSON.toJSONString(sparkHistoryServerList));
        log.info("{}:{}", Constant.YARN_CLUSTERS, yarnConfList);
        redisService.set(Constant.RM_JHS_MAP, JSON.toJSONString(rmJhsMap));
        log.info("{}:{}", Constant.RM_JHS_MAP, rmJhsMap);
        updateJHSConfig(yarnConfList);
    }

    /**
     * 更新配置中jobhistoryserver hdfs路径信息
     */
    public void updateJHSConfig(List<YarnConf> list) {
        for (YarnConf yarnClusterInfo : list) {
            String host = yarnClusterInfo.getJobHistoryServer();
            YarnPathInfo yarnPathInfo = getYarnPathInfo(host);
            if (yarnPathInfo == null) {
                log.error("get {}, hdfsPath empty", host);
                continue;
            }
            String yarnRemotePath = Constant.JHS_HDFS_PATH + host;
            String mapreduceDonePath = Constant.JHS_MAPREDUCE_DONE_PATH + host;
            String mapreduceIntermediateDonePath = Constant.JHS_MAPREDUCE_INTERMEDIATE_DONE_PATH + host;
            log.info("cache yarnPathInfo:{},{}", yarnRemotePath, yarnPathInfo.getRemoteDir());
            log.info("cache yarnPathInfo:{},{}", mapreduceDonePath, yarnPathInfo.getMapreduceDoneDir());
            log.info("cache yarnPathInfo:{},{}", mapreduceIntermediateDonePath, yarnPathInfo.getMapreduceIntermediateDoneDir());
            redisService.set(yarnRemotePath, yarnPathInfo.getRemoteDir());
            redisService.set(mapreduceDonePath, yarnPathInfo.getMapreduceDoneDir());
            redisService.set(mapreduceIntermediateDonePath, yarnPathInfo.getMapreduceIntermediateDoneDir());
        }
    }

    /**
     * 获取jobhistoryserver hdfs路径信息
     */
    public YarnPathInfo getYarnPathInfo(String ip) {
        String url = String.format(YARN_CONF, ip);
        log.info("getHDFSPath:{}", url);
        ResponseEntity<String> responseEntity;
        try {
            responseEntity = restTemplate.getForEntity(url, String.class);
        } catch (Exception e) {
            log.error("getHDFSPathErr:{},{}", url, e.getMessage());
            return null;
        }

        if (responseEntity.getBody() == null) {
            log.error("getHDFSPathErr:{}", url);
            return null;
        }

        YarnConfProperties yarnConfProperties = null;
        try {
            yarnConfProperties = JSON.parseObject(responseEntity.getBody(), YarnConfProperties.class);
        } catch (Exception e) {
            log.warn("parse yarn conf json error, retry by xml format, error: {}", e.getMessage());
            List<Properties> propertiesList = getPropertiesByXml(responseEntity);
            if (null == propertiesList || propertiesList.size() == 0) {
                return null;
            }
            yarnConfProperties.setProperties(propertiesList);
        }

        String remoteDir = "";
        String defaultFS = "";
        String mapreduceDoneDir = "";
        String mapreduceIntermediateDoneDir = "";

        if (yarnConfProperties != null && yarnConfProperties.getProperties() != null) {
            for (Properties properties : yarnConfProperties.getProperties()) {
                String key = properties.getKey();
                String value = properties.getValue();
                if (YARN_REMOTE_APP_LOG_DIR.equals(key)) {
                    log.info("yarnConfProperties key: {}, value: {}", YARN_REMOTE_APP_LOG_DIR, value);
                    remoteDir = value;
                }
                if (DEFAULT_FS.equals(key)) {
                    log.info("yarnConfProperties key: {}, value: {}", DEFAULT_FS, value);
                    defaultFS = value;
                }
                if (MARREDUCE_DONE_DIR.equals(key)) {
                    log.info("yarnConfProperties key: {}, value: {}", MARREDUCE_DONE_DIR, value);
                    mapreduceDoneDir = value;
                }
                if (MARREDUCE_INTERMEDIATE_DONE_DIR.equals(key)) {
                    log.info("yarnConfProperties key: {}, value: {}", MARREDUCE_INTERMEDIATE_DONE_DIR, value);
                    mapreduceIntermediateDoneDir = value;
                }
            }
        }
        if (StringUtils.isEmpty(defaultFS)) {
            log.error("defaultFSEmpty:{}", url);
            return null;
        }
        if (StringUtils.isEmpty(remoteDir)) {
            log.error("remoteDirEmpty:{}", url);
            return null;
        }
        if (StringUtils.isEmpty(mapreduceDoneDir)) {
            log.error("mapreduceDoneDirrEmpty:{}", url);
            return null;
        }
        if (!remoteDir.contains(Constant.HDFS_SCHEME)) {
            remoteDir = defaultFS + remoteDir;
        }
        if (!mapreduceDoneDir.contains(Constant.HDFS_SCHEME)) {
            mapreduceDoneDir = defaultFS + mapreduceDoneDir;
        }
        if (!mapreduceIntermediateDoneDir.contains(Constant.HDFS_SCHEME)) {
            mapreduceIntermediateDoneDir = defaultFS + mapreduceIntermediateDoneDir;
        }

        YarnPathInfo yarnPathInfo = new YarnPathInfo();
        yarnPathInfo.setDefaultFS(defaultFS);
        yarnPathInfo.setRemoteDir(remoteDir);
        yarnPathInfo.setMapreduceDoneDir(mapreduceDoneDir);
        yarnPathInfo.setMapreduceIntermediateDoneDir(mapreduceIntermediateDoneDir);
        log.info("yarnPathInfo: {}, {}", url, yarnPathInfo);
        return yarnPathInfo;
    }

    private static List<Properties> getPropertiesByXml(ResponseEntity<String> responseEntity) {
        Document document;
        try {
            document = XMLFileTool.XMLFileReader.loadXMLFile(responseEntity.getBody());
        } catch (Exception e) {
            log.error("parse yarn conf xml error: ", e);
            return null;
        }
        Element root = document.getRootElement();
        List<Properties> propertiesList = new ArrayList<>();
        for (Element element : root.elements()) {
            String name = element.element("name").getTextTrim();
            String value = element.element("value").getTextTrim();

            Properties ps = new Properties();
            ps.setKey(name);
            ps.setValue(value);
            propertiesList.add(ps);
        }
        return propertiesList;
    }


}
