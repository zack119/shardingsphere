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

package org.apache.shardingsphere.data.pipeline.core.spi.handler;

import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.data.pipeline.core.job.PipelineJobIdUtils;
import org.apache.shardingsphere.data.pipeline.core.metadata.node.PipelineMetaDataNode;
import org.apache.shardingsphere.data.pipeline.core.spi.process.PipelineJobConfigurationChangedEventProcessor;
import org.apache.shardingsphere.data.pipeline.core.spi.process.PipelineJobConfigurationChangedEventProcessorFactory;
import org.apache.shardingsphere.elasticjob.infra.pojo.JobConfigurationPOJO;
import org.apache.shardingsphere.infra.util.yaml.YamlEngine;
import org.apache.shardingsphere.mode.repository.cluster.listener.DataChangedEvent;

import java.util.regex.Pattern;

/**
 * Job config changed handler.
 */
@Slf4j
public final class JobConfigurationChangedHandler implements PipelineMetaDataChangedHandler {
    
    @Override
    public Pattern getKeyPattern() {
        return PipelineMetaDataNode.CONFIG_PATTERN;
    }
    
    @Override
    public void handle(final DataChangedEvent event) {
        log.info("{} job config: {}", event.getType(), event.getKey());
        JobConfigurationPOJO jobConfigPOJO;
        try {
            jobConfigPOJO = YamlEngine.unmarshal(event.getValue(), JobConfigurationPOJO.class, true);
            // CHECKSTYLE:OFF
        } catch (final Exception ex) {
            // CHECKSTYLE:ON
            log.error("analyze job config pojo failed.", ex);
            return;
        }
        PipelineJobConfigurationChangedEventProcessor processor = PipelineJobConfigurationChangedEventProcessorFactory.getInstance(PipelineJobIdUtils.parseJobType(jobConfigPOJO.getJobName()));
        processor.process(event, jobConfigPOJO);
    }
}
