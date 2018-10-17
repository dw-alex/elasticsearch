/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.Version;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingClusterStateUpdateRequest;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.test.VersionUtils;

import java.util.Collections;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

public class MetaDataMappingServiceTests extends ESSingleNodeTestCase {
    @Override
    protected boolean forbidPrivateIndexSettings() {
        // to force the index version in the _parent test
        return false;
    }

    // Tests _parent meta field logic, because part of the validation is in MetaDataMappingService
    public void testAddChildTypePointingToAlreadyExistingType() throws Exception {
        Version version = VersionUtils.randomVersionBetween(random(), Version.V_6_0_0, Version.V_6_4_0);
        createIndex("test", Settings.builder().put(IndexMetaData.SETTING_VERSION_CREATED, version).build(),
            "type", "field", "type=keyword");

        // Shouldn't be able the add the _parent field pointing to an already existing type, which isn't a parent type
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> client().admin()
                .indices()
                .preparePutMapping("test")
                .setType("child")
                .setSource("_parent", "type=type")
                .get());
        assertThat(e.getMessage(),
                equalTo("can't add a _parent field that points to an already existing type, that isn't already a parent"));
    }

    public void testParentIsAString() throws Exception {
        Version version = VersionUtils.randomVersionBetween(random(), Version.V_6_0_0, Version.V_6_4_0);
        // Shouldn't be able the add the _parent field pointing to an already existing type, which isn't a parent type
        Exception e = expectThrows(MapperParsingException.class, () -> client().admin().indices().prepareCreate("test")
                .setSettings(Settings.builder().put(IndexMetaData.SETTING_VERSION_CREATED, version))
                .addMapping("parent", "{\"properties\":{}}", XContentType.JSON)
                .addMapping("child", "{\"_parent\": \"parent\",\"properties\":{}}", XContentType.JSON)
                .get());
        assertEquals("Failed to parse mapping [child]: [_parent] must be an object containing [type]", e.getMessage());
    }

    public void testMappingClusterStateUpdateDoesntChangeExistingIndices() throws Exception {
        final IndexService indexService = createIndex("test", client().admin().indices().prepareCreate("test").addMapping("type"));
        final CompressedXContent currentMapping = indexService.mapperService().documentMapper("type").mappingSource();

        final MetaDataMappingService mappingService = getInstanceFromNode(MetaDataMappingService.class);
        final ClusterService clusterService = getInstanceFromNode(ClusterService.class);
        // TODO - it will be nice to get a random mapping generator
        final PutMappingClusterStateUpdateRequest request = new PutMappingClusterStateUpdateRequest().type("type");
        request.indices(new Index[] {indexService.index()});
        request.source("{ \"properties\": { \"field\": { \"type\": \"text\" }}}");
        final ClusterStateTaskExecutor.ClusterTasksResult<PutMappingClusterStateUpdateRequest> result =
                mappingService.putMappingExecutor.execute(clusterService.state(), Collections.singletonList(request));
        // the task completed successfully
        assertThat(result.executionResults.size(), equalTo(1));
        assertTrue(result.executionResults.values().iterator().next().isSuccess());
        // the task really was a mapping update
        assertThat(
                indexService.mapperService().documentMapper("type").mappingSource(),
                not(equalTo(result.resultingState.metaData().index("test").mapping("type").source())));
        // since we never committed the cluster state update, the in-memory state is unchanged
        assertThat(indexService.mapperService().documentMapper("type").mappingSource(), equalTo(currentMapping));
    }

    public void testClusterStateIsNotChangedWithIdenticalMappings() throws Exception {
        createIndex("test", client().admin().indices().prepareCreate("test").addMapping("type"));

        final MetaDataMappingService mappingService = getInstanceFromNode(MetaDataMappingService.class);
        final ClusterService clusterService = getInstanceFromNode(ClusterService.class);
        final PutMappingClusterStateUpdateRequest request = new PutMappingClusterStateUpdateRequest().type("type");
        request.source("{ \"properties\" { \"field\": { \"type\": \"text\" }}}");
        ClusterState result = mappingService.putMappingExecutor.execute(clusterService.state(), Collections.singletonList(request))
            .resultingState;

        assertFalse(result != clusterService.state());

        ClusterState result2 = mappingService.putMappingExecutor.execute(result, Collections.singletonList(request))
            .resultingState;

        assertSame(result, result2);
    }

    public void testMappingVersion() throws Exception {
        final IndexService indexService = createIndex("test", client().admin().indices().prepareCreate("test").addMapping("type"));
        final long previousVersion = indexService.getMetaData().getMappingVersion();
        final MetaDataMappingService mappingService = getInstanceFromNode(MetaDataMappingService.class);
        final ClusterService clusterService = getInstanceFromNode(ClusterService.class);
        final PutMappingClusterStateUpdateRequest request = new PutMappingClusterStateUpdateRequest().type("type");
        request.indices(new Index[] {indexService.index()});
        request.source("{ \"properties\": { \"field\": { \"type\": \"text\" }}}");
        final ClusterStateTaskExecutor.ClusterTasksResult<PutMappingClusterStateUpdateRequest> result =
                mappingService.putMappingExecutor.execute(clusterService.state(), Collections.singletonList(request));
        assertThat(result.executionResults.size(), equalTo(1));
        assertTrue(result.executionResults.values().iterator().next().isSuccess());
        assertThat(result.resultingState.metaData().index("test").getMappingVersion(), equalTo(1 + previousVersion));
    }

    public void testMappingVersionUnchanged() throws Exception {
        final IndexService indexService = createIndex("test", client().admin().indices().prepareCreate("test").addMapping("type"));
        final long previousVersion = indexService.getMetaData().getMappingVersion();
        final MetaDataMappingService mappingService = getInstanceFromNode(MetaDataMappingService.class);
        final ClusterService clusterService = getInstanceFromNode(ClusterService.class);
        final PutMappingClusterStateUpdateRequest request = new PutMappingClusterStateUpdateRequest().type("type");
        request.indices(new Index[] {indexService.index()});
        request.source("{ \"properties\": {}}");
        final ClusterStateTaskExecutor.ClusterTasksResult<PutMappingClusterStateUpdateRequest> result =
                mappingService.putMappingExecutor.execute(clusterService.state(), Collections.singletonList(request));
        assertThat(result.executionResults.size(), equalTo(1));
        assertTrue(result.executionResults.values().iterator().next().isSuccess());
        assertThat(result.resultingState.metaData().index("test").getMappingVersion(), equalTo(previousVersion));
    }

}
