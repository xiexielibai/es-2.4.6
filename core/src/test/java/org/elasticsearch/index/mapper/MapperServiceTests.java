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

package org.elasticsearch.index.mapper;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.Version;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.mapper.internal.TypeFieldMapper;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.elasticsearch.test.VersionUtils.getFirstVersion;
import static org.elasticsearch.test.VersionUtils.getPreviousVersion;
import static org.elasticsearch.test.VersionUtils.randomVersionBetween;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.startsWith;

public class MapperServiceTests extends ESSingleNodeTestCase {
    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    @Test
    public void testTypeNameStartsWithIllegalDot() {
        expectedException.expect(MapperParsingException.class);
        expectedException.expect(hasToString(containsString("mapping type name [.test-type] must not start with a '.'")));
        String index = "test-index";
        String type = ".test-type";
        String field = "field";
        client()
                .admin()
                .indices()
                .prepareCreate(index)
                .addMapping(type, field, "type=string")
                .execute()
                .actionGet();
    }

    @Test
    public void testThatLongTypeNameIsNotRejectedOnPreElasticsearchVersionTwo() {
        String index = "text-index";
        String field = "field";
        String type = new String(new char[256]).replace("\0", "a");

        CreateIndexResponse response =
                client()
                        .admin()
                        .indices()
                        .prepareCreate(index)
                        .setSettings(settings(randomVersionBetween(random(), getFirstVersion(), getPreviousVersion(Version.V_2_0_0_beta1))))
                        .addMapping(type, field, "type=string")
                        .execute()
                        .actionGet();
        assertNotNull(response);
    }

    @Test
    public void testTypeNameTooLong() {
        String index = "text-index";
        String field = "field";
        String type = new String(new char[256]).replace("\0", "a");

        expectedException.expect(MapperParsingException.class);
        expectedException.expect(hasToString(containsString("mapping type name [" + type + "] is too long; limit is length 255 but was [256]")));
        client()
                .admin()
                .indices()
                .prepareCreate(index)
                .addMapping(type, field, "type=string")
                .execute()
                .actionGet();
    }

    public void testTypes() throws Exception {
        IndexService indexService1 = createIndex("index1");
        MapperService mapperService = indexService1.mapperService();
        assertEquals(Collections.emptySet(), mapperService.types());

        mapperService.merge("type1", new CompressedXContent("{\"type1\":{}}"), MapperService.MergeReason.MAPPING_UPDATE, false);
        assertNull(mapperService.documentMapper(MapperService.DEFAULT_MAPPING));
        assertEquals(Collections.singleton("type1"), mapperService.types());

        mapperService.merge(MapperService.DEFAULT_MAPPING, new CompressedXContent("{\"_default_\":{}}"), MapperService.MergeReason.MAPPING_UPDATE, false);
        assertNotNull(mapperService.documentMapper(MapperService.DEFAULT_MAPPING));
        assertEquals(Collections.singleton("type1"), mapperService.types());

        mapperService.merge("type2", new CompressedXContent("{\"type2\":{}}"), MapperService.MergeReason.MAPPING_UPDATE, false);
        assertNotNull(mapperService.documentMapper(MapperService.DEFAULT_MAPPING));
        assertEquals(new HashSet<>(Arrays.asList("type1", "type2")), mapperService.types());
    }

    public void testIndexIntoDefaultMapping() throws Throwable {
        // 1. test implicit index creation
        try {
            client().prepareIndex("index1", MapperService.DEFAULT_MAPPING, "1").setSource("{").execute().get();
            fail();
        } catch (Throwable t) {
            if (t instanceof ExecutionException) {
                t = ((ExecutionException) t).getCause();
            }
            final Throwable throwable = ExceptionsHelper.unwrapCause(t);
            if (throwable instanceof IllegalArgumentException) {
                assertEquals("It is forbidden to index into the default mapping [_default_]", throwable.getMessage());
            } else {
                throw t;
            }
        }

        // 2. already existing index
        IndexService indexService = createIndex("index2");
        try {
            client().prepareIndex("index2", MapperService.DEFAULT_MAPPING, "2").setSource().execute().get();
            fail();
        } catch (Throwable t) {
            if (t instanceof ExecutionException) {
                t = ((ExecutionException) t).getCause();
            }
            final Throwable throwable = ExceptionsHelper.unwrapCause(t);
            if (throwable instanceof IllegalArgumentException) {
                assertEquals("It is forbidden to index into the default mapping [_default_]", throwable.getMessage());
            } else {
                throw t;
            }
        }
        assertFalse(indexService.mapperService().hasMapping(MapperService.DEFAULT_MAPPING));
    }

    @Test
    public void testSearchFilter() {
        IndexService indexService = createIndex("index1", client().admin().indices().prepareCreate("index1")
            .addMapping("type1", "field1", "type=nested")
            .addMapping("type2", new Object[0])
        );

        Query searchFilter = indexService.mapperService().searchFilter("type1", "type3");
        Query expectedQuery = new BooleanQuery.Builder()
            .add(new BooleanQuery.Builder()
                .add(new ConstantScoreQuery(new TermQuery(new Term(TypeFieldMapper.NAME, "type1"))), BooleanClause.Occur.SHOULD)
                .add(new TermQuery(new Term(TypeFieldMapper.NAME, "type3")), BooleanClause.Occur.SHOULD)
                .build(), BooleanClause.Occur.MUST
            )
            .add(Queries.newNonNestedFilter(), BooleanClause.Occur.MUST)
            .build();
        assertThat(searchFilter, Matchers.<Query>equalTo(new ConstantScoreQuery(expectedQuery)));
    }

    @Test
    public void testMergeWithMap() throws Throwable {
        IndexService indexService1 = createIndex("index1");
        MapperService mapperService = indexService1.mapperService();
        Map<String, Map<String, Object>> mappings = new HashMap<>();

        mappings.put(MapperService.DEFAULT_MAPPING, MapperService.parseMapping("{}"));
        try {
            mapperService.merge(mappings, false);
            fail("should throw exception");
        } catch (MapperParsingException e) {
            assertThat(e.getMessage(), startsWith("Failed to parse mapping [" + MapperService.DEFAULT_MAPPING + "]: "));
        }

        mappings.clear();
        mappings.put("type1", MapperService.parseMapping("{}"));

        try {
            mapperService.merge(mappings, false);
            fail("should throw exception");
        } catch (MapperParsingException e) {
            assertThat(e.getMessage(), startsWith("Failed to parse mapping [type1]: "));
        }
    }

    @Test
    public void testOtherDocumentMappersOnlyUpdatedWhenChangingFieldType() throws IOException {
        IndexService indexService = createIndex("test");

        CompressedXContent simpleMapping = new CompressedXContent(XContentFactory.jsonBuilder().startObject()
            .startObject("properties")
            .startObject("field")
            .field("type", "string")
            .endObject()
            .endObject().endObject().bytes());

        indexService.mapperService().merge("type1", simpleMapping, MapperService.MergeReason.MAPPING_UPDATE, true);
        DocumentMapper documentMapper = indexService.mapperService().documentMapper("type1");

        indexService.mapperService().merge("type2", simpleMapping, MapperService.MergeReason.MAPPING_UPDATE, true);
        assertSame(indexService.mapperService().documentMapper("type1"), documentMapper);

        CompressedXContent normsDisabledMapping = new CompressedXContent(XContentFactory.jsonBuilder().startObject()
            .startObject("properties")
            .startObject("field")
            .field("type", "string")
            .startObject("norms")
            .field("enabled", false)
            .endObject()
            .endObject()
            .endObject().endObject().bytes());

        indexService.mapperService().merge("type3", normsDisabledMapping, MapperService.MergeReason.MAPPING_UPDATE, true);
        assertNotSame(indexService.mapperService().documentMapper("type1"), documentMapper);
    }
}
