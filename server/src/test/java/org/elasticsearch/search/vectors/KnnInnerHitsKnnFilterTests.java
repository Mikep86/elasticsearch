/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.vectors;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.common.lucene.index.ElasticsearchDirectoryReader;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.MapperServiceTestCase;
import org.elasticsearch.index.query.InnerHitContextBuilder;
import org.elasticsearch.index.query.InnerHitsRewriteContext;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.Rewriteable;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.shard.ShardId;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks the inner hits of a knn query on a nested vector whose filter contains another knn query. Inner hits rewrite both
 * knn queries to exact ones, and must select the chapters whose filter matches at the level the main query evaluates it.
 * <p>
 * Books have a top level vector {@code v} and chapters, which have paragraphs. The outer knn query is on the chapters' vector.
 */
public class KnnInnerHitsKnnFilterTests extends MapperServiceTestCase {

    private static final String MAPPING = """
        { "_doc": { "properties": {
          "genre": { "type": "keyword" },
          "v": { "type": "dense_vector", "dims": 2, "similarity": "l2_norm", "index_options": { "type": "flat" } },
          "chapters": { "type": "nested", "properties": {
            "cid": { "type": "keyword" },
            "language": { "type": "keyword" },
            "vector": { "type": "dense_vector", "dims": 2, "similarity": "l2_norm", "index_options": { "type": "flat" } },
            "paragraphs": { "type": "nested", "properties": {
              "pid": { "type": "keyword" },
              "vector": { "type": "dense_vector", "dims": 2, "similarity": "l2_norm", "index_options": { "type": "flat" } }
            } }
          } }
        } } }
        """;

    private static final List<String> BOOKS = List.of("""
        {"genre": "novel", "v": [1, 1], "chapters": [
          {"cid": "c1", "language": "fr", "vector": [1, 0], "paragraphs": [{"pid": "p1", "vector": [1, 0]}]},
          {"cid": "c2", "language": "en", "vector": [0, 1], "paragraphs": [{"pid": "p2", "vector": [0, 1]}]}]}
        """, """
        {"genre": "nature", "v": [1, 0], "chapters": [
          {"cid": "c3", "language": "fr", "vector": [1, 1], "paragraphs": [{"pid": "p3", "vector": [1, 1]}]},
          {"cid": "c4", "language": "en", "vector": [2, 0], "paragraphs": [{"pid": "p4", "vector": [2, 0]}]}]}
        """, """
        {"genre": "novel", "v": [0, 1], "chapters": [
          {"cid": "c5", "language": "de", "vector": [0, 2], "paragraphs": [{"pid": "p5", "vector": [0, 2]}]}]}
        """);

    public void testKnnOnTopLevelVectorInFilter() throws IOException {
        assertInnerHits(knn("v").addFilterQuery(QueryBuilders.termQuery("genre", "novel")), Set.of("c1", "c2", "c5"));
    }

    public void testKnnOnChildVectorInNestedFilter() throws IOException {
        QueryBuilder filter = QueryBuilders.nestedQuery(
            "chapters.paragraphs",
            knn("chapters.paragraphs.vector").addFilterQuery(QueryBuilders.termQuery("genre", "novel"))
                .addFilterQuery(QueryBuilders.termQuery("chapters.language", "fr")),
            ScoreMode.None
        );
        assertInnerHits(filter, Set.of("c1"));
    }

    /** The inner hits of a top level knn search come from a knn score doc query, in the scope of the vector's nested level. */
    public void testTopLevelKnnSearchWithKnnOnTopLevelVectorInFilter() throws IOException {
        QueryBuilder filter = knn("v").addFilterQuery(QueryBuilders.termQuery("genre", "novel"));
        withSearcher((mapperService, searcher) -> {
            SearchExecutionContext context = chaptersScope(mapperService, searcher);
            QueryBuilder innerHitsQuery = Rewriteable.rewrite(
                new KnnScoreDocQueryBuilder(
                    new ScoreDoc[] { new ScoreDoc(0, 1f) },
                    "chapters.vector",
                    VectorData.fromFloats(new float[] { 1, 1 }),
                    null,
                    List.of(filter)
                ),
                new InnerHitsRewriteContext(context.getParserConfig(), System::currentTimeMillis)
            );
            assertInnerHitsQuery(innerHitsQuery, 2);
            assertEquals(Set.of("c1", "c2", "c5"), search(searcher, innerHitsQuery.toQuery(context)));
        });
    }

    /** Runs the outer knn query in the scope of a nested query on chapters, and its inner hits in the same scope. */
    private void assertInnerHits(QueryBuilder filter, Set<String> expected) throws IOException {
        withSearcher((mapperService, searcher) -> {
            SearchExecutionContext context = chaptersScope(mapperService, searcher);
            QueryBuilder knn = Rewriteable.rewrite(knn("chapters.vector").addFilterQuery(filter), context, true);
            Set<String> hits = search(searcher, knn.toQuery(context));

            context = chaptersScope(mapperService, searcher);
            QueryBuilder innerHitsQuery = Rewriteable.rewrite(
                knn,
                new InnerHitsRewriteContext(context.getParserConfig(), System::currentTimeMillis)
            );
            assertInnerHitsQuery(innerHitsQuery, 2);
            Set<String> innerHits = search(searcher, innerHitsQuery.toQuery(context));

            assertTrue("hits " + hits + " are not all in inner hits " + innerHits, innerHits.containsAll(hits));
            assertEquals(expected, innerHits);
        });
    }

    private static KnnVectorQueryBuilder knn(String field) {
        return new KnnVectorQueryBuilder(field, new float[] { 1, 1 }, 100, 100, null, null, null);
    }

    /**
     * Inner hits use exact knn, including for the knn queries in filters, and their nested inner hits can be extracted from the
     * rewritten query like {@code SearchService} does.
     */
    private static void assertInnerHitsQuery(QueryBuilder query, int expectedExactKnnQueries) {
        InnerHitContextBuilder.extractInnerHits(query, new HashMap<>());
        String json = query.toString();
        assertFalse(json, json.contains("\"" + KnnVectorQueryBuilder.NAME + "\""));
        assertEquals(json, expectedExactKnnQueries, json.split("\"" + ExactKnnQueryBuilder.NAME + "\"", -1).length - 1);
    }

    private SearchExecutionContext chaptersScope(MapperService mapperService, IndexSearcher searcher) {
        SearchExecutionContext context = createSearchExecutionContext(mapperService, searcher);
        context.nestedScope().nextLevel(context.nestedLookup().getNestedMappers().get("chapters"));
        return context;
    }

    /** Returns the ids of the chapters that {@code query} matches. */
    private static Set<String> search(IndexSearcher searcher, Query query) throws IOException {
        Set<String> ids = new HashSet<>();
        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
        for (ScoreDoc scoreDoc : searcher.search(query, 10_000).scoreDocs) {
            LeafReaderContext leaf = leaves.get(ReaderUtil.subIndex(scoreDoc.doc, leaves));
            SortedSetDocValues values = leaf.reader().getSortedSetDocValues("chapters.cid");
            assertTrue("matched a document that is not a chapter", values != null && values.advanceExact(scoreDoc.doc - leaf.docBase));
            ids.add(values.lookupOrd(values.nextOrd()).utf8ToString());
        }
        return ids;
    }

    @FunctionalInterface
    interface SearcherTest {
        void accept(MapperService mapperService, IndexSearcher searcher) throws IOException;
    }

    private void withSearcher(SearcherTest test) throws IOException {
        MapperService mapperService = createMapperService(MAPPING);
        withLuceneIndex(mapperService, iw -> {
            for (String book : BOOKS) {
                iw.addDocuments(mapperService.documentMapper().parse(source(book)).docs());
            }
        }, reader -> {
            DirectoryReader esReader = ElasticsearchDirectoryReader.wrap(reader, new ShardId("index", "_na_", 0));
            test.accept(mapperService, newSearcher(esReader, false, false));
        });
    }
}
