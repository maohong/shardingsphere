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

package org.apache.shardingsphere.mode.manager.cluster.coordinator.subscriber;

import org.apache.shardingsphere.mode.manager.ContextManager;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.RegistryCenter;
import org.apache.shardingsphere.metadata.persist.MetaDataPersistService;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.process.subscriber.ProcessListChangedSubscriber;

/**
 * Context manager subscriber facade.
 */
public final class ContextManagerSubscriberFacade {
    
    public ContextManagerSubscriberFacade(final MetaDataPersistService persistService, final RegistryCenter registryCenter, final ContextManager contextManager) {
        new ConfigurationChangedSubscriber(persistService, registryCenter, contextManager);
        new ResourceMetaDataChangedSubscriber(contextManager);
        new DatabaseChangedSubscriber(contextManager);
        new StateChangedSubscriber(registryCenter, contextManager);
        new ProcessListChangedSubscriber(registryCenter, contextManager);
        new CacheEvictedSubscriber(contextManager.getInstanceContext().getEventBusContext());
    }
}
