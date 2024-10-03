/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.rank.rrf;

import org.apache.lucene.search.TotalHits;
import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.retriever.CompoundRetrieverBuilder;
import org.elasticsearch.search.retriever.KnnRetrieverBuilder;
import org.elasticsearch.search.retriever.StandardRetrieverBuilder;
import org.elasticsearch.test.hamcrest.ElasticsearchAssertions;
import org.elasticsearch.xcontent.XContentType;

import java.util.Arrays;

import static org.elasticsearch.cluster.metadata.IndexMetadata.SETTING_NUMBER_OF_SHARDS;
import static org.hamcrest.Matchers.equalTo;

public class RRFRetrieverBuilderNestedDocsIT extends RRFRetrieverBuilderIT {

    private static final String LAST_30D_FIELD = "views.last30d";
    private static final String ALL_TIME_FIELD = "views.all";

    @Override
    protected void setupIndex() {
        String mapping = """
            {
              "properties": {
                "vector": {
                  "type": "dense_vector",
                  "dims": 1,
                  "element_type": "float",
                  "similarity": "l2_norm",
                  "index": true,
                  "index_options": {
                    "type": "hnsw"
                  }
                },
                "text": {
                  "type": "text"
                },
                "doc": {
                  "type": "keyword"
                },
                "topic": {
                  "type": "keyword"
                },
                "views": {
                    "type": "nested",
                    "properties": {
                        "last30d": {
                            "type": "integer"
                        },
                        "all": {
                            "type": "integer"
                        }
                    }
                }
              }
            }
            """;
        createIndex(INDEX, Settings.builder().put(SETTING_NUMBER_OF_SHARDS, 5).build());
        admin().indices().preparePutMapping(INDEX).setSource(mapping, XContentType.JSON).get();
        indexDoc(INDEX, "doc_1", DOC_FIELD, "doc_1", TOPIC_FIELD, "technology", TEXT_FIELD, "term", LAST_30D_FIELD, 100);
        indexDoc(
            INDEX,
            "doc_2",
            DOC_FIELD,
            "doc_2",
            TOPIC_FIELD,
            "astronomy",
            TEXT_FIELD,
            "search term term",
            VECTOR_FIELD,
            new float[] { 2.0f },
            LAST_30D_FIELD,
            3
        );
        indexDoc(INDEX, "doc_3", DOC_FIELD, "doc_3", TOPIC_FIELD, "technology", VECTOR_FIELD, new float[] { 3.0f });
        indexDoc(
            INDEX,
            "doc_4",
            DOC_FIELD,
            "doc_4",
            TOPIC_FIELD,
            "technology",
            TEXT_FIELD,
            "term term term term",
            ALL_TIME_FIELD,
            100,
            LAST_30D_FIELD,
            40
        );
        indexDoc(INDEX, "doc_5", DOC_FIELD, "doc_5", TOPIC_FIELD, "science", TEXT_FIELD, "irrelevant stuff");
        indexDoc(
            INDEX,
            "doc_6",
            DOC_FIELD,
            "doc_6",
            TEXT_FIELD,
            "search term term term term term term",
            VECTOR_FIELD,
            new float[] { 6.0f },
            LAST_30D_FIELD,
            15
        );
        indexDoc(
            INDEX,
            "doc_7",
            DOC_FIELD,
            "doc_7",
            TOPIC_FIELD,
            "biology",
            TEXT_FIELD,
            "term term term term term term term",
            VECTOR_FIELD,
            new float[] { 7.0f },
            ALL_TIME_FIELD,
            1000
        );
        refresh(INDEX);
    }

    public void testRRFRetrieverWithNestedQuery() {
        final int rankWindowSize = 100;
        final int rankConstant = 10;
        SearchSourceBuilder source = new SearchSourceBuilder();
        // this one retrieves docs 1, 4
        StandardRetrieverBuilder standard0 = new StandardRetrieverBuilder(
            QueryBuilders.nestedQuery("views", QueryBuilders.rangeQuery(LAST_30D_FIELD).gte(30L), ScoreMode.Avg)
        );
        // this one retrieves docs 2 and 6 due to prefilter
        StandardRetrieverBuilder standard1 = new StandardRetrieverBuilder(
            QueryBuilders.constantScoreQuery(QueryBuilders.termsQuery(ID_FIELD, "doc_2", "doc_3", "doc_6")).boost(20L)
        );
        standard1.getPreFilterQueryBuilders().add(QueryBuilders.queryStringQuery("search").defaultField(TEXT_FIELD));
        // this one retrieves docs 6
        KnnRetrieverBuilder knnRetrieverBuilder = new KnnRetrieverBuilder(VECTOR_FIELD, new float[] { 6.0f }, null, 1, 100, null);
        source.retriever(
            new RRFRetrieverBuilder(
                Arrays.asList(
                    new CompoundRetrieverBuilder.RetrieverSource(standard0, null),
                    new CompoundRetrieverBuilder.RetrieverSource(standard1, null),
                    new CompoundRetrieverBuilder.RetrieverSource(knnRetrieverBuilder, null)
                ),
                rankWindowSize,
                rankConstant
            )
        );
        source.fetchField(TOPIC_FIELD);
        SearchRequestBuilder req = client().prepareSearch(INDEX).setSource(source);
        ElasticsearchAssertions.assertResponse(req, resp -> {
            assertNull(resp.pointInTimeId());
            assertNotNull(resp.getHits().getTotalHits());
            assertThat(resp.getHits().getTotalHits().value, equalTo(4L));
            assertThat(resp.getHits().getTotalHits().relation, equalTo(TotalHits.Relation.EQUAL_TO));
            assertThat(resp.getHits().getAt(0).getId(), equalTo("doc_6"));
            assertThat(resp.getHits().getAt(1).getId(), equalTo("doc_1"));
            assertThat(resp.getHits().getAt(2).getId(), equalTo("doc_2"));
            assertThat(resp.getHits().getAt(3).getId(), equalTo("doc_4"));
        });
    }
}