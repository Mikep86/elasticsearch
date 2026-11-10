/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.shard;

import org.apache.lucene.search.BayesianScoreEstimator;
import org.elasticsearch.core.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * Bayesian BM25 parameters estimated for the full-text fields of a shard, and the merged segments they were computed from.
 */
public record BayesianBM25ShardParameters(Set<String> segmentNames, Map<String, BayesianScoreEstimator.Parameters> byField) {

    public BayesianBM25ShardParameters {
        segmentNames = Set.copyOf(segmentNames);
        byField = Map.copyOf(byField);
    }

    /** Returns the parameters estimated for the field, or {@code null} if there are none. */
    @Nullable
    public BayesianScoreEstimator.Parameters get(String field) {
        return byField.get(field);
    }
}
