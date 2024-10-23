/*
 * Copyright 2021-present StarRocks, Inc. All rights reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.starrocks.connector.flink.cdc.mysql;

import com.starrocks.connector.flink.cdc.DatabaseSync;
import com.starrocks.connector.flink.cdc.DateToStringConverter;
import com.starrocks.connector.flink.cdc.SourceSchema;
import org.apache.flink.cdc.connectors.mysql.source.MySqlSource;
import org.apache.flink.cdc.connectors.mysql.source.MySqlSourceBuilder;
import org.apache.flink.cdc.connectors.mysql.source.config.MySqlSourceOptions;
import org.apache.flink.cdc.connectors.mysql.source.offset.BinlogOffset;
import org.apache.flink.cdc.connectors.mysql.source.offset.BinlogOffsetBuilder;
import org.apache.flink.cdc.connectors.mysql.table.StartupOptions;
import org.apache.flink.cdc.connectors.shaded.org.apache.kafka.connect.json.JsonConverterConfig;
import org.apache.flink.cdc.debezium.JsonDebeziumDeserializationSchema;
import org.apache.flink.cdc.debezium.table.DebeziumOptions;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.DriverManager;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import java.util.Properties;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;


public class MysqlDatabaseSync extends DatabaseSync {
    private static final Logger LOG = LoggerFactory.getLogger(MysqlDatabaseSync.class);

    private static String JDBC_URL = "jdbc:mysql://%s:%d?useInformationSchema=true";
    private static final String PROPERTIES_PREFIX = "jdbc.properties.";
    public MysqlDatabaseSync() {
    }

    @Override
    public Connection getConnection() throws SQLException {
        Properties jdbcProperties = getJdbcProperties();
        StringBuilder jdbcUrlSb = new StringBuilder(JDBC_URL);
        jdbcProperties.forEach((key, value) -> jdbcUrlSb.append("&").append(key).append("=").append(value));
        String jdbcUrl = String.format(jdbcUrlSb.toString(), config.get(MySqlSourceOptions.HOSTNAME), config.get(MySqlSourceOptions.PORT));

        return DriverManager.getConnection(jdbcUrl,config.get(MySqlSourceOptions.USERNAME),config.get(MySqlSourceOptions.PASSWORD));
    }

    @Override
    public List<SourceSchema> getSchemaList() throws Exception {
        String databaseName = config.get(MySqlSourceOptions.DATABASE_NAME);
        List<SourceSchema> schemaList = new ArrayList<>();
        try (Connection conn = getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet tables =
                         metaData.getTables(databaseName, null, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    String tableComment = tables.getString("REMARKS");
                    if (!isSyncNeeded(tableName)) {
                        continue;
                    }
                    SourceSchema sourceSchema =
                            new SourceSchema(metaData, databaseName, tableName, tableComment);
                    if (sourceSchema.primaryKeys.size() > 0) {
                        //Only sync tables with primary keys
                        schemaList.add(sourceSchema);
                    } else {
                        LOG.warn("table {} has no primary key, skip", tableName);
                        System.out.println("table " + tableName + " has no primary key, skip.");
                    }
                }
            }
        }
        return schemaList;
    }

    @Override
    public DataStreamSource<String> buildCdcSource(StreamExecutionEnvironment env) {
        MySqlSourceBuilder<String> sourceBuilder = MySqlSource.builder();

        String databaseName = config.get(MySqlSourceOptions.DATABASE_NAME);
        Preconditions.checkNotNull(databaseName, "database-name in mysql is required");
        String tableName = config.get(MySqlSourceOptions.TABLE_NAME);
        sourceBuilder.hostname(config.get(MySqlSourceOptions.HOSTNAME))
                .port(config.get(MySqlSourceOptions.PORT))
                .username(config.get(MySqlSourceOptions.USERNAME))
                .password(config.get(MySqlSourceOptions.PASSWORD))
                .databaseList(databaseName)
                .tableList(databaseName + "." + tableName);

        config.getOptional(MySqlSourceOptions.SERVER_ID).ifPresent(sourceBuilder::serverId);
        config.getOptional(MySqlSourceOptions.SERVER_TIME_ZONE).ifPresent(sourceBuilder::serverTimeZone);
        config.getOptional(MySqlSourceOptions.SCAN_SNAPSHOT_FETCH_SIZE).ifPresent(sourceBuilder::fetchSize);
        config.getOptional(MySqlSourceOptions.CONNECT_TIMEOUT).ifPresent(sourceBuilder::connectTimeout);
        config.getOptional(MySqlSourceOptions.CONNECT_MAX_RETRIES).ifPresent(sourceBuilder::connectMaxRetries);
        config.getOptional(MySqlSourceOptions.CONNECTION_POOL_SIZE).ifPresent(sourceBuilder::connectionPoolSize);
        config.getOptional(MySqlSourceOptions.HEARTBEAT_INTERVAL).ifPresent(sourceBuilder::heartbeatInterval);
        config.getOptional(MySqlSourceOptions.SCAN_NEWLY_ADDED_TABLE_ENABLED).ifPresent(sourceBuilder::scanNewlyAddedTableEnabled);

        String startupMode = config.get(MySqlSourceOptions.SCAN_STARTUP_MODE);
        if ("initial".equalsIgnoreCase(startupMode)) {
            sourceBuilder.startupOptions(StartupOptions.initial());
        } else if ("earliest-offset".equalsIgnoreCase(startupMode)) {
            sourceBuilder.startupOptions(StartupOptions.earliest());
        } else if ("latest-offset".equalsIgnoreCase(startupMode)) {
            sourceBuilder.startupOptions(StartupOptions.latest());
        } else if ("specific-offset".equalsIgnoreCase(startupMode)) {
            BinlogOffsetBuilder offsetBuilder = BinlogOffset.builder();
            String file = config.get(MySqlSourceOptions.SCAN_STARTUP_SPECIFIC_OFFSET_FILE);
            Long pos = config.get(MySqlSourceOptions.SCAN_STARTUP_SPECIFIC_OFFSET_POS);
            if (file != null && pos != null) {
                offsetBuilder.setBinlogFilePosition(file, pos);
            }
            config.getOptional(MySqlSourceOptions.SCAN_STARTUP_SPECIFIC_OFFSET_GTID_SET).ifPresent(offsetBuilder::setGtidSet);
            config.getOptional(MySqlSourceOptions.SCAN_STARTUP_SPECIFIC_OFFSET_SKIP_EVENTS).ifPresent(offsetBuilder::setSkipEvents);
            config.getOptional(MySqlSourceOptions.SCAN_STARTUP_SPECIFIC_OFFSET_SKIP_ROWS).ifPresent(offsetBuilder::setSkipRows);
            sourceBuilder.startupOptions(StartupOptions.specificOffset(offsetBuilder.build()));
        } else if ("timestamp".equalsIgnoreCase(startupMode)) {
            sourceBuilder.startupOptions(
                    StartupOptions.timestamp(config.get(MySqlSourceOptions.SCAN_STARTUP_TIMESTAMP_MILLIS)));
        }

        Properties jdbcProperties = new Properties();
        Properties debeziumProperties = new Properties();
        //date to string
        debeziumProperties.putAll(DateToStringConverter.DEFAULT_PROPS);

        for (Map.Entry<String, String> entry : config.toMap().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key.startsWith(PROPERTIES_PREFIX)) {
                jdbcProperties.put(key.substring(PROPERTIES_PREFIX.length()), value);
            } else if (key.startsWith(DebeziumOptions.DEBEZIUM_OPTIONS_PREFIX)) {
                debeziumProperties.put(
                        key.substring(DebeziumOptions.DEBEZIUM_OPTIONS_PREFIX.length()), value);
            }
        }
        sourceBuilder.jdbcProperties(jdbcProperties);
        sourceBuilder.debeziumProperties(debeziumProperties);

        Map<String, Object> customConverterConfigs = new HashMap<>();
        customConverterConfigs.put(JsonConverterConfig.DECIMAL_FORMAT_CONFIG, "numeric");
        JsonDebeziumDeserializationSchema schema =
                new JsonDebeziumDeserializationSchema(false, customConverterConfigs);
        MySqlSource<String> mySqlSource = sourceBuilder.deserializer(schema).includeSchemaChanges(true).build();

        DataStreamSource<String> streamSource = env.fromSource(
                mySqlSource, WatermarkStrategy.noWatermarks(), "MySQL Source");
        return streamSource;
    }

    private Properties getJdbcProperties(){
        Properties jdbcProps = new Properties();
        for (Map.Entry<String, String> entry : config.toMap().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key.startsWith(PROPERTIES_PREFIX)) {
                jdbcProps.put(key.substring(PROPERTIES_PREFIX.length()), value);
            }
        }
        return jdbcProps;
    }
}
