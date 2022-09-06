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

package org.apache.shardingsphere.underlying.route;

import lombok.RequiredArgsConstructor;
import org.apache.shardingsphere.sql.parser.SQLParserEngine;
import org.apache.shardingsphere.sql.parser.binder.SQLStatementContextFactory;
import org.apache.shardingsphere.sql.parser.binder.statement.CommonSQLStatementContext;
import org.apache.shardingsphere.sql.parser.binder.statement.SQLStatementContext;
import org.apache.shardingsphere.sql.parser.sql.statement.SQLStatement;
import org.apache.shardingsphere.underlying.common.config.properties.ConfigurationProperties;
import org.apache.shardingsphere.underlying.common.metadata.ShardingSphereMetaData;
import org.apache.shardingsphere.underlying.common.rule.BaseRule;
import org.apache.shardingsphere.underlying.route.context.RouteContext;
import org.apache.shardingsphere.underlying.route.context.RouteResult;
import org.apache.shardingsphere.underlying.route.decorator.RouteDecorator;
import org.apache.shardingsphere.underlying.route.hook.SPIRoutingHook;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Data node router.
 */
@RequiredArgsConstructor
public final class DataNodeRouter {
    /**
     * 数据源 表结构信息
     */
    private final ShardingSphereMetaData metaData;

    /**
     * 配置信息
     */
    private final ConfigurationProperties properties;

    /**
     * 解析引擎
     */
    private final SQLParserEngine parserEngine;

    /**
     * BaseRule-RouteDecorator映射关系，BasePrepareEngine注入
     */
    private final Map<BaseRule, RouteDecorator> decorators = new LinkedHashMap<>();

    /**
     * SPI钩子 暴露给客户的扩展点
     */
    private SPIRoutingHook routingHook = new SPIRoutingHook();
    
    /**
     * Register route decorator.
     *
     * @param rule rule
     * @param decorator route decorator
     */
    public void registerDecorator(final BaseRule rule, final RouteDecorator decorator) {
        decorators.put(rule, decorator);
    }
    
    /**
     * Route SQL.
     *
     * @param sql SQL
     * @param parameters SQL parameters
     * @param useCache whether cache SQL parse result
     * @return route context
     */
    public RouteContext route(final String sql, final List<Object> parameters, final boolean useCache) {
        routingHook.start(sql);
        try {
            // 解析 & 路由
            RouteContext result = executeRoute(sql, parameters, useCache);
            routingHook.finishSuccess(result, metaData.getSchema());
            return result;
            // CHECKSTYLE:OFF
        } catch (final Exception ex) {
            // CHECKSTYLE:ON
            routingHook.finishFailure(ex);
            throw ex;
        }
    }
    
    @SuppressWarnings("unchecked")
    private RouteContext executeRoute(final String sql, final List<Object> parameters, final boolean useCache) {
        // 解析
        RouteContext result = createRouteContext(sql, parameters, useCache);
        // 路由
        for (Entry<BaseRule, RouteDecorator> entry : decorators.entrySet()) {
            result = entry.getValue().decorate(result, metaData, entry.getKey(), properties);
        }
        return result;
    }
    
    private RouteContext createRouteContext(final String sql, final List<Object> parameters, final boolean useCache) {
        // SQL解析成SQLStatement
        SQLStatement sqlStatement = parserEngine.parse(sql, useCache);
        try {
            SQLStatementContext sqlStatementContext = SQLStatementContextFactory.newInstance(metaData.getSchema(), sql, parameters, sqlStatement);
            return new RouteContext(sqlStatementContext, parameters, new RouteResult());
            // TODO should pass parameters for master-slave
        } catch (final IndexOutOfBoundsException ex) {
            return new RouteContext(new CommonSQLStatementContext(sqlStatement), parameters, new RouteResult());
        }
    }
}
