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

package org.apache.shardingsphere.encrypt.rewrite.token.generator;

import com.google.common.base.Preconditions;
import lombok.Setter;
import org.apache.shardingsphere.encrypt.exception.metadata.EncryptTableNotFoundException;
import org.apache.shardingsphere.encrypt.rewrite.aware.EncryptRuleAware;
import org.apache.shardingsphere.encrypt.rule.EncryptRule;
import org.apache.shardingsphere.encrypt.rule.EncryptTable;
import org.apache.shardingsphere.infra.binder.statement.SQLStatementContext;
import org.apache.shardingsphere.infra.binder.statement.dml.InsertStatementContext;
import org.apache.shardingsphere.infra.rewrite.sql.token.generator.OptionalSQLTokenGenerator;
import org.apache.shardingsphere.infra.rewrite.sql.token.generator.aware.PreviousSQLTokensAware;
import org.apache.shardingsphere.infra.rewrite.sql.token.pojo.SQLToken;
import org.apache.shardingsphere.infra.rewrite.sql.token.pojo.generic.UseDefaultInsertColumnsToken;
import org.apache.shardingsphere.infra.util.exception.ShardingSpherePreconditions;
import org.apache.shardingsphere.sql.parser.sql.common.segment.dml.column.InsertColumnsSegment;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/**
 * Use default insert columns token generator for encrypt.
 */
@Setter
public final class EncryptForUseDefaultInsertColumnsTokenGenerator implements OptionalSQLTokenGenerator<InsertStatementContext>, PreviousSQLTokensAware, EncryptRuleAware {
    
    private List<SQLToken> previousSQLTokens;
    
    private EncryptRule encryptRule;
    
    @Override
    public boolean isGenerateSQLToken(final SQLStatementContext sqlStatementContext) {
        return sqlStatementContext instanceof InsertStatementContext && !((InsertStatementContext) sqlStatementContext).containsInsertColumns();
    }
    
    @Override
    public UseDefaultInsertColumnsToken generateSQLToken(final InsertStatementContext insertStatementContext) {
        String tableName = insertStatementContext.getSqlStatement().getTable().getTableName().getIdentifier().getValue();
        Optional<UseDefaultInsertColumnsToken> previousSQLToken = findInsertColumnsToken();
        if (previousSQLToken.isPresent()) {
            processPreviousSQLToken(previousSQLToken.get(), insertStatementContext, tableName);
            return previousSQLToken.get();
        }
        return generateNewSQLToken(insertStatementContext, tableName);
    }
    
    private Optional<UseDefaultInsertColumnsToken> findInsertColumnsToken() {
        for (SQLToken each : previousSQLTokens) {
            if (each instanceof UseDefaultInsertColumnsToken) {
                return Optional.of((UseDefaultInsertColumnsToken) each);
            }
        }
        return Optional.empty();
    }
    
    private void processPreviousSQLToken(final UseDefaultInsertColumnsToken previousSQLToken, final InsertStatementContext insertStatementContext, final String tableName) {
        Optional<EncryptTable> encryptTable = encryptRule.findEncryptTable(tableName);
        ShardingSpherePreconditions.checkState(encryptTable.isPresent(), () -> new EncryptTableNotFoundException(tableName));
        List<String> columnNames = getColumnNames(insertStatementContext, encryptTable.get(), previousSQLToken.getColumns());
        previousSQLToken.getColumns().clear();
        previousSQLToken.getColumns().addAll(columnNames);
    }
    
    private UseDefaultInsertColumnsToken generateNewSQLToken(final InsertStatementContext insertStatementContext, final String tableName) {
        Optional<InsertColumnsSegment> insertColumnsSegment = insertStatementContext.getSqlStatement().getInsertColumns();
        Preconditions.checkState(insertColumnsSegment.isPresent());
        Optional<EncryptTable> encryptTable = encryptRule.findEncryptTable(tableName);
        ShardingSpherePreconditions.checkState(encryptTable.isPresent(), () -> new EncryptTableNotFoundException(tableName));
        return new UseDefaultInsertColumnsToken(insertColumnsSegment.get().getStopIndex(), getColumnNames(insertStatementContext, encryptTable.get(), insertStatementContext.getColumnNames()));
    }
    
    private List<String> getColumnNames(final InsertStatementContext sqlStatementContext, final EncryptTable encryptTable, final List<String> currentColumnNames) {
        List<String> result = new LinkedList<>(currentColumnNames);
        Iterator<String> descendingColumnNames = sqlStatementContext.getDescendingColumnNames();
        while (descendingColumnNames.hasNext()) {
            String columnName = descendingColumnNames.next();
            if (encryptTable.findEncryptorName(columnName).isPresent()) {
                int columnIndex = result.indexOf(columnName);
                setCipherColumn(result, encryptTable, columnName, columnIndex);
                if (encryptTable.findAssistedQueryColumn(columnName).isPresent()) {
                    addAssistedQueryColumn(result, encryptTable, columnName, columnIndex);
                    columnIndex++;
                }
                if (encryptTable.findLikeQueryEncryptorName(columnName).isPresent()) {
                    addLikeQueryColumn(result, encryptTable, columnName, columnIndex);
                }
            }
        }
        return result;
    }
    
    private void addAssistedQueryColumn(final List<String> columnNames, final EncryptTable encryptTable, final String columnName, final int columnIndex) {
        encryptTable.findAssistedQueryColumn(columnName).ifPresent(optional -> columnNames.add(columnIndex + 1, optional));
    }
    
    private void addLikeQueryColumn(final List<String> columnNames, final EncryptTable encryptTable, final String columnName, final int columnIndex) {
        encryptTable.findLikeQueryColumn(columnName).ifPresent(optional -> columnNames.add(columnIndex + 1, optional));
    }
    
    private void setCipherColumn(final List<String> columnNames, final EncryptTable encryptTable, final String columnName, final int columnIndex) {
        columnNames.set(columnIndex, encryptTable.getCipherColumn(columnName));
    }
}
