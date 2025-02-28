/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.data.pipeline.core.importer;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.data.pipeline.api.config.ImporterConfiguration;
import org.apache.shardingsphere.data.pipeline.api.datasource.PipelineDataSourceManager;
import org.apache.shardingsphere.data.pipeline.api.executor.AbstractLifecycleExecutor;
import org.apache.shardingsphere.data.pipeline.api.importer.Importer;
import org.apache.shardingsphere.data.pipeline.api.ingest.channel.PipelineChannel;
import org.apache.shardingsphere.data.pipeline.api.ingest.record.Column;
import org.apache.shardingsphere.data.pipeline.api.ingest.record.DataRecord;
import org.apache.shardingsphere.data.pipeline.api.ingest.record.FinishedRecord;
import org.apache.shardingsphere.data.pipeline.api.ingest.record.GroupedDataRecord;
import org.apache.shardingsphere.data.pipeline.api.ingest.record.Record;
import org.apache.shardingsphere.data.pipeline.api.job.JobOperationType;
import org.apache.shardingsphere.data.pipeline.api.job.progress.listener.PipelineJobProgressListener;
import org.apache.shardingsphere.data.pipeline.api.job.progress.listener.PipelineJobProgressUpdatedParameter;
import org.apache.shardingsphere.data.pipeline.api.metadata.LogicTableName;
import org.apache.shardingsphere.data.pipeline.core.exception.job.PipelineImporterJobWriteException;
import org.apache.shardingsphere.data.pipeline.core.ingest.IngestDataChangeType;
import org.apache.shardingsphere.data.pipeline.core.record.RecordUtils;
import org.apache.shardingsphere.data.pipeline.spi.importer.connector.ImporterConnector;
import org.apache.shardingsphere.data.pipeline.spi.ratelimit.JobRateLimitAlgorithm;
import org.apache.shardingsphere.data.pipeline.spi.sqlbuilder.PipelineSQLBuilder;
import org.apache.shardingsphere.data.pipeline.util.spi.PipelineTypedSPILoader;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Default importer.
 */
@Slf4j
public final class DataSourceImporter extends AbstractLifecycleExecutor implements Importer {
    
    private static final DataRecordMerger MERGER = new DataRecordMerger();
    
    @Getter(AccessLevel.PROTECTED)
    private final ImporterConfiguration importerConfig;
    
    private final PipelineDataSourceManager dataSourceManager;
    
    private final PipelineSQLBuilder pipelineSqlBuilder;
    
    private final PipelineChannel channel;
    
    private final PipelineJobProgressListener jobProgressListener;
    
    private final JobRateLimitAlgorithm rateLimitAlgorithm;
    
    private final AtomicReference<Statement> batchInsertStatement = new AtomicReference<>();
    
    private final AtomicReference<Statement> updateStatement = new AtomicReference<>();
    
    private final AtomicReference<Statement> batchDeleteStatement = new AtomicReference<>();
    
    public DataSourceImporter(final ImporterConfiguration importerConfig, final ImporterConnector importerConnector, final PipelineChannel channel,
                              final PipelineJobProgressListener jobProgressListener) {
        this.importerConfig = importerConfig;
        rateLimitAlgorithm = importerConfig.getRateLimitAlgorithm();
        this.dataSourceManager = (PipelineDataSourceManager) importerConnector.getConnector();
        this.channel = channel;
        pipelineSqlBuilder = PipelineTypedSPILoader.getDatabaseTypedService(PipelineSQLBuilder.class, importerConfig.getDataSourceConfig().getDatabaseType().getType());
        this.jobProgressListener = jobProgressListener;
    }
    
    @Override
    protected void runBlocking() {
        int batchSize = importerConfig.getBatchSize();
        while (isRunning()) {
            List<Record> records = channel.fetchRecords(batchSize, 3, TimeUnit.SECONDS);
            if (null != records && !records.isEmpty()) {
                PipelineJobProgressUpdatedParameter updatedParam = flush(dataSourceManager.getDataSource(importerConfig.getDataSourceConfig()), records);
                channel.ack(records);
                jobProgressListener.onProgressUpdated(updatedParam);
                if (FinishedRecord.class.equals(records.get(records.size() - 1).getClass())) {
                    break;
                }
            }
        }
    }
    
    private PipelineJobProgressUpdatedParameter flush(final DataSource dataSource, final List<Record> buffer) {
        List<DataRecord> dataRecords = buffer.stream().filter(DataRecord.class::isInstance).map(DataRecord.class::cast).collect(Collectors.toList());
        if (dataRecords.isEmpty()) {
            return new PipelineJobProgressUpdatedParameter(0);
        }
        int insertRecordNumber = 0;
        for (DataRecord each : dataRecords) {
            if (IngestDataChangeType.INSERT.equals(each.getType())) {
                insertRecordNumber++;
            }
        }
        List<GroupedDataRecord> result = MERGER.group(dataRecords);
        for (GroupedDataRecord each : result) {
            flushInternal(dataSource, each.getBatchDeleteDataRecords());
            flushInternal(dataSource, each.getBatchInsertDataRecords());
            flushInternal(dataSource, each.getBatchUpdateDataRecords());
            sequentialFlush(dataSource, each.getNonBatchRecords());
        }
        return new PipelineJobProgressUpdatedParameter(insertRecordNumber);
    }
    
    private void flushInternal(final DataSource dataSource, final List<DataRecord> buffer) {
        if (null == buffer || buffer.isEmpty()) {
            return;
        }
        tryFlush(dataSource, buffer);
    }
    
    @SneakyThrows(InterruptedException.class)
    private void tryFlush(final DataSource dataSource, final List<DataRecord> buffer) {
        for (int i = 0; isRunning() && i <= importerConfig.getRetryTimes(); i++) {
            try {
                doFlush(dataSource, buffer);
                return;
            } catch (final SQLException ex) {
                log.error("flush failed {}/{} times.", i, importerConfig.getRetryTimes(), ex);
                if (i == importerConfig.getRetryTimes()) {
                    throw new PipelineImporterJobWriteException(ex);
                }
                Thread.sleep(Math.min(5 * 60 * 1000L, 1000L << i));
            }
        }
    }
    
    private void doFlush(final DataSource dataSource, final List<DataRecord> buffer) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            switch (buffer.get(0).getType()) {
                case IngestDataChangeType.INSERT:
                    if (null != rateLimitAlgorithm) {
                        rateLimitAlgorithm.intercept(JobOperationType.INSERT, 1);
                    }
                    executeBatchInsert(connection, buffer);
                    break;
                case IngestDataChangeType.UPDATE:
                    if (null != rateLimitAlgorithm) {
                        rateLimitAlgorithm.intercept(JobOperationType.UPDATE, 1);
                    }
                    executeUpdate(connection, buffer);
                    break;
                case IngestDataChangeType.DELETE:
                    if (null != rateLimitAlgorithm) {
                        rateLimitAlgorithm.intercept(JobOperationType.DELETE, 1);
                    }
                    executeBatchDelete(connection, buffer);
                    break;
                default:
                    break;
            }
            connection.commit();
        }
    }
    
    private void doFlush(final Connection connection, final DataRecord each) throws SQLException {
        switch (each.getType()) {
            case IngestDataChangeType.INSERT:
                if (null != rateLimitAlgorithm) {
                    rateLimitAlgorithm.intercept(JobOperationType.INSERT, 1);
                }
                executeBatchInsert(connection, Collections.singletonList(each));
                break;
            case IngestDataChangeType.UPDATE:
                if (null != rateLimitAlgorithm) {
                    rateLimitAlgorithm.intercept(JobOperationType.UPDATE, 1);
                }
                executeUpdate(connection, each);
                break;
            case IngestDataChangeType.DELETE:
                if (null != rateLimitAlgorithm) {
                    rateLimitAlgorithm.intercept(JobOperationType.DELETE, 1);
                }
                executeBatchDelete(connection, Collections.singletonList(each));
                break;
            default:
        }
    }
    
    private void executeBatchInsert(final Connection connection, final List<DataRecord> dataRecords) throws SQLException {
        DataRecord dataRecord = dataRecords.get(0);
        String insertSql = pipelineSqlBuilder.buildInsertSQL(getSchemaName(dataRecord.getTableName()), dataRecord);
        try (PreparedStatement preparedStatement = connection.prepareStatement(insertSql)) {
            batchInsertStatement.set(preparedStatement);
            preparedStatement.setQueryTimeout(30);
            for (DataRecord each : dataRecords) {
                for (int i = 0; i < each.getColumnCount(); i++) {
                    preparedStatement.setObject(i + 1, each.getColumn(i).getValue());
                }
                preparedStatement.addBatch();
            }
            preparedStatement.executeBatch();
        } finally {
            batchInsertStatement.set(null);
        }
    }
    
    private String getSchemaName(final String logicTableName) {
        return getImporterConfig().getSchemaName(new LogicTableName(logicTableName));
    }
    
    private void executeUpdate(final Connection connection, final List<DataRecord> dataRecords) throws SQLException {
        for (DataRecord each : dataRecords) {
            executeUpdate(connection, each);
        }
    }
    
    private void executeUpdate(final Connection connection, final DataRecord dataRecord) throws SQLException {
        Set<String> shardingColumns = importerConfig.getShardingColumns(dataRecord.getTableName());
        List<Column> conditionColumns = RecordUtils.extractConditionColumns(dataRecord, shardingColumns);
        List<Column> updatedColumns = pipelineSqlBuilder.extractUpdatedColumns(dataRecord);
        String updateSql = pipelineSqlBuilder.buildUpdateSQL(getSchemaName(dataRecord.getTableName()), dataRecord, conditionColumns);
        try (PreparedStatement preparedStatement = connection.prepareStatement(updateSql)) {
            updateStatement.set(preparedStatement);
            for (int i = 0; i < updatedColumns.size(); i++) {
                preparedStatement.setObject(i + 1, updatedColumns.get(i).getValue());
            }
            for (int i = 0; i < conditionColumns.size(); i++) {
                Column keyColumn = conditionColumns.get(i);
                // TODO There to be compatible with PostgreSQL before value is null except primary key and unsupported updating sharding value now.
                if (shardingColumns.contains(keyColumn.getName()) && keyColumn.getOldValue() == null) {
                    preparedStatement.setObject(updatedColumns.size() + i + 1, keyColumn.getValue());
                    continue;
                }
                preparedStatement.setObject(updatedColumns.size() + i + 1, keyColumn.getOldValue());
            }
            // TODO if table without unique key the conditionColumns before values is null, so update will fail at PostgreSQL
            int updateCount = preparedStatement.executeUpdate();
            if (1 != updateCount) {
                log.warn("executeUpdate failed, updateCount={}, updateSql={}, updatedColumns={}, conditionColumns={}", updateCount, updateSql, updatedColumns, conditionColumns);
            }
        } finally {
            updateStatement.set(null);
        }
    }
    
    private void executeBatchDelete(final Connection connection, final List<DataRecord> dataRecords) throws SQLException {
        DataRecord dataRecord = dataRecords.get(0);
        List<Column> conditionColumns = RecordUtils.extractConditionColumns(dataRecord, importerConfig.getShardingColumns(dataRecord.getTableName()));
        String deleteSQL = pipelineSqlBuilder.buildDeleteSQL(getSchemaName(dataRecord.getTableName()), dataRecord, conditionColumns);
        try (PreparedStatement preparedStatement = connection.prepareStatement(deleteSQL)) {
            batchDeleteStatement.set(preparedStatement);
            preparedStatement.setQueryTimeout(30);
            for (DataRecord each : dataRecords) {
                for (int i = 0; i < conditionColumns.size(); i++) {
                    Object oldValue = conditionColumns.get(i).getOldValue();
                    if (null == oldValue) {
                        log.warn("Record old value is null, record={}", each);
                    }
                    preparedStatement.setObject(i + 1, oldValue);
                }
                preparedStatement.addBatch();
            }
            int[] counts = preparedStatement.executeBatch();
            if (IntStream.of(counts).anyMatch(value -> 1 != value)) {
                log.warn("batchDelete failed, counts={}, sql={}, conditionColumns={}", Arrays.toString(counts), deleteSQL, conditionColumns);
            }
        } finally {
            batchDeleteStatement.set(null);
        }
    }
    
    private void sequentialFlush(final DataSource dataSource, final List<DataRecord> buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            // TODO it's better use transaction, but execute delete maybe not effect when open transaction of PostgreSQL sometimes
            for (DataRecord each : buffer) {
                try {
                    doFlush(connection, each);
                } catch (final SQLException ex) {
                    throw new PipelineImporterJobWriteException(String.format("Write failed, record=%s", each), ex);
                }
            }
        } catch (final SQLException ex) {
            throw new PipelineImporterJobWriteException(ex);
        }
    }
    
    @Override
    protected void doStop() throws SQLException {
        cancelStatement(batchInsertStatement.get());
        cancelStatement(updateStatement.get());
        cancelStatement(batchDeleteStatement.get());
    }
}
