/*
 * Copyright (c) 2015 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.heroic.metadata;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.ImmutableSet;
import com.spotify.heroic.common.DateRange;
import com.spotify.heroic.common.OptionalLimit;
import com.spotify.heroic.common.Series;
import com.spotify.heroic.filter.Filter;
import lombok.Data;

import java.util.Set;

@Data
public class FindSeriesStream {
    private final Set<Series> series;

    public static FindSeriesStream of() {
        return new FindSeriesStream(ImmutableSet.of());
    }

    public static FindSeriesStream of(final Set<Series> series) {
        return new FindSeriesStream(series);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return series.isEmpty();
    }

    @Data
    public static class Request {
        private final Filter filter;
        private final DateRange range;
        private final OptionalLimit limit;
    }
}
