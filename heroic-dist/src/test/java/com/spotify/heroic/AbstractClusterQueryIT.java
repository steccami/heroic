package com.spotify.heroic;

import static com.spotify.heroic.test.Data.points;
import static com.spotify.heroic.test.Matchers.containsChild;
import static com.spotify.heroic.test.Matchers.hasIdentifier;
import static com.spotify.heroic.test.Matchers.identifierContains;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.spotify.heroic.common.Feature;
import com.spotify.heroic.common.FeatureSet;
import com.spotify.heroic.common.Series;
import com.spotify.heroic.dagger.CoreComponent;
import com.spotify.heroic.ingestion.Ingestion;
import com.spotify.heroic.ingestion.IngestionComponent;
import com.spotify.heroic.ingestion.IngestionManager;
import com.spotify.heroic.metric.MetricCollection;
import com.spotify.heroic.metric.MetricType;
import com.spotify.heroic.metric.QueryError;
import com.spotify.heroic.metric.QueryResult;
import com.spotify.heroic.metric.RequestError;
import com.spotify.heroic.metric.ResultLimit;
import com.spotify.heroic.metric.ResultLimits;
import com.spotify.heroic.metric.ShardedResultGroup;
import eu.toolchain.async.AsyncFuture;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

public abstract class AbstractClusterQueryIT extends AbstractLocalClusterIT {
    private final Series s1 = Series.of("key1", ImmutableMap.of("shared", "a", "diff", "a"));
    private final Series s2 = Series.of("key1", ImmutableMap.of("shared", "a", "diff", "b"));
    private final Series s3 = Series.of("key1", ImmutableMap.of("shared", "a", "diff", "c"));

    private QueryManager query;

    protected boolean cardinalitySupport = true;

    protected void setupSupport() {
    }

    @Before
    public final void setupAbstract() {
        setupSupport();

        query = instances.get(0).inject(CoreComponent::queryManager);
    }

    @Override
    protected AsyncFuture<Void> prepareEnvironment() {
        final List<IngestionManager> ingestion = instances
            .stream()
            .map(i -> i.inject(IngestionComponent::ingestionManager))
            .collect(Collectors.toList());

        final List<AsyncFuture<Ingestion>> writes = new ArrayList<>();

        final IngestionManager m1 = ingestion.get(0);
        final IngestionManager m2 = ingestion.get(1);

        writes.add(m1
            .useDefaultGroup()
            .write(new Ingestion.Request(s1, points().p(10, 1D).p(30, 2D).build())));
        writes.add(m2
            .useDefaultGroup()
            .write(new Ingestion.Request(s2, points().p(10, 1D).p(20, 4D).build())));

        return async.collectAndDiscard(writes);
    }

    public QueryResult query(final String queryString) throws Exception {
        return query(query.newQueryFromString(queryString), builder -> {
        });
    }

    public QueryResult query(final String queryString, final Consumer<QueryBuilder> modifier)
        throws Exception {
        return query(query.newQueryFromString(queryString), modifier);
    }

    public QueryResult query(final QueryBuilder builder, final Consumer<QueryBuilder> modifier)
        throws Exception {
        builder
            .features(Optional.of(FeatureSet.of(Feature.DISTRIBUTED_AGGREGATIONS)))
            .source(Optional.of(MetricType.POINT))
            .rangeIfAbsent(Optional.of(new QueryDateRange.Absolute(10, 40)));

        modifier.accept(builder);
        return query.useDefaultGroup().query(builder.build()).get();
    }

    @Test
    public void basicQueryTest() throws Exception {
        final QueryResult result = query("sum(10ms)");

        final Set<MetricCollection> m = getResults(result);
        final List<Long> cadences = getCadences(result);

        assertEquals(ImmutableList.of(10L), cadences);
        assertEquals(ImmutableSet.of(points().p(10, 2D).p(20, 4D).p(30, 2D).build()), m);
    }

    @Test
    public void distributedQueryTest() throws Exception {
        final QueryResult result = query("sum(10ms) by shared");

        final Set<MetricCollection> m = getResults(result);
        final List<Long> cadences = getCadences(result);

        assertEquals(ImmutableList.of(10L), cadences);
        assertEquals(ImmutableSet.of(points().p(10, 2D).p(20, 4D).p(30, 2D).build()), m);
    }

    @Test
    public void distributedQueryTraceTest() throws Exception {
        final QueryResult result = query("sum(10ms) by shared");

        // Verify that the top level QueryTrace is for CoreQueryManager
        assertThat(result.getTrace(), hasIdentifier(equalTo(CoreQueryManager.QUERY)));
        // Verify that second level is of type QUERY_SHARD
        assertThat(result.getTrace(), containsChild(
            hasIdentifier(identifierContains(CoreQueryManager.QUERY_SHARD.toString()))));

        /* Verify that the third level (under QUERY_SHARD) contains at least one entry for the
         * local node and at least one for the remote node */
        assertThat(result.getTrace(), containsChild(
            allOf(hasIdentifier(identifierContains(CoreQueryManager.QUERY_SHARD.toString())),
                containsChild(hasIdentifier(identifierContains("[local]"))))));
        assertThat(result.getTrace(), containsChild(
            allOf(hasIdentifier(identifierContains(CoreQueryManager.QUERY_SHARD.toString())),
                containsChild(hasIdentifier(not(identifierContains("[local]")))))));
    }

    @Test
    public void distributedDifferentQueryTest() throws Exception {
        final QueryResult result = query("sum(10ms) by diff");

        final Set<MetricCollection> m = getResults(result);
        final List<Long> cadences = getCadences(result);

        assertEquals(ImmutableList.of(10L, 10L), cadences);
        assertEquals(ImmutableSet.of(points().p(10, 1D).p(30, 2D).build(),
            points().p(10, 1D).p(20, 4D).build()), m);
    }

    @Test
    public void distributedFilterQueryTest() throws Exception {
        final QueryResult result = query("average(10ms) by * | topk(2) | bottomk(1) | sum(10ms)");

        final Set<MetricCollection> m = getResults(result);
        final List<Long> cadences = getCadences(result);

        assertEquals(ImmutableList.of(10L), cadences);
        assertEquals(ImmutableSet.of(points().p(10, 1D).p(20, 4D).build()), m);
    }

    @Test
    public void filterQueryTest() throws Exception {
        final QueryResult result =
            query("average(10ms) by * | topk(2) | bottomk(1) | sum(10ms)", builder -> {
                builder.features(Optional.empty());
            });

        final Set<MetricCollection> m = getResults(result);
        final List<Long> cadences = getCadences(result);

        // why not two responses?
        assertEquals(ImmutableList.of(10L, 10L), cadences);
        assertEquals(ImmutableSet.of(points().p(10, 1D).p(20, 4D).build(),
            points().p(10, 1D).p(30, 2D).build()), m);
    }

    @Test
    public void deltaQueryTest() throws Exception {
        final QueryResult result = query("delta", builder -> {
            builder.features(Optional.empty());
        });

        final Set<MetricCollection> m = getResults(result);
        final List<Long> cadences = getCadences(result);

        assertEquals(ImmutableList.of(-1L, -1L), cadences);
        assertEquals(ImmutableSet.of(points().p(30, 1D).build(), points().p(20, 3D).build()), m);
    }

    @Test
    public void distributedDeltaQueryTest() throws Exception {
        final QueryResult result = query("max | delta");

        final Set<MetricCollection> m = getResults(result);
        final List<Long> cadences = getCadences(result);

        assertEquals(ImmutableList.of(1L), cadences);
        assertEquals(ImmutableSet.of(points().p(20, 3D).p(30, -2D).build()), m);
    }

    @Test
    public void filterLastQueryTest() throws Exception {
        final QueryResult result = query("average(10ms) by * | topk(2) | bottomk(1)");

        final Set<MetricCollection> m = getResults(result);
        final List<Long> cadences = getCadences(result);

        assertEquals(ImmutableList.of(10L), cadences);
        assertEquals(ImmutableSet.of(points().p(10, 1D).p(20, 4D).build()), m);
    }

    @Test
    public void cardinalityTest() throws Exception {
        assumeTrue(cardinalitySupport);

        final QueryResult result = query("cardinality(10ms)");

        final Set<MetricCollection> m = getResults(result);
        final List<Long> cadences = getCadences(result);

        assertEquals(ImmutableList.of(10L), cadences);
        assertEquals(ImmutableSet.of(points().p(10, 1D).p(20, 1D).p(30, 1D).p(40, 0D).build()), m);
    }

    @Test
    public void cardinalityWithKeyTest() throws Exception {
        assumeTrue(cardinalitySupport);

        // TODO: support native booleans in expressions
        final QueryResult result = query("cardinality(10ms, method=hllp(includeKey=\"true\"))");

        final Set<MetricCollection> m = getResults(result);
        final List<Long> cadences = getCadences(result);

        assertEquals(ImmutableList.of(10L), cadences);
        assertEquals(ImmutableSet.of(points().p(10, 2D).p(20, 1D).p(30, 1D).p(40, 0D).build()), m);
    }

    @Test
    public void dataLimit() throws Exception {
        final QueryResult result = query("*", builder -> {
            builder.options(Optional.of(QueryOptions.builder().dataLimit(1L).build()));
        });

        // quota limits are always errors
        assertEquals(2, result.getErrors().size());

        for (final RequestError e : result.getErrors()) {
            assertTrue((e instanceof QueryError));
            final QueryError q = (QueryError) e;
            assertThat(q.getError(),
                containsString("Some fetches failed (1) or were cancelled (0)"));
        }

        assertEquals(ResultLimits.of(ResultLimit.QUOTA), result.getLimits());
    }

    @Test
    public void aggregationLimit() throws Exception {
        final QueryResult result = query("sum(10ms) by *", builder -> {
            builder.options(Optional.of(QueryOptions.builder().aggregationLimit(1L).build()));
        });

        // quota limits are always errors
        assertEquals(2, result.getErrors().size());

        for (final RequestError e : result.getErrors()) {
            assertTrue((e instanceof QueryError));
            final QueryError q = (QueryError) e;
            assertThat(q.getError(), containsString("Query exceeded quota"));
        }

        assertEquals(ResultLimits.of(ResultLimit.AGGREGATION), result.getLimits());
    }

    @Test
    public void groupLimit() throws Exception {
        final QueryResult result = query("*", builder -> {
            builder.options(Optional.of(QueryOptions.builder().groupLimit(1L).build()));
        });

        assertEquals(0, result.getErrors().size());
        assertEquals(ResultLimits.of(ResultLimit.GROUP), result.getLimits());
        assertEquals(1, result.getGroups().size());
    }

    @Test
    public void seriesLimitFailure() throws Exception {
        final QueryResult result = query("*", builder -> {
            builder.options(
                Optional.of(QueryOptions.builder().seriesLimit(0L).failOnLimits(true).build()));
        });

        assertEquals(2, result.getErrors().size());

        for (final RequestError e : result.getErrors()) {
            assertTrue((e instanceof QueryError));
            final QueryError q = (QueryError) e;
            assertThat(q.getError(), containsString(
                "The number of series requested is more than the allowed limit of [0]"));
        }

        assertEquals(ResultLimits.of(ResultLimit.SERIES), result.getLimits());
    }

    @Test
    public void groupLimitFailure() throws Exception {
        final QueryResult result = query("*", builder -> {
            builder.options(
                Optional.of(QueryOptions.builder().groupLimit(0L).failOnLimits(true).build()));
        });

        assertEquals(2, result.getErrors().size());

        for (final RequestError e : result.getErrors()) {
            assertTrue((e instanceof QueryError));
            final QueryError q = (QueryError) e;
            assertThat(q.getError(), containsString(
                "The number of result groups is more than the allowed limit of [0]"));
        }

        assertEquals(ResultLimits.of(ResultLimit.GROUP), result.getLimits());
        assertEquals(0, result.getGroups().size());
    }

    private Set<MetricCollection> getResults(final QueryResult result) {
        return result
            .getGroups()
            .stream()
            .map(ShardedResultGroup::getMetrics)
            .collect(Collectors.toSet());
    }

    private List<Long> getCadences(final QueryResult result) {
        final List<Long> cadences = result
            .getGroups()
            .stream()
            .map(ShardedResultGroup::getCadence)
            .collect(Collectors.toList());

        Collections.sort(cadences);
        return cadences;
    }
}
