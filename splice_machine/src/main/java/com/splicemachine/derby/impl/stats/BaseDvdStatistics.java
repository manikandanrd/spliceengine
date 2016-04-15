package com.splicemachine.derby.impl.stats;

import com.splicemachine.db.iapi.types.DataValueDescriptor;
import com.splicemachine.stats.ColumnStatistics;
import com.splicemachine.stats.cardinality.CardinalityEstimator;
import com.splicemachine.stats.estimate.Distribution;
import com.splicemachine.stats.estimate.EmptyDistribution;

import java.io.Externalizable;

/**
 * @author Scott Fines
 *         Date: 2/27/15
 */
public abstract class BaseDvdStatistics implements ColumnStatistics<DataValueDescriptor>,Externalizable {
    protected ColumnStatistics baseStats;

    public BaseDvdStatistics() {
    }

    public BaseDvdStatistics(ColumnStatistics baseStats) {
        this.baseStats = baseStats;
    }

    @Override public long cardinality() { return baseStats.cardinality(); }
    @Override public float nullFraction() { return baseStats.nullFraction(); }
    @Override public long nullCount() { return baseStats.nullCount(); }
    @Override public int avgColumnWidth() { return baseStats.avgColumnWidth(); }
    @Override public long nonNullCount() { return baseStats.nonNullCount(); }
    @Override public long minCount() { return baseStats.minCount(); }
    @Override public int columnId() { return baseStats.columnId(); }
    @Override public CardinalityEstimator getCardinalityEstimator() { return baseStats.getCardinalityEstimator();}
    @Override public long totalBytes() { return baseStats.totalBytes(); }

    @Override public Distribution<DataValueDescriptor> getDistribution() {
        return newDistribution(baseStats);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ColumnStatistics<DataValueDescriptor> merge(ColumnStatistics<DataValueDescriptor> other) {
        if (other instanceof ColumnAverage) {
            baseStats = (ColumnStatistics) baseStats.merge(other);
        }
        else if (other instanceof BaseDvdStatistics){
            baseStats = (ColumnStatistics) baseStats.merge(((BaseDvdStatistics) other).baseStats);
        }

        return this;
    }

    protected abstract Distribution<DataValueDescriptor> newDistribution(ColumnStatistics baseStats);
}
