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

package org.apache.shardingsphere.underlying.pluggble.prepare;

import lombok.RequiredArgsConstructor;
import org.apache.shardingsphere.spi.order.OrderedRegistry;
import org.apache.shardingsphere.sql.parser.SQLParserEngine;
import org.apache.shardingsphere.underlying.common.config.properties.ConfigurationProperties;
import org.apache.shardingsphere.underlying.common.config.properties.ConfigurationPropertyKey;
import org.apache.shardingsphere.underlying.common.exception.ShardingSphereException;
import org.apache.shardingsphere.underlying.common.metadata.ShardingSphereMetaData;
import org.apache.shardingsphere.underlying.common.rule.BaseRule;
import org.apache.shardingsphere.underlying.executor.context.ExecutionContext;
import org.apache.shardingsphere.underlying.executor.context.ExecutionUnit;
import org.apache.shardingsphere.underlying.executor.context.SQLUnit;
import org.apache.shardingsphere.underlying.executor.log.SQLLogger;
import org.apache.shardingsphere.underlying.rewrite.SQLRewriteEntry;
import org.apache.shardingsphere.underlying.rewrite.context.SQLRewriteContext;
import org.apache.shardingsphere.underlying.rewrite.context.SQLRewriteContextDecorator;
import org.apache.shardingsphere.underlying.rewrite.engine.SQLRewriteEngine;
import org.apache.shardingsphere.underlying.rewrite.engine.SQLRewriteResult;
import org.apache.shardingsphere.underlying.rewrite.engine.SQLRouteRewriteEngine;
import org.apache.shardingsphere.underlying.route.DataNodeRouter;
import org.apache.shardingsphere.underlying.route.context.RouteContext;
import org.apache.shardingsphere.underlying.route.context.RouteUnit;
import org.apache.shardingsphere.underlying.route.decorator.RouteDecorator;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * Base prepare engine.
 */
@RequiredArgsConstructor
public abstract class BasePrepareEngine {
    
    private final Collection<BaseRule> rules;
    
    private final ConfigurationProperties properties;
    
    private final ShardingSphereMetaData metaData;
    
    private final DataNodeRouter router;
    
    private final SQLRewriteEntry rewriter;
    
    public BasePrepareEngine(final Collection<BaseRule> rules, final ConfigurationProperties properties, final ShardingSphereMetaData metaData, final SQLParserEngine parser) {
        this.rules = rules;
        this.properties = properties;
        this.metaData = metaData;
        router = new DataNodeRouter(metaData, properties, parser);
        rewriter = new SQLRewriteEntry(metaData.getSchema(), properties);
    }
    
    /**
     * Prepare to execute.
     *
     * @param sql SQL
     * @param parameters SQL parameters
     * @return execution context
     */
    public ExecutionContext prepare(final String sql, final List<Object> parameters) {
        // copy 一份参数列表
        List<Object> clonedParameters = cloneParameters(parameters);
        // 解析 路由
        RouteContext routeContext = executeRoute(sql, clonedParameters);
        ExecutionContext result = new ExecutionContext(routeContext.getSqlStatementContext());
        // 重写
        result.getExecutionUnits().addAll(executeRewrite(sql, clonedParameters, routeContext));
        // 打印sql
        if (properties.<Boolean>getValue(ConfigurationPropertyKey.SQL_SHOW)) {
            SQLLogger.logSQL(sql, properties.<Boolean>getValue(ConfigurationPropertyKey.SQL_SIMPLE), result.getSqlStatementContext(), result.getExecutionUnits());
        }
        return result;
    }
    
    protected abstract List<Object> cloneParameters(List<Object> parameters);
    
    private RouteContext executeRoute(final String sql, final List<Object> clonedParameters) {
        // 向DataNodeRouter实例中注册BaseRule对应的RouteDecorator
        registerRouteDecorator();
        return route(router, sql, clonedParameters);
    }
    
    private void registerRouteDecorator() {
        // 循环所有通过SPI机制注册的RouteDecorator实现类
        for (Class<? extends RouteDecorator> each : OrderedRegistry.getRegisteredClasses(RouteDecorator.class)) {
            RouteDecorator routeDecorator = createRouteDecorator(each);
            Class<?> ruleClass = (Class<?>) routeDecorator.getType();
            // 把分表规则BaseRule和RouteDecorator的对应关系注册到DataNodeRouter
            // FIXME rule.getClass().getSuperclass() == ruleClass for orchestration, should decouple extend between orchestration rule and sharding rule
            rules.stream().filter(rule -> rule.getClass() == ruleClass || rule.getClass().getSuperclass() == ruleClass).collect(Collectors.toList())
                    .forEach(rule -> router.registerDecorator(rule, routeDecorator));
        }
    }
    
    private RouteDecorator createRouteDecorator(final Class<? extends RouteDecorator> decorator) {
        try {
            return decorator.newInstance();
        } catch (final InstantiationException | IllegalAccessException ex) {
            throw new ShardingSphereException(String.format("Can not find public default constructor for route decorator `%s`", decorator), ex);
        }
    }
    
    protected abstract RouteContext route(DataNodeRouter dataNodeRouter, String sql, List<Object> parameters);
    
    private Collection<ExecutionUnit> executeRewrite(final String sql, final List<Object> parameters, final RouteContext routeContext) {
        registerRewriteDecorator();
        SQLRewriteContext sqlRewriteContext = rewriter.createSQLRewriteContext(sql, parameters, routeContext.getSqlStatementContext(), routeContext);
        return routeContext.getRouteResult().getRouteUnits().isEmpty() ? rewrite(sqlRewriteContext) : rewrite(routeContext, sqlRewriteContext);
    }
    
    private void registerRewriteDecorator() {
        for (Class<? extends SQLRewriteContextDecorator> each : OrderedRegistry.getRegisteredClasses(SQLRewriteContextDecorator.class)) {
            SQLRewriteContextDecorator rewriteContextDecorator = createRewriteDecorator(each);
            Class<?> ruleClass = (Class<?>) rewriteContextDecorator.getType();
            // FIXME rule.getClass().getSuperclass() == ruleClass for orchestration, should decouple extend between orchestration rule and sharding rule
            rules.stream().filter(rule -> rule.getClass() == ruleClass || rule.getClass().getSuperclass() == ruleClass).collect(Collectors.toList())
                    .forEach(rule -> rewriter.registerDecorator(rule, rewriteContextDecorator));
        }
    }
    
    private SQLRewriteContextDecorator createRewriteDecorator(final Class<? extends SQLRewriteContextDecorator> decorator) {
        try {
            return decorator.newInstance();
        } catch (final InstantiationException | IllegalAccessException ex) {
            throw new ShardingSphereException(String.format("Can not find public default constructor for rewrite decorator `%s`", decorator), ex);
        }
    }
    
    private Collection<ExecutionUnit> rewrite(final SQLRewriteContext sqlRewriteContext) {
        SQLRewriteResult sqlRewriteResult = new SQLRewriteEngine().rewrite(sqlRewriteContext);
        String dataSourceName = metaData.getDataSources().getAllInstanceDataSourceNames().iterator().next();
        return Collections.singletonList(new ExecutionUnit(dataSourceName, new SQLUnit(sqlRewriteResult.getSql(), sqlRewriteResult.getParameters())));
    }
    
    private Collection<ExecutionUnit> rewrite(final RouteContext routeContext, final SQLRewriteContext sqlRewriteContext) {
        Collection<ExecutionUnit> result = new LinkedHashSet<>();
        for (Entry<RouteUnit, SQLRewriteResult> entry : new SQLRouteRewriteEngine().rewrite(sqlRewriteContext, routeContext.getRouteResult()).entrySet()) {
            result.add(new ExecutionUnit(entry.getKey().getDataSourceMapper().getActualName(), new SQLUnit(entry.getValue().getSql(), entry.getValue().getParameters())));
        }
        return result;
    }
}
