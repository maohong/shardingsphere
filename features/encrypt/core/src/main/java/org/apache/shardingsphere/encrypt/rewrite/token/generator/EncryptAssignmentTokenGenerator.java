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
import org.apache.shardingsphere.encrypt.rewrite.aware.DatabaseNameAware;
import org.apache.shardingsphere.encrypt.rewrite.aware.EncryptRuleAware;
import org.apache.shardingsphere.encrypt.rewrite.token.pojo.EncryptAssignmentToken;
import org.apache.shardingsphere.encrypt.rewrite.token.pojo.EncryptLiteralAssignmentToken;
import org.apache.shardingsphere.encrypt.rewrite.token.pojo.EncryptParameterAssignmentToken;
import org.apache.shardingsphere.encrypt.rule.EncryptRule;
import org.apache.shardingsphere.infra.binder.statement.SQLStatementContext;
import org.apache.shardingsphere.infra.binder.statement.dml.InsertStatementContext;
import org.apache.shardingsphere.infra.binder.statement.dml.UpdateStatementContext;
import org.apache.shardingsphere.infra.binder.type.TableAvailable;
import org.apache.shardingsphere.infra.database.type.DatabaseTypeEngine;
import org.apache.shardingsphere.infra.rewrite.sql.token.generator.CollectionSQLTokenGenerator;
import org.apache.shardingsphere.sql.parser.sql.common.segment.dml.assignment.AssignmentSegment;
import org.apache.shardingsphere.sql.parser.sql.common.segment.dml.assignment.SetAssignmentSegment;
import org.apache.shardingsphere.sql.parser.sql.common.segment.dml.expr.simple.LiteralExpressionSegment;
import org.apache.shardingsphere.sql.parser.sql.common.segment.dml.expr.simple.ParameterMarkerExpressionSegment;
import org.apache.shardingsphere.sql.parser.sql.common.statement.SQLStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.dml.InsertStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.dml.UpdateStatement;
import org.apache.shardingsphere.sql.parser.sql.dialect.handler.dml.InsertStatementHandler;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Optional;

/**
 * Assignment generator for encrypt.
 */
@Setter
public final class EncryptAssignmentTokenGenerator implements CollectionSQLTokenGenerator<SQLStatementContext>, EncryptRuleAware, DatabaseNameAware {
    
    private EncryptRule encryptRule;
    
    private String databaseName;
    
    @Override
    public boolean isGenerateSQLToken(final SQLStatementContext sqlStatementContext) {
        return sqlStatementContext instanceof UpdateStatementContext || sqlStatementContext instanceof InsertStatementContext
                && InsertStatementHandler.getSetAssignmentSegment(((InsertStatementContext) sqlStatementContext).getSqlStatement()).isPresent();
    }
    
    @Override
    public Collection<EncryptAssignmentToken> generateSQLTokens(final SQLStatementContext sqlStatementContext) {
        Collection<EncryptAssignmentToken> result = new LinkedList<>();
        String tableName = ((TableAvailable) sqlStatementContext).getAllTables().iterator().next().getTableName().getIdentifier().getValue();
        String schemaName = sqlStatementContext.getTablesContext().getSchemaName().orElseGet(() -> DatabaseTypeEngine.getDefaultSchemaName(sqlStatementContext.getDatabaseType(), databaseName));
        for (AssignmentSegment each : getSetAssignmentSegment(sqlStatementContext.getSqlStatement()).getAssignments()) {
            if (encryptRule.findStandardEncryptor(tableName, each.getColumns().get(0).getIdentifier().getValue()).isPresent()) {
                generateSQLToken(schemaName, tableName, each).ifPresent(result::add);
            }
        }
        return result;
    }
    
    private SetAssignmentSegment getSetAssignmentSegment(final SQLStatement sqlStatement) {
        if (sqlStatement instanceof InsertStatement) {
            Optional<SetAssignmentSegment> result = InsertStatementHandler.getSetAssignmentSegment((InsertStatement) sqlStatement);
            Preconditions.checkState(result.isPresent());
            return result.get();
        }
        return ((UpdateStatement) sqlStatement).getSetAssignment();
    }
    
    private Optional<EncryptAssignmentToken> generateSQLToken(final String schemaName, final String tableName, final AssignmentSegment assignmentSegment) {
        if (assignmentSegment.getValue() instanceof ParameterMarkerExpressionSegment) {
            return Optional.of(generateParameterSQLToken(tableName, assignmentSegment));
        }
        if (assignmentSegment.getValue() instanceof LiteralExpressionSegment) {
            return Optional.of(generateLiteralSQLToken(schemaName, tableName, assignmentSegment));
        }
        return Optional.empty();
    }
    
    private EncryptAssignmentToken generateParameterSQLToken(final String tableName, final AssignmentSegment assignmentSegment) {
        EncryptParameterAssignmentToken result = new EncryptParameterAssignmentToken(assignmentSegment.getColumns().get(0).getStartIndex(), assignmentSegment.getStopIndex());
        String columnName = assignmentSegment.getColumns().get(0).getIdentifier().getValue();
        addCipherColumn(tableName, columnName, result);
        addAssistedQueryColumn(tableName, columnName, result);
        addLikeQueryColumn(tableName, columnName, result);
        return result;
    }
    
    private void addCipherColumn(final String tableName, final String columnName, final EncryptParameterAssignmentToken token) {
        token.addColumnName(encryptRule.getCipherColumn(tableName, columnName));
    }
    
    private void addAssistedQueryColumn(final String tableName, final String columnName, final EncryptParameterAssignmentToken token) {
        encryptRule.findAssistedQueryColumn(tableName, columnName).ifPresent(token::addColumnName);
    }
    
    private void addLikeQueryColumn(final String tableName, final String columnName, final EncryptParameterAssignmentToken token) {
        encryptRule.findLikeQueryColumn(tableName, columnName).ifPresent(token::addColumnName);
    }
    
    private EncryptAssignmentToken generateLiteralSQLToken(final String schemaName, final String tableName, final AssignmentSegment assignmentSegment) {
        EncryptLiteralAssignmentToken result = new EncryptLiteralAssignmentToken(assignmentSegment.getColumns().get(0).getStartIndex(), assignmentSegment.getStopIndex());
        addCipherAssignment(schemaName, tableName, assignmentSegment, result);
        addAssistedQueryAssignment(schemaName, tableName, assignmentSegment, result);
        addLikeAssignment(schemaName, tableName, assignmentSegment, result);
        return result;
    }
    
    private void addCipherAssignment(final String schemaName, final String tableName, final AssignmentSegment assignmentSegment, final EncryptLiteralAssignmentToken token) {
        Object originalValue = ((LiteralExpressionSegment) assignmentSegment.getValue()).getLiterals();
        Object cipherValue = encryptRule.getEncryptValues(databaseName, schemaName, tableName, assignmentSegment.getColumns().get(0).getIdentifier().getValue(),
                Collections.singletonList(originalValue)).iterator().next();
        token.addAssignment(encryptRule.getCipherColumn(tableName, assignmentSegment.getColumns().get(0).getIdentifier().getValue()), cipherValue);
    }
    
    private void addAssistedQueryAssignment(final String schemaName, final String tableName, final AssignmentSegment assignmentSegment, final EncryptLiteralAssignmentToken token) {
        Object originalValue = ((LiteralExpressionSegment) assignmentSegment.getValue()).getLiterals();
        Optional<String> assistedQueryColumn = encryptRule.findAssistedQueryColumn(tableName, assignmentSegment.getColumns().get(0).getIdentifier().getValue());
        if (assistedQueryColumn.isPresent()) {
            Object assistedQueryValue = encryptRule.getEncryptAssistedQueryValues(databaseName, schemaName,
                    tableName, assignmentSegment.getColumns().get(0).getIdentifier().getValue(), Collections.singletonList(originalValue)).iterator().next();
            token.addAssignment(assistedQueryColumn.get(), assistedQueryValue);
        }
    }
    
    private void addLikeAssignment(final String schemaName, final String tableName, final AssignmentSegment assignmentSegment, final EncryptLiteralAssignmentToken token) {
        Object originalValue = ((LiteralExpressionSegment) assignmentSegment.getValue()).getLiterals();
        Optional<String> assistedQueryColumn = encryptRule.findLikeQueryColumn(tableName, assignmentSegment.getColumns().get(0).getIdentifier().getValue());
        if (assistedQueryColumn.isPresent()) {
            Object assistedQueryValue = encryptRule.getEncryptLikeQueryValues(databaseName, schemaName,
                    tableName, assignmentSegment.getColumns().get(0).getIdentifier().getValue(), Collections.singletonList(originalValue)).iterator().next();
            token.addAssignment(assistedQueryColumn.get(), assistedQueryValue);
        }
    }
}
