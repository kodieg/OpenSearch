/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

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

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.action.bulk;

import org.opensearch.action.DocWriteRequest.OpType;
import org.opensearch.action.DocWriteResponse;
import org.opensearch.action.admin.indices.alias.Alias;
import org.opensearch.action.admin.indices.stats.IndicesStatsRequest;
import org.opensearch.action.admin.indices.stats.IndicesStatsResponse;
import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.WriteRequest.RefreshPolicy;
import org.opensearch.action.update.UpdateRequest;
import org.opensearch.action.update.UpdateRequestBuilder;
import org.opensearch.action.update.UpdateResponse;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.common.Strings;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.index.VersionType;
import org.opensearch.indices.IndexClosedException;
import org.opensearch.plugins.Plugin;
import org.opensearch.script.MockScriptPlugin;
import org.opensearch.script.Script;
import org.opensearch.script.ScriptException;
import org.opensearch.script.ScriptType;
import org.opensearch.test.InternalSettingsPlugin;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.transport.client.Requests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.function.Function;

import static org.opensearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertHitCount;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertNoFailures;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class BulkWithUpdatesIT extends OpenSearchIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(InternalSettingsPlugin.class, CustomScriptPlugin.class);
    }

    public static class CustomScriptPlugin extends MockScriptPlugin {

        @Override
        @SuppressWarnings("unchecked")
        protected Map<String, Function<Map<String, Object>, Object>> pluginScripts() {
            Map<String, Function<Map<String, Object>, Object>> scripts = new HashMap<>();

            scripts.put("ctx._source.field += 1", vars -> srcScript(vars, source -> {
                Integer field = (Integer) source.get("field");
                return source.replace("field", field + 1);
            }));

            scripts.put("ctx._source.counter += 1", vars -> srcScript(vars, source -> {
                Integer counter = (Integer) source.get("counter");
                return source.replace("counter", counter + 1);
            }));

            scripts.put("ctx._source.field2 = 'value2'", vars -> srcScript(vars, source -> source.replace("field2", "value2")));

            scripts.put("throw script exception on unknown var", vars -> {
                throw new ScriptException("message", null, Collections.emptyList(), "exception on unknown var", CustomScriptPlugin.NAME);
            });

            scripts.put("ctx.op = \"none\"", vars -> ((Map<String, Object>) vars.get("ctx")).put("op", "none"));
            scripts.put("ctx.op = \"delete\"", vars -> ((Map<String, Object>) vars.get("ctx")).put("op", "delete"));
            return scripts;
        }

        @SuppressWarnings("unchecked")
        static Object srcScript(Map<String, Object> vars, Function<Map<String, Object>, Object> f) {
            Map<?, ?> ctx = (Map<?, ?>) vars.get("ctx");

            Map<String, Object> source = (Map<String, Object>) ctx.get("_source");
            return f.apply(source);
        }
    }

    public void testBulkUpdateSimple() throws Exception {
        assertAcked(prepareCreate("test").addAlias(new Alias("alias")));
        ensureGreen();

        BulkResponse bulkResponse = client().prepareBulk()
            .add(client().prepareIndex().setIndex(indexOrAlias()).setId("1").setSource("field", 1))
            .add(client().prepareIndex().setIndex(indexOrAlias()).setId("2").setSource("field", 2).setCreate(true))
            .add(client().prepareIndex().setIndex(indexOrAlias()).setId("3").setSource("field", 3))
            .add(client().prepareIndex().setIndex(indexOrAlias()).setId("4").setSource("field", 4))
            .add(client().prepareIndex().setIndex(indexOrAlias()).setId("5").setSource("field", 5))
            .execute()
            .actionGet();

        assertThat(bulkResponse.hasFailures(), equalTo(false));
        assertThat(bulkResponse.getItems().length, equalTo(5));
        for (BulkItemResponse bulkItemResponse : bulkResponse) {
            assertThat(bulkItemResponse.getIndex(), equalTo("test"));
        }

        final Script script = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "ctx._source.field += 1", Collections.emptyMap());

        bulkResponse = client().prepareBulk()
            .add(client().prepareUpdate().setIndex(indexOrAlias()).setId("1").setScript(script))
            .add(client().prepareUpdate().setIndex(indexOrAlias()).setId("2").setScript(script).setRetryOnConflict(3))
            .add(
                client().prepareUpdate()
                    .setIndex(indexOrAlias())
                    .setId("3")
                    .setDoc(jsonBuilder().startObject().field("field1", "test").endObject())
            )
            .get();

        assertThat(bulkResponse.hasFailures(), equalTo(false));
        assertThat(bulkResponse.getItems().length, equalTo(3));
        for (BulkItemResponse bulkItemResponse : bulkResponse) {
            assertThat(bulkItemResponse.getIndex(), equalTo("test"));
        }
        assertThat(bulkResponse.getItems()[0].getResponse().getId(), equalTo("1"));
        assertThat(bulkResponse.getItems()[0].getResponse().getVersion(), equalTo(2L));
        assertThat(bulkResponse.getItems()[1].getResponse().getId(), equalTo("2"));
        assertThat(bulkResponse.getItems()[1].getResponse().getVersion(), equalTo(2L));
        assertThat(bulkResponse.getItems()[2].getResponse().getId(), equalTo("3"));
        assertThat(bulkResponse.getItems()[2].getResponse().getVersion(), equalTo(2L));

        GetResponse getResponse = client().prepareGet().setIndex("test").setId("1").execute().actionGet();
        assertThat(getResponse.isExists(), equalTo(true));
        assertThat(getResponse.getVersion(), equalTo(2L));
        assertThat(((Number) getResponse.getSource().get("field")).longValue(), equalTo(2L));

        getResponse = client().prepareGet().setIndex("test").setId("2").execute().actionGet();
        assertThat(getResponse.isExists(), equalTo(true));
        assertThat(getResponse.getVersion(), equalTo(2L));
        assertThat(((Number) getResponse.getSource().get("field")).longValue(), equalTo(3L));

        getResponse = client().prepareGet().setIndex("test").setId("3").execute().actionGet();
        assertThat(getResponse.isExists(), equalTo(true));
        assertThat(getResponse.getVersion(), equalTo(2L));
        assertThat(getResponse.getSource().get("field1").toString(), equalTo("test"));

        bulkResponse = client().prepareBulk()
            .add(
                client().prepareUpdate()
                    .setIndex(indexOrAlias())
                    .setId("6")
                    .setScript(script)
                    .setUpsert(jsonBuilder().startObject().field("field", 0).endObject())
            )
            .add(client().prepareUpdate().setIndex(indexOrAlias()).setId("7").setScript(script))
            .add(client().prepareUpdate().setIndex(indexOrAlias()).setId("2").setScript(script))
            .get();

        assertThat(bulkResponse.hasFailures(), equalTo(true));
        assertThat(bulkResponse.getItems().length, equalTo(3));
        assertThat(bulkResponse.getItems()[0].getResponse().getId(), equalTo("6"));
        assertThat(bulkResponse.getItems()[0].getResponse().getVersion(), equalTo(1L));
        assertThat(bulkResponse.getItems()[1].getResponse(), nullValue());
        assertThat(bulkResponse.getItems()[1].getFailure().getIndex(), equalTo("test"));
        assertThat(bulkResponse.getItems()[1].getFailure().getId(), equalTo("7"));
        assertThat(bulkResponse.getItems()[1].getFailure().getMessage(), containsString("document missing"));
        assertThat(bulkResponse.getItems()[2].getResponse().getId(), equalTo("2"));
        assertThat(bulkResponse.getItems()[2].getResponse().getIndex(), equalTo("test"));
        assertThat(bulkResponse.getItems()[2].getResponse().getVersion(), equalTo(3L));

        getResponse = client().prepareGet().setIndex("test").setId("6").execute().actionGet();
        assertThat(getResponse.isExists(), equalTo(true));
        assertThat(getResponse.getVersion(), equalTo(1L));
        assertThat(((Number) getResponse.getSource().get("field")).longValue(), equalTo(0L));

        getResponse = client().prepareGet().setIndex("test").setId("7").execute().actionGet();
        assertThat(getResponse.isExists(), equalTo(false));

        getResponse = client().prepareGet().setIndex("test").setId("2").execute().actionGet();
        assertThat(getResponse.isExists(), equalTo(true));
        assertThat(getResponse.getVersion(), equalTo(3L));
        assertThat(((Number) getResponse.getSource().get("field")).longValue(), equalTo(4L));
    }

    public void testBulkUpdateWithScriptedUpsertAndDynamicMappingUpdate() throws Exception {
        assertAcked(prepareCreate("test").addAlias(new Alias("alias")));
        ensureGreen();

        final Script script = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "ctx._source.field += 1", Collections.emptyMap());

        BulkResponse bulkResponse = client().prepareBulk()
            .add(
                client().prepareUpdate().setIndex(indexOrAlias()).setId("1").setScript(script).setScriptedUpsert(true).setUpsert("field", 1)
            )
            .add(
                client().prepareUpdate().setIndex(indexOrAlias()).setId("2").setScript(script).setScriptedUpsert(true).setUpsert("field", 1)
            )
            .get();

        logger.info(bulkResponse.buildFailureMessage());

        assertThat(bulkResponse.hasFailures(), equalTo(false));
        assertThat(bulkResponse.getItems().length, equalTo(2));
        for (BulkItemResponse bulkItemResponse : bulkResponse) {
            assertThat(bulkItemResponse.getIndex(), equalTo("test"));
        }
        assertThat(bulkResponse.getItems()[0].getResponse().getId(), equalTo("1"));
        assertThat(bulkResponse.getItems()[0].getResponse().getVersion(), equalTo(1L));
        assertThat(bulkResponse.getItems()[1].getResponse().getId(), equalTo("2"));
        assertThat(bulkResponse.getItems()[1].getResponse().getVersion(), equalTo(1L));

        GetResponse getResponse = client().prepareGet().setIndex("test").setId("1").execute().actionGet();
        assertThat(getResponse.isExists(), equalTo(true));
        assertThat(getResponse.getVersion(), equalTo(1L));
        assertThat(((Number) getResponse.getSource().get("field")).longValue(), equalTo(2L));

        getResponse = client().prepareGet().setIndex("test").setId("2").execute().actionGet();
        assertThat(getResponse.isExists(), equalTo(true));
        assertThat(getResponse.getVersion(), equalTo(1L));
        assertThat(((Number) getResponse.getSource().get("field")).longValue(), equalTo(2L));
    }

    public void testBulkWithCAS() throws Exception {
        createIndex("test", Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1).build());
        ensureGreen();
        BulkResponse bulkResponse = client().prepareBulk()
            .add(client().prepareIndex("test").setId("1").setCreate(true).setSource("field", "1"))
            .add(client().prepareIndex("test").setId("2").setCreate(true).setSource("field", "1"))
            .add(client().prepareIndex("test").setId("1").setSource("field", "2"))
            .get();

        assertEquals(DocWriteResponse.Result.CREATED, bulkResponse.getItems()[0].getResponse().getResult());
        assertThat(bulkResponse.getItems()[0].getResponse().getSeqNo(), equalTo(0L));
        assertEquals(DocWriteResponse.Result.CREATED, bulkResponse.getItems()[1].getResponse().getResult());
        assertThat(bulkResponse.getItems()[1].getResponse().getSeqNo(), equalTo(1L));
        assertEquals(DocWriteResponse.Result.UPDATED, bulkResponse.getItems()[2].getResponse().getResult());
        assertThat(bulkResponse.getItems()[2].getResponse().getSeqNo(), equalTo(2L));

        bulkResponse = client().prepareBulk()
            .add(client().prepareUpdate("test", "1").setIfSeqNo(40L).setIfPrimaryTerm(20).setDoc(Requests.INDEX_CONTENT_TYPE, "field", "2"))
            .add(client().prepareUpdate("test", "2").setDoc(Requests.INDEX_CONTENT_TYPE, "field", "2"))
            .add(client().prepareUpdate("test", "1").setIfSeqNo(2L).setIfPrimaryTerm(1).setDoc(Requests.INDEX_CONTENT_TYPE, "field", "3"))
            .get();

        assertThat(bulkResponse.getItems()[0].getFailureMessage(), containsString("version conflict"));
        assertThat(bulkResponse.getItems()[1].getResponse().getSeqNo(), equalTo(3L));
        assertThat(bulkResponse.getItems()[2].getResponse().getSeqNo(), equalTo(4L));

        bulkResponse = client().prepareBulk()
            .add(client().prepareIndex("test").setId("e1").setSource("field", "1").setVersion(10).setVersionType(VersionType.EXTERNAL))
            .add(client().prepareIndex("test").setId("e2").setSource("field", "1").setVersion(10).setVersionType(VersionType.EXTERNAL))
            .add(client().prepareIndex("test").setId("e1").setSource("field", "2").setVersion(12).setVersionType(VersionType.EXTERNAL))
            .get();

        assertEquals(DocWriteResponse.Result.CREATED, bulkResponse.getItems()[0].getResponse().getResult());
        assertThat(bulkResponse.getItems()[0].getResponse().getVersion(), equalTo(10L));
        assertEquals(DocWriteResponse.Result.CREATED, bulkResponse.getItems()[1].getResponse().getResult());
        assertThat(bulkResponse.getItems()[1].getResponse().getVersion(), equalTo(10L));
        assertEquals(DocWriteResponse.Result.UPDATED, bulkResponse.getItems()[2].getResponse().getResult());
        assertThat(bulkResponse.getItems()[2].getResponse().getVersion(), equalTo(12L));

        bulkResponse = client().prepareBulk()
            .add(client().prepareUpdate("test", "e1").setDoc(Requests.INDEX_CONTENT_TYPE, "field", "2").setIfSeqNo(10L).setIfPrimaryTerm(1))
            .add(client().prepareUpdate("test", "e1").setDoc(Requests.INDEX_CONTENT_TYPE, "field", "3").setIfSeqNo(20L).setIfPrimaryTerm(1))
            .get();

        assertThat(bulkResponse.getItems()[0].getFailureMessage(), containsString("version conflict"));
        assertThat(bulkResponse.getItems()[1].getFailureMessage(), containsString("version conflict"));
    }

    public void testBulkUpdateMalformedScripts() throws Exception {
        createIndex("test");
        ensureGreen();

        BulkResponse bulkResponse = client().prepareBulk()
            .add(client().prepareIndex().setIndex("test").setId("1").setSource("field", 1))
            .add(client().prepareIndex().setIndex("test").setId("2").setSource("field", 1))
            .add(client().prepareIndex().setIndex("test").setId("3").setSource("field", 1))
            .execute()
            .actionGet();

        assertThat(bulkResponse.hasFailures(), equalTo(false));
        assertThat(bulkResponse.getItems().length, equalTo(3));

        bulkResponse = client().prepareBulk()
            .add(
                client().prepareUpdate()
                    .setIndex("test")
                    .setId("1")
                    .setFetchSource("field", null)
                    .setScript(
                        new Script(
                            ScriptType.INLINE,
                            CustomScriptPlugin.NAME,
                            "throw script exception on unknown var",
                            Collections.emptyMap()
                        )
                    )
            )
            .add(
                client().prepareUpdate()
                    .setIndex("test")
                    .setId("2")
                    .setFetchSource("field", null)
                    .setScript(new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "ctx._source.field += 1", Collections.emptyMap()))
            )
            .add(
                client().prepareUpdate()
                    .setIndex("test")
                    .setId("3")
                    .setFetchSource("field", null)
                    .setScript(
                        new Script(
                            ScriptType.INLINE,
                            CustomScriptPlugin.NAME,
                            "throw script exception on unknown var",
                            Collections.emptyMap()
                        )
                    )
            )
            .execute()
            .actionGet();

        assertThat(bulkResponse.hasFailures(), equalTo(true));
        assertThat(bulkResponse.getItems().length, equalTo(3));
        assertThat(bulkResponse.getItems()[0].getFailure().getId(), equalTo("1"));
        assertThat(bulkResponse.getItems()[0].getFailure().getMessage(), containsString("failed to execute script"));
        assertThat(bulkResponse.getItems()[0].getResponse(), nullValue());

        assertThat(bulkResponse.getItems()[1].getResponse().getId(), equalTo("2"));
        assertThat(bulkResponse.getItems()[1].getResponse().getVersion(), equalTo(2L));
        assertThat(((UpdateResponse) bulkResponse.getItems()[1].getResponse()).getGetResult().sourceAsMap().get("field"), equalTo(2));
        assertThat(bulkResponse.getItems()[1].getFailure(), nullValue());

        assertThat(bulkResponse.getItems()[2].getFailure().getId(), equalTo("3"));
        assertThat(bulkResponse.getItems()[2].getFailure().getMessage(), containsString("failed to execute script"));
        assertThat(bulkResponse.getItems()[2].getResponse(), nullValue());
    }

    public void testBulkUpdateLargerVolume() throws Exception {
        createIndex("test");
        ensureGreen();

        int numDocs = scaledRandomIntBetween(100, 2000);
        if (numDocs % 2 == 1) {
            numDocs++; // this test needs an even num of docs
        }

        final Script script = new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "ctx._source.counter += 1", Collections.emptyMap());

        BulkRequestBuilder builder = client().prepareBulk();
        for (int i = 0; i < numDocs; i++) {
            builder.add(
                client().prepareUpdate()
                    .setIndex("test")
                    .setId(Integer.toString(i))
                    .setFetchSource("counter", null)
                    .setScript(script)
                    .setUpsert(jsonBuilder().startObject().field("counter", 1).endObject())
            );
        }

        BulkResponse response = builder.get();
        assertThat(response.hasFailures(), equalTo(false));
        assertThat(response.getItems().length, equalTo(numDocs));
        for (int i = 0; i < numDocs; i++) {
            assertThat(response.getItems()[i].getId(), equalTo(Integer.toString(i)));
            assertThat(response.getItems()[i].getVersion(), equalTo(1L));
            assertThat(response.getItems()[i].getIndex(), equalTo("test"));
            assertThat(response.getItems()[i].getOpType(), equalTo(OpType.UPDATE));
            assertThat(response.getItems()[i].getResponse().getId(), equalTo(Integer.toString(i)));
            assertThat(response.getItems()[i].getResponse().getVersion(), equalTo(1L));
            assertThat(((UpdateResponse) response.getItems()[i].getResponse()).getGetResult().sourceAsMap().get("counter"), equalTo(1));

            for (int j = 0; j < 5; j++) {
                GetResponse getResponse = client().prepareGet("test", Integer.toString(i)).execute().actionGet();
                assertThat(getResponse.isExists(), equalTo(true));
                assertThat(getResponse.getVersion(), equalTo(1L));
                assertThat(((Number) getResponse.getSource().get("counter")).longValue(), equalTo(1L));
            }
        }

        builder = client().prepareBulk();
        for (int i = 0; i < numDocs; i++) {
            UpdateRequestBuilder updateBuilder = client().prepareUpdate()
                .setIndex("test")
                .setId(Integer.toString(i))
                .setFetchSource("counter", null);
            if (i % 2 == 0) {
                updateBuilder.setScript(script);
            } else {
                updateBuilder.setDoc(jsonBuilder().startObject().field("counter", 2).endObject());
            }
            if (i % 3 == 0) {
                updateBuilder.setRetryOnConflict(3);
            }

            builder.add(updateBuilder);
        }

        response = builder.execute().actionGet();
        assertThat(response.hasFailures(), equalTo(false));
        assertThat(response.getItems().length, equalTo(numDocs));
        for (int i = 0; i < numDocs; i++) {
            assertThat(response.getItems()[i].getId(), equalTo(Integer.toString(i)));
            assertThat(response.getItems()[i].getVersion(), equalTo(2L));
            assertThat(response.getItems()[i].getIndex(), equalTo("test"));
            assertThat(response.getItems()[i].getOpType(), equalTo(OpType.UPDATE));
            assertThat(response.getItems()[i].getResponse().getId(), equalTo(Integer.toString(i)));
            assertThat(response.getItems()[i].getResponse().getVersion(), equalTo(2L));
            assertThat(((UpdateResponse) response.getItems()[i].getResponse()).getGetResult().sourceAsMap().get("counter"), equalTo(2));
        }

        builder = client().prepareBulk();
        int maxDocs = numDocs / 2 + numDocs;
        for (int i = (numDocs / 2); i < maxDocs; i++) {
            builder.add(client().prepareUpdate().setIndex("test").setId(Integer.toString(i)).setScript(script));
        }
        response = builder.execute().actionGet();
        assertThat(response.hasFailures(), equalTo(true));
        assertThat(response.getItems().length, equalTo(numDocs));
        for (int i = 0; i < numDocs; i++) {
            int id = i + (numDocs / 2);
            if (i >= (numDocs / 2)) {
                assertThat(response.getItems()[i].getFailure().getId(), equalTo(Integer.toString(id)));
                assertThat(response.getItems()[i].getFailure().getMessage(), containsString("document missing"));
            } else {
                assertThat(response.getItems()[i].getId(), equalTo(Integer.toString(id)));
                assertThat(response.getItems()[i].getVersion(), equalTo(3L));
                assertThat(response.getItems()[i].getIndex(), equalTo("test"));
                assertThat(response.getItems()[i].getOpType(), equalTo(OpType.UPDATE));
            }
        }

        builder = client().prepareBulk();
        for (int i = 0; i < numDocs; i++) {
            builder.add(
                client().prepareUpdate()
                    .setIndex("test")
                    .setId(Integer.toString(i))
                    .setScript(new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "ctx.op = \"none\"", Collections.emptyMap()))
            );
        }
        response = builder.execute().actionGet();
        assertThat(response.buildFailureMessage(), response.hasFailures(), equalTo(false));
        assertThat(response.getItems().length, equalTo(numDocs));
        for (int i = 0; i < numDocs; i++) {
            assertThat(response.getItems()[i].getItemId(), equalTo(i));
            assertThat(response.getItems()[i].getId(), equalTo(Integer.toString(i)));
            assertThat(response.getItems()[i].getIndex(), equalTo("test"));
            assertThat(response.getItems()[i].getOpType(), equalTo(OpType.UPDATE));
        }

        builder = client().prepareBulk();
        for (int i = 0; i < numDocs; i++) {
            builder.add(
                client().prepareUpdate()
                    .setIndex("test")
                    .setId(Integer.toString(i))
                    .setScript(new Script(ScriptType.INLINE, CustomScriptPlugin.NAME, "ctx.op = \"delete\"", Collections.emptyMap()))
            );
        }
        response = builder.execute().actionGet();
        assertThat("expected no failures but got: " + response.buildFailureMessage(), response.hasFailures(), equalTo(false));
        assertThat(response.getItems().length, equalTo(numDocs));
        for (int i = 0; i < numDocs; i++) {
            final BulkItemResponse itemResponse = response.getItems()[i];
            assertThat(itemResponse.getFailure(), nullValue());
            assertThat(itemResponse.isFailed(), equalTo(false));
            assertThat(itemResponse.getItemId(), equalTo(i));
            assertThat(itemResponse.getId(), equalTo(Integer.toString(i)));
            assertThat(itemResponse.getIndex(), equalTo("test"));
            assertThat(itemResponse.getOpType(), equalTo(OpType.UPDATE));
            for (int j = 0; j < 5; j++) {
                GetResponse getResponse = client().prepareGet("test", Integer.toString(i)).get();
                assertThat(getResponse.isExists(), equalTo(false));
            }
        }
        assertThat(response.hasFailures(), equalTo(false));
    }

    public void testBulkIndexingWhileInitializing() throws Exception {
        int replica = randomInt(2);

        internalCluster().ensureAtLeastNumDataNodes(1 + replica);

        assertAcked(prepareCreate("test").setSettings(Settings.builder().put(indexSettings()).put("index.number_of_replicas", replica)));

        int numDocs = scaledRandomIntBetween(100, 5000);
        int bulk = scaledRandomIntBetween(1, 99);
        for (int i = 0; i < numDocs;) {
            final BulkRequestBuilder builder = client().prepareBulk();
            for (int j = 0; j < bulk && i < numDocs; j++, i++) {
                builder.add(client().prepareIndex("test").setId(Integer.toString(i)).setSource("val", i));
            }
            logger.info("bulk indexing {}-{}", i - bulk, i - 1);
            BulkResponse response = builder.get();
            if (response.hasFailures()) {
                fail(response.buildFailureMessage());
            }
        }

        refresh();

        SearchResponse countResponse = client().prepareSearch().setSize(0).get();
        assertHitCount(countResponse, numDocs);
    }

    public void testFailingVersionedUpdatedOnBulk() throws Exception {
        createIndex("test");
        index("test", "type", "1", "field", "1");
        final BulkResponse[] responses = new BulkResponse[30];
        final CyclicBarrier cyclicBarrier = new CyclicBarrier(responses.length);
        Thread[] threads = new Thread[responses.length];

        for (int i = 0; i < responses.length; i++) {
            final int threadID = i;
            threads[threadID] = new Thread(() -> {
                try {
                    cyclicBarrier.await();
                } catch (Exception e) {
                    return;
                }
                BulkRequestBuilder requestBuilder = client().prepareBulk();
                requestBuilder.add(
                    client().prepareUpdate("test", "1")
                        .setIfSeqNo(0L)
                        .setIfPrimaryTerm(1)
                        .setDoc(Requests.INDEX_CONTENT_TYPE, "field", threadID)
                );
                responses[threadID] = requestBuilder.get();

            });
            threads[threadID].start();

        }

        for (int i = 0; i < threads.length; i++) {
            threads[i].join();
        }

        int successes = 0;
        for (BulkResponse response : responses) {
            if (!response.hasFailures()) {
                successes++;
            }
        }

        assertThat(successes, equalTo(1));
    }

    // issue 4987
    public void testThatInvalidIndexNamesShouldNotBreakCompleteBulkRequest() {
        int bulkEntryCount = randomIntBetween(10, 50);
        BulkRequestBuilder builder = client().prepareBulk();
        boolean[] expectedFailures = new boolean[bulkEntryCount];
        ArrayList<String> badIndexNames = new ArrayList<>();
        for (int i = randomIntBetween(1, 5); i > 0; i--) {
            badIndexNames.add("INVALID.NAME" + i);
        }
        boolean expectFailure = false;
        for (int i = 0; i < bulkEntryCount; i++) {
            expectFailure |= expectedFailures[i] = randomBoolean();
            String name;
            if (expectedFailures[i]) {
                name = randomFrom(badIndexNames);
            } else {
                name = "test";
            }
            builder.add(client().prepareIndex().setIndex(name).setId("1").setSource("field", 1));
        }
        BulkResponse bulkResponse = builder.get();
        assertThat(bulkResponse.hasFailures(), is(expectFailure));
        assertThat(bulkResponse.getItems().length, is(bulkEntryCount));
        for (int i = 0; i < bulkEntryCount; i++) {
            assertThat(bulkResponse.getItems()[i].isFailed(), is(expectedFailures[i]));
        }
    }

    // issue 6630
    public void testThatFailedUpdateRequestReturnsCorrectType() throws Exception {
        BulkResponse indexBulkItemResponse = client().prepareBulk()
            .add(new IndexRequest("test").id("3").source("{ \"title\" : \"Great Title of doc 3\" }", MediaTypeRegistry.JSON))
            .add(new IndexRequest("test").id("4").source("{ \"title\" : \"Great Title of doc 4\" }", MediaTypeRegistry.JSON))
            .add(new IndexRequest("test").id("5").source("{ \"title\" : \"Great Title of doc 5\" }", MediaTypeRegistry.JSON))
            .add(new IndexRequest("test").id("6").source("{ \"title\" : \"Great Title of doc 6\" }", MediaTypeRegistry.JSON))
            .setRefreshPolicy(RefreshPolicy.IMMEDIATE)
            .get();
        assertNoFailures(indexBulkItemResponse);

        BulkResponse bulkItemResponse = client().prepareBulk()
            .add(new IndexRequest("test").id("1").source("{ \"title\" : \"Great Title of doc 1\" }", MediaTypeRegistry.JSON))
            .add(new IndexRequest("test").id("2").source("{ \"title\" : \"Great Title of doc 2\" }", MediaTypeRegistry.JSON))
            .add(new UpdateRequest("test", "3").doc("{ \"date\" : \"2014-01-30T23:59:57\"}", MediaTypeRegistry.JSON))
            .add(new UpdateRequest("test", "4").doc("{ \"date\" : \"2014-13-30T23:59:57\"}", MediaTypeRegistry.JSON))
            .add(new DeleteRequest("test", "5"))
            .add(new DeleteRequest("test", "6"))
            .get();

        assertNoFailures(indexBulkItemResponse);
        assertThat(bulkItemResponse.getItems().length, is(6));
        assertThat(bulkItemResponse.getItems()[0].getOpType(), is(OpType.INDEX));
        assertThat(bulkItemResponse.getItems()[1].getOpType(), is(OpType.INDEX));
        assertThat(bulkItemResponse.getItems()[2].getOpType(), is(OpType.UPDATE));
        assertThat(bulkItemResponse.getItems()[3].getOpType(), is(OpType.UPDATE));
        assertThat(bulkItemResponse.getItems()[4].getOpType(), is(OpType.DELETE));
        assertThat(bulkItemResponse.getItems()[5].getOpType(), is(OpType.DELETE));
    }

    private static String indexOrAlias() {
        return randomBoolean() ? "test" : "alias";
    }

    // issue 6410
    public void testThatMissingIndexDoesNotAbortFullBulkRequest() throws Exception {
        createIndex("bulkindex1", "bulkindex2");
        BulkRequest bulkRequest = new BulkRequest();
        bulkRequest.add(new IndexRequest("bulkindex1").id("1").source(Requests.INDEX_CONTENT_TYPE, "text", "hallo1"))
            .add(new IndexRequest("bulkindex2").id("1").source(Requests.INDEX_CONTENT_TYPE, "text", "hallo2"))
            .add(new IndexRequest("bulkindex2").source(Requests.INDEX_CONTENT_TYPE, "text", "hallo2"))
            .add(new UpdateRequest("bulkindex2", "2").doc(Requests.INDEX_CONTENT_TYPE, "foo", "bar"))
            .add(new DeleteRequest("bulkindex2", "3"))
            .setRefreshPolicy(RefreshPolicy.IMMEDIATE);

        client().bulk(bulkRequest).get();
        SearchResponse searchResponse = client().prepareSearch("bulkindex*").get();
        assertHitCount(searchResponse, 3);

        assertBusy(() -> assertAcked(client().admin().indices().prepareClose("bulkindex2")));

        BulkRequest bulkRequest2 = new BulkRequest();
        bulkRequest2.add(new IndexRequest("bulkindex1").id("1").source(Requests.INDEX_CONTENT_TYPE, "text", "hallo1"))
            .add(new IndexRequest("bulkindex2").id("1").source(Requests.INDEX_CONTENT_TYPE, "text", "hallo2"))
            .add(new IndexRequest("bulkindex2").source(Requests.INDEX_CONTENT_TYPE, "text", "hallo2"))
            .add(new UpdateRequest("bulkindex2", "2").doc(Requests.INDEX_CONTENT_TYPE, "foo", "bar"))
            .add(new DeleteRequest("bulkindex2", "3"))
            .setRefreshPolicy(RefreshPolicy.IMMEDIATE);

        BulkResponse bulkResponse = client().bulk(bulkRequest2).get();
        assertThat(bulkResponse.hasFailures(), is(true));
        assertThat(bulkResponse.getItems().length, is(5));
    }

    // issue 9821
    public void testFailedRequestsOnClosedIndex() throws Exception {
        createIndex("bulkindex1");

        client().prepareIndex("bulkindex1").setId("1").setSource("text", "test").get();
        assertBusy(() -> assertAcked(client().admin().indices().prepareClose("bulkindex1")));

        BulkRequest bulkRequest = new BulkRequest().setRefreshPolicy(RefreshPolicy.IMMEDIATE);
        bulkRequest.add(new IndexRequest("bulkindex1").id("1").source(Requests.INDEX_CONTENT_TYPE, "text", "hallo1"))
            .add(new UpdateRequest("bulkindex1", "1").doc(Requests.INDEX_CONTENT_TYPE, "foo", "bar"))
            .add(new DeleteRequest("bulkindex1", "1"));

        BulkResponse bulkResponse = client().bulk(bulkRequest).get();
        assertThat(bulkResponse.hasFailures(), is(true));
        BulkItemResponse[] responseItems = bulkResponse.getItems();
        assertThat(responseItems.length, is(3));
        assertThat(responseItems[0].getOpType(), is(OpType.INDEX));
        assertThat(responseItems[0].getFailure().getCause(), instanceOf(IndexClosedException.class));
        assertThat(responseItems[1].getOpType(), is(OpType.UPDATE));
        assertThat(responseItems[1].getFailure().getCause(), instanceOf(IndexClosedException.class));
        assertThat(responseItems[2].getOpType(), is(OpType.DELETE));
        assertThat(responseItems[2].getFailure().getCause(), instanceOf(IndexClosedException.class));
    }

    // issue 9821
    public void testInvalidIndexNamesCorrectOpType() {
        BulkResponse bulkResponse = client().prepareBulk()
            .add(client().prepareIndex().setIndex("INVALID.NAME").setId("1").setSource(Requests.INDEX_CONTENT_TYPE, "field", 1))
            .add(client().prepareUpdate().setIndex("INVALID.NAME").setId("1").setDoc(Requests.INDEX_CONTENT_TYPE, "field", randomInt()))
            .add(client().prepareDelete().setIndex("INVALID.NAME").setId("1"))
            .get();
        assertThat(bulkResponse.getItems().length, is(3));
        assertThat(bulkResponse.getItems()[0].getOpType(), is(OpType.INDEX));
        assertThat(bulkResponse.getItems()[1].getOpType(), is(OpType.UPDATE));
        assertThat(bulkResponse.getItems()[2].getOpType(), is(OpType.DELETE));
    }

    public void testNoopUpdate() {
        String indexName = "test";
        createIndex(indexName, Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1).build());
        internalCluster().ensureAtLeastNumDataNodes(2);
        ensureGreen(indexName);
        IndexResponse doc = index(indexName, "_doc", "1", Collections.singletonMap("user", "xyz"));
        assertThat(doc.getShardInfo().getSuccessful(), equalTo(2));
        final BulkResponse bulkResponse = client().prepareBulk()
            .add(new UpdateRequest().index(indexName).id("1").detectNoop(true).doc("user", "xyz")) // noop update
            .add(new UpdateRequest().index(indexName).id("2").docAsUpsert(false).doc("f", "v")) // not_found update
            .add(new DeleteRequest().index(indexName).id("2")) // not_found delete
            .get();
        assertThat(bulkResponse.getItems(), arrayWithSize(3));

        final BulkItemResponse noopUpdate = bulkResponse.getItems()[0];
        assertThat(noopUpdate.getResponse().getResult(), equalTo(DocWriteResponse.Result.NOOP));
        assertThat(
            Strings.toString(MediaTypeRegistry.JSON, noopUpdate),
            noopUpdate.getResponse().getShardInfo().getSuccessful(),
            equalTo(2)
        );

        // test noop_update_total metric in stats changed
        IndicesStatsRequest indicesStatsRequest = new IndicesStatsRequest().indices(indexName).indexing(true);
        final IndicesStatsResponse indicesStatsResponse = client().admin().indices().stats(indicesStatsRequest).actionGet();
        assertThat(indicesStatsResponse.getIndex(indexName).getTotal().indexing.getTotal().getNoopUpdateCount(), equalTo(1L));
        assertThat(indicesStatsResponse.getIndex(indexName).getPrimaries().indexing.getTotal().getNoopUpdateCount(), equalTo(1L));

        final BulkItemResponse notFoundUpdate = bulkResponse.getItems()[1];
        assertNotNull(notFoundUpdate.getFailure());

        final BulkItemResponse notFoundDelete = bulkResponse.getItems()[2];
        assertThat(notFoundDelete.getResponse().getResult(), equalTo(DocWriteResponse.Result.NOT_FOUND));
        assertThat(
            Strings.toString(MediaTypeRegistry.JSON, notFoundDelete),
            notFoundDelete.getResponse().getShardInfo().getSuccessful(),
            equalTo(2)
        );
    }
}
