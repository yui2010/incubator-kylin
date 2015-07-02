package org.apache.kylin.metadata.realization;

import com.google.common.base.Function;
import com.google.common.collect.BoundType;
import com.google.common.collect.Range;
import org.apache.kylin.common.util.DateFormat;
import org.apache.kylin.metadata.filter.*;
import org.apache.kylin.metadata.model.DataType;
import org.apache.kylin.metadata.model.TblColRef;

/**
 */
public class SQLDigestUtil {

    public static <F, T> T appendTsFilterToExecute(SQLDigest sqlDigest, TblColRef partitionColRef, Range<Long> tsRange, Function<F, T> action) {

        // add the boundary condition to query real-time
        TupleFilter originalFilter = sqlDigest.filter;
        sqlDigest.filter = createFilterForRealtime(originalFilter, partitionColRef, tsRange);

        boolean addFilterColumn = false, addAllColumn = false;

        if (!sqlDigest.filterColumns.contains(partitionColRef)) {
            sqlDigest.filterColumns.add(partitionColRef);
            addFilterColumn = true;
        }

        if (!sqlDigest.allColumns.contains(partitionColRef)) {
            sqlDigest.allColumns.add(partitionColRef);
            addAllColumn = true;
        }

        T ret = action.apply(null);

        // restore the sqlDigest
        sqlDigest.filter = originalFilter;

        if (addFilterColumn)
            sqlDigest.filterColumns.remove(partitionColRef);

        if (addAllColumn)
            sqlDigest.allColumns.remove(partitionColRef);

        return ret;
    }

    //ts column type differentiate
    private static String formatTimeStr(DataType type, long ts) {
        String ret;
        if (type == DataType.getInstance("date")) {
            ret = DateFormat.formatToDateStr(ts);
        } else if (type == DataType.getInstance("long")) {
            ret = String.valueOf(ts);
        } else {
            throw new IllegalArgumentException("Illegal type for partition column " + type);
        }
        return ret;
    }

    private static TupleFilter createFilterForRealtime(TupleFilter originFilter, TblColRef partitionColRef, Range<Long> tsRange) {
        DataType type = partitionColRef.getColumnDesc().getType();

        String startTimeStr, endTimeStr;
        CompareTupleFilter startFilter = null, endFilter = null;
        if (tsRange.hasLowerBound()) {
            startTimeStr = formatTimeStr(type, tsRange.lowerEndpoint());
            if (tsRange.lowerBoundType() == BoundType.CLOSED) {
                startFilter = new CompareTupleFilter(TupleFilter.FilterOperatorEnum.GTE);
            } else {
                startFilter = new CompareTupleFilter(TupleFilter.FilterOperatorEnum.GT);
            }
            ColumnTupleFilter columnTupleFilter = new ColumnTupleFilter(partitionColRef);
            ConstantTupleFilter constantTupleFilter = new ConstantTupleFilter(startTimeStr);
            startFilter.addChild(columnTupleFilter);
            startFilter.addChild(constantTupleFilter);
        }

        if (tsRange.hasUpperBound()) {
            endTimeStr = formatTimeStr(type, tsRange.upperEndpoint());
            if (tsRange.upperBoundType() == BoundType.CLOSED) {
                endFilter = new CompareTupleFilter(TupleFilter.FilterOperatorEnum.LTE);
            } else {
                endFilter = new CompareTupleFilter(TupleFilter.FilterOperatorEnum.LT);
            }
            ColumnTupleFilter columnTupleFilter = new ColumnTupleFilter(partitionColRef);
            ConstantTupleFilter constantTupleFilter = new ConstantTupleFilter(endTimeStr);
            endFilter.addChild(columnTupleFilter);
            endFilter.addChild(constantTupleFilter);
        }

        if (originFilter == null) {
            if (endFilter == null) {
                return startFilter;
            }
            if (startFilter == null) {
                return endFilter;
            }
        }

        LogicalTupleFilter logicalTupleFilter = new LogicalTupleFilter(TupleFilter.FilterOperatorEnum.AND);

        if (originFilter != null) {
            logicalTupleFilter.addChild(originFilter);
        }
        if (startFilter != null) {
            logicalTupleFilter.addChild(startFilter);
        }
        if (endFilter != null) {
            logicalTupleFilter.addChild(endFilter);
        }

        return logicalTupleFilter;
    }
}
